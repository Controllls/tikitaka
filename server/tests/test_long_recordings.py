from contextlib import contextmanager
import json

import numpy as np
import pytest

from momo.audio_lab import stages, worker
from momo.audio_lab.models import AudioConfig, catalog
from momo.audio_lab.topic_windows import windows, stitch
from momo.config import settings
from momo.highlights.topics import validate_partition


def transcript(count=3000):
    return [{"id": i + 1, "startMs": i * 3600, "endMs": (i + 1) * 3600,
             "text": "긴 대화의 앞뒤 문맥과 주제를 이어서 분석하는 문장입니다."} for i in range(count)]


def test_three_hour_transcript_is_covered_with_overlap_without_artificial_boundaries():
    rows = transcript()
    batches = windows(rows)
    assert len(rows) > 1200 and sum(len(r["text"]) for r in rows) > 60_000
    assert len(batches) > 1
    assert [i for w in batches for i in range(w.first, w.last + 1)] == list(range(1, len(rows) + 1))
    actual_starts = [1, 310, 700, 1100, 1700, 2300, 2700]
    partitions = []
    for index, w in enumerate(batches):
        assert sum(len(r["text"].encode("utf-8")) + 48 for r in w.context) <= 9600
        if index:
            assert w.context[0]["id"] < w.first
        if index < len(batches) - 1:
            assert w.context[-1]["id"] > w.last
        ids = sorted({w.context[0]["id"]} | {s for s in actual_starts if w.context[0]["id"] <= s <= w.context[-1]["id"]})
        parts = validate_partition({"topics": [{"start_id": s, "title": "대화", "reason": "문맥"} for s in ids]}, w.context)
        partitions.append((w, parts))
    joined = stitch(rows, partitions)
    assert [p["start_id"] for p in joined] == actual_starts
    assert joined[-1]["end_id"] == len(rows)
    assert all(a["end_id"] + 1 == b["start_id"] for a, b in zip(joined, joined[1:]))


def test_partition_keeps_one_local_model_loaded_and_reports_batches(monkeypatch, tmp_path):
    loads, calls, progress = [], [], []
    @contextmanager
    def local(*args, **kwargs):
        loads.append(1)
        yield "http://localhost/model"
    def respond(url, body, timeout):
        context = json.loads(body["messages"][1]["content"])["utterances"]
        calls.append(context)
        return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(
            {"topics": [{"start_id": context[0]["id"], "title": "계속되는 이야기", "reason": "같은 문맥"}]})}}]}
    monkeypatch.setattr(worker, "local_model", local)
    monkeypatch.setattr(worker, "http_json", respond)
    rows = transcript()
    parts, metadata = worker.partition(rows, "qwen-local", AudioConfig(), tmp_path, None, tmp_path, progress.append)
    assert len(loads) == 1
    assert len(calls) == metadata["analysis_windows"] > 1
    assert parts[0]["start_id"] == 1 and parts[0]["end_id"] == len(rows) and len(parts) == 1
    assert progress[0] == 0 and progress[-1] == 1


def test_decode_rejects_over_limit_before_expensive_pcm_conversion(monkeypatch, tmp_path):
    calls = []
    def run(args, **kwargs):
        calls.append(args)
        class Probe:
            stdout = json.dumps({"streams": [{"index": 0}], "format": {"duration": settings.lab_max_seconds + 1}}).encode()
        return Probe()
    monkeypatch.setattr(stages.subprocess, "run", run)
    with pytest.raises(ValueError, match="시간"):
        stages.decode(tmp_path / "long.media", tmp_path)
    assert len(calls) == 1 and calls[0][0] == settings.ffprobe
    assert not (tmp_path / "audio.f32").exists()


def test_local_topic_retry_corrects_global_ids_without_changing_boundaries(monkeypatch, tmp_path):
    calls = {}
    @contextmanager
    def local(*args, **kwargs):
        yield "http://localhost/model"

    def respond(url, body, timeout):
        context = json.loads(body["messages"][1]["content"])["utterances"]
        first = context[0]["id"]
        calls[first] = calls.get(first, 0) + 1
        assert f"topics[0].start_id는 반드시 {first}" in body["messages"][0]["content"]
        if first != 1 and calls[first] == 1:
            start = 1  # Model incorrectly renumbers an overlapping later window.
        else:
            if calls[first] == 2:
                assert "이전 응답은 검증에 실패" in body["messages"][0]["content"]
            start = first
        return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(
            {"topics": [{"start_id": start, "title": "one story", "reason": "context"}]})}}]}

    monkeypatch.setattr(worker, "local_model", local)
    monkeypatch.setattr(worker, "http_json", respond)
    rows = transcript(500)
    parts, metadata = worker.partition(rows, "qwen-local", AudioConfig(), tmp_path, None, tmp_path)
    assert len(calls) > 1 and max(calls.values()) == 2
    assert len(metadata["requests"]) == sum(calls.values())
    assert [(p["start_id"], p["end_id"]) for p in parts] == [(1, len(rows))]
    assert len(list(tmp_path.glob("topic-invalid-*.json"))) == len(calls) - 1


def test_local_topic_invalid_retry_still_fails_instead_of_inventing_clips(monkeypatch, tmp_path):
    calls = []
    @contextmanager
    def local(*args, **kwargs):
        yield "http://localhost/model"
    def respond(*args, **kwargs):
        calls.append(1)
        return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(
            {"topics": [{"start_id": 2, "title": "bad start", "reason": "invalid"}]})}}]}
    monkeypatch.setattr(worker, "local_model", local)
    monkeypatch.setattr(worker, "http_json", respond)
    with pytest.raises(ValueError, match="한 번 교정"):
        worker.partition(transcript(10), "qwen-local", AudioConfig(), tmp_path, None, tmp_path)
    assert len(calls) == 2


def test_partition_uses_selected_api_model_in_each_window_without_resetting_ids(monkeypatch, tmp_path):
    calls = []
    async def request(client, credentials, method, path, payload):
        context = json.loads(payload["input"])["utterances"]
        calls.append(payload)
        text = json.dumps({"topics": [{"start_id": context[0]["id"], "title": "연결된 주제", "reason": "문맥"}]})
        return {"status": "completed", "model": payload["model"], "output": [
            {"type": "message", "content": [{"type": "output_text", "text": text}]}]}, "test-request"
    monkeypatch.setattr("momo.topic_lab.openai_request", request)
    rows = transcript()
    parts, metadata = worker.partition(rows, "openai:test-model", AudioConfig(), tmp_path, object(), tmp_path)
    assert len(calls) == metadata["analysis_windows"] > 1
    assert all(p["model"] == "test-model" and p["store"] is False for p in calls)
    assert json.loads(calls[-1]["input"])["utterances"][0]["id"] > 1
    assert [(p["start_id"], p["end_id"]) for p in parts] == [(1, len(rows))]


def test_long_source_cannot_enter_short_character_schema_after_expensive_inference(monkeypatch, tmp_path):
    class LongPCM:
        def __len__(self):
            return 10800 * 16000
    monkeypatch.setattr(stages, "decode", lambda *_: LongPCM())
    def unexpected(*_):
        pytest.fail("Character render limit must be checked before inference")
    monkeypatch.setattr(stages, "vad", unexpected)
    payload = {"id": "long", "directory": str(tmp_path), "source": str(tmp_path / "source"),
               "config": AudioConfig().model_dump(), "input": {"name": "long.wav", "sha256": "test"},
               "scene": {"output": "full"}}
    with pytest.raises(ValueError, match="하이라이트"):
        worker.run(payload)
    result = json.loads((tmp_path / "partial.json").read_text(encoding="utf-8"))
    assert result["stages"] == {}


def test_catalog_supports_three_hour_original_and_separate_short_video_contract():
    limits = catalog()
    assert limits["max_file_bytes"] >= 10_000_000_000
    assert limits["max_seconds"] >= 10800
    assert limits["max_video_seconds"] == 600


def test_long_stt_chunks_keep_original_times(monkeypatch, tmp_path):
    # A broadcast view has the three-hour clock without retaining a 691 MB test file.
    # Actual file decoding is covered by long_recording_smoke.py.
    pcm = np.broadcast_to(np.zeros(1, np.float32), (10800 * 16000,))
    sizes, progress = [], []
    def transcribe(chunk, model, credentials):
        sizes.append(len(chunk))
        return {"text": "끝부분 대화", "usage": {}}
    monkeypatch.setattr(stages, "audio_api", transcribe)
    result = stages.transcribe(pcm, "openai:gpt-transcribe", tmp_path,
        {"spans": [{"startMs": 10740000, "endMs": 10800000}]}, object(), progress.append)
    assert sizes == [28000 * 16, 28000 * 16, 4000 * 16]
    assert result["utterances"][0]["startMs"] == 10740000
    assert result["utterances"][-1]["endMs"] == 10800000
    assert all(a <= b for a, b in zip(progress, progress[1:]))
