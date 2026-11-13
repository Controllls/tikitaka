import copy
import json

import numpy as np
import pytest

from momo.audio_lab import worker, segment_worker
from momo.audio_lab.models import AudioConfig
from momo.audio_lab.segment_core import (
    CharacterMotion,
    assemble_characters,
    independent_tracks,
    plan_segments,
    speaker_matches,
    trim_utterances,
)
from momo.audio_lab.service import SegmentAction
from momo.video_lab.characters import load_characters
from test_audio_lab import audio_client as base_audio_client, fake_process, upload

segment_client = base_audio_client


def lane(identity, speaker, start=0, end=1000, *, laugh=0.0, speech=0.9, energy=0.01):
    return dict(
        id=identity,
        group_id=identity.split("_")[0],
        speaker=speaker,
        state="complete",
        startMs=start,
        endMs=end,
        contextStartMs=start,
        contextEndMs=end,
        speech=[speech] * ((end - start) // 20),
        laughter=[laugh] * ((end - start) // 20),
        energy=[energy] * ((end - start) // 20),
        energy_reference=0.01,
        utterances=[dict(startMs=start, endMs=end, text="hello", words=[])],
        separated=False,
        audio_file=None,
        review_reasons=[],
    )


def test_independent_speech_and_laughter_and_non_vad_candidates():
    acoustic = dict(
        vad=[0.9] * 32,
        energy=[0.01] * 50,
        rms=[0.01] * 50,
        frames=[dict(laughter=0.3, otherVocal=0.01, nonVocal=0.01)] * 7,
    )
    speech, laughter, candidates, _ = independent_tracks(acoustic, 1000)
    assert np.all(speech > 0.8) and np.all(laughter >= 0.3)
    activity = np.zeros((50, 8))
    activity[:, 0] = 0.9
    groups = plan_segments(activity, np.zeros(50), candidates, 1000)
    assert groups and groups[0]["startMs"] == 0 and groups[-1]["endMs"] == 1000


def test_core_intervals_do_not_overlap_or_lose_clock_and_bound_context():
    activity = np.zeros((1100, 8))
    activity[:500, 0] = 0.9
    activity[500:, 1] = 0.9
    groups = plan_segments(activity, np.ones(1100), np.zeros(1100), 22000)
    assert groups[0]["startMs"] == 0 and groups[-1]["endMs"] == 22000
    assert all(a["endMs"] == b["startMs"] for a, b in zip(groups, groups[1:]))
    assert all(
        g["endMs"] - g["startMs"] <= 8000 and g["contextEndMs"] - g["contextStartMs"] <= 9000 for g in groups
    )
    assert any(g["columns"] == [1] for g in groups)
    json.dumps(groups)  # NumPy scalars must never leak into saved jobs.


def test_matching_is_independent_of_separator_output_order_and_rejects_duplicates():
    anchors = {"S1": np.array([1.0, 0]), "S2": np.array([0.0, 1])}
    assert [m["speaker"] for m in speaker_matches([anchors["S2"], anchors["S1"]], anchors)] == ["S2", "S1"]
    assert [m["speaker"] for m in speaker_matches([anchors["S1"], anchors["S1"]], anchors)] == [None, None]
    assert speaker_matches([None], anchors)[0]["speaker"] is None


def test_padding_words_owned_once_and_keep_original_clock():
    row = dict(
        startMs=0,
        endMs=2000,
        text="hello world",
        words=[dict(startMs=500, endMs=1100, text=" hello"), dict(startMs=1100, endMs=1900, text=" world")],
    )
    first = trim_utterances([row], 10000, 11000, 10000)
    second = trim_utterances([row], 11000, 12000, 10000)
    assert first[0]["text"] == "hello" and second[0]["text"] == "world"
    assert first[0]["endMs"] == 11000 and second[0]["startMs"] == 11100


def test_two_characters_speak_and_laugh_simultaneously_with_independent_energy(tmp_path):
    lanes = [lane("G000001_0", "S1", energy=0), lane("G000001_1", "S2", laugh=0.3, speech=0)]
    speakers = [dict(speaker_id="S1", first_seen_us=0), dict(speaker_id="S2", first_seen_us=0)]
    source = dict(sha256="0" * 64, durationMs=1000)
    result, tracks, words, events = assemble_characters("test", source, [], lanes, speakers)
    assert len(events) == 1 and any(
        c["text"] == "[웃음]" and c["speakers"] == ["S2"] for c in result["captions"]
    )
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    assert motion.at("P1", 500000)["mouth"] == 0
    assert motion.at("P2", 500000)["laughing"] and motion.at("P2", 500000)["mouth"] > 0
    assert motion.at("P2", 1000000)["mouth"] == 0
    (tmp_path / "character-result.json").write_text(json.dumps(result), encoding="utf-8")
    (tmp_path / "character-tracks.json").write_text(json.dumps(tracks), encoding="utf-8")
    experiment = dict(id="test", input=source)
    loaded, _, _ = load_characters(experiment, tmp_path)
    assert len(loaded.speakers) == 2
    with pytest.raises(ValueError, match="일치"):
        load_characters(dict(id="wrong", input=source), tmp_path)


def cached_job(tmp_path):
    prior = tmp_path / "prior"
    prior.mkdir()
    config = AudioConfig(pipeline="segments").model_dump()
    source = dict(sha256="0" * 64, durationMs=2000, name="test.wav")
    groups = [
        dict(
            id=f"G{i + 1:06d}",
            startMs=i * 1000,
            endMs=(i + 1) * 1000,
            contextStartMs=i * 1000,
            contextEndMs=(i + 1) * 1000,
            separate=False,
            more_than_two=False,
        )
        for i in range(2)
    ]
    speakers = [dict(speaker_id="S1", first_seen_us=0), dict(speaker_id="S2", first_seen_us=1000000)]
    rows = [lane(g["id"] + "_0", f"S{i + 1}", g["startMs"], g["endMs"]) for i, g in enumerate(groups)]
    character, tracks, utterances, _ = assemble_characters("prior", source, groups, rows, speakers)
    np.zeros(32000, np.float32).tofile(prior / "audio.f32")
    np.savez_compressed(prior / "segment-base.npz", activity=np.zeros((100, 8)), recovered=np.zeros((100, 8)))
    base = dict(
        policy="character-segments-v1",
        source_sha256=source["sha256"],
        mapping={0: "S1", 1: "S2"},
        anchors={},
        groups=groups,
        speakers=speakers,
    )
    original = dict(
        id="prior",
        input=source,
        config=config,
        analysis_policy="character-segments-v1",
        topics=[],
        clips=[],
        utterances=utterances,
        stages={key: dict(data={"execution": {}}) for key in AudioConfig(pipeline="segments").stages()},
    )
    for name, value in [
        ("segment-plan.json", base),
        ("result.json", original),
        ("character-result.json", character),
        ("character-tracks.json", tracks),
    ]:
        (prior / name).write_text(json.dumps(value), encoding="utf-8")
    for row in rows:
        (prior / f"segment_{row['group_id']}.json").write_text(json.dumps([row]), encoding="utf-8")
    return prior, original, rows


def test_manual_review_reuses_models_preserves_old_record_and_updates_job_ids(tmp_path, monkeypatch):
    prior, original, rows = cached_job(tmp_path)
    output = tmp_path / "review"
    output.mkdir()
    monkeypatch.setattr(
        segment_worker, "SegmentEngine", lambda *a: pytest.fail("Manual review must not load models")
    )
    worker.run(
        dict(
            id="review",
            directory=str(output),
            source="unused",
            input=original["input"],
            config=original["config"],
            reuse=str(prior / "result.json"),
            segment_action=dict(segment_id="G000001_0", speaker_id="S2", rerun=False),
        )
    )
    result = json.loads((output / "result.json").read_text(encoding="utf-8"))
    assert result["characters"]["segments"][0]["speaker"] == "S2"
    assert result["stages"]["stt"]["data"]["processed_groups"] == 0
    assert json.loads((prior / "segment_G000001.json").read_text())[0]["speaker"] == "S1"
    assert json.loads((output / "character-tracks.json").read_text(encoding="utf-8"))["job_id"] == "review"
    assert result["characters"]["captions"][0]["speakers"] == ["S2"]


def test_selective_retry_calls_only_target_group_and_keeps_other_cache(tmp_path, monkeypatch):
    from momo.inference import acoustic, voice

    prior, original, rows = cached_job(tmp_path)
    output = tmp_path / "retry"
    output.mkdir()
    called = []

    class Engine:
        execution = {"device": "test"}
        separation_execution = {}

        def __init__(self, *args):
            pass

        def separate(self, groups, report):
            called.append([g["id"] for g in groups])

        def analyze(self, groups, completed, report):
            for g in groups:
                replacement = copy.deepcopy(rows[0])
                replacement["utterances"][0]["text"] = "retried"
                completed(g, [replacement])

    monkeypatch.setattr(segment_worker, "SegmentEngine", Engine)
    monkeypatch.setattr(acoustic, "AcousticEngine", lambda *a: object())
    monkeypatch.setattr(voice, "VoiceEncoder", lambda *a: object())
    monkeypatch.setattr(worker, "partition", lambda *a: ([], {}))
    worker.run(
        dict(
            id="retry",
            directory=str(output),
            source="unused",
            input=original["input"],
            config=original["config"],
            reuse=str(prior / "result.json"),
            segment_action=dict(segment_id="G000001_0", rerun=True),
        )
    )
    assert called == [["G000001"]]
    assert (output / "segment_G000002.json").read_bytes() == (prior / "segment_G000002.json").read_bytes()
    result = json.loads((output / "result.json").read_text(encoding="utf-8"))
    assert (
        result["utterances"][0]["text"] == "retried" and result["stages"]["stt"]["data"]["reused_groups"] == 1
    )


def test_segment_request_rejects_path_traversal_and_invalid_speaker():
    for name in ["../other", "G000001_2", "G1_0"]:
        with pytest.raises(ValueError):
            SegmentAction(segment_id=name)
    with pytest.raises(ValueError):
        SegmentAction(segment_id="G000001_0", speaker_id="S9")
    with pytest.raises(ValueError):
        AudioConfig(pipeline="segments", mode="vad")


def test_zero_duration_words_keep_text_without_invalid_word_intervals():
    rows = [
        dict(
            startMs=0,
            endMs=800,
            text="hello there",
            words=[dict(startMs=0, endMs=0, text=" hello"), dict(startMs=0, endMs=800, text=" there")],
        )
    ]
    trimmed = trim_utterances(rows, 0, 1000)
    assert trimmed[0]["text"] == "hello there"
    assert all(w["endMs"] > w["startMs"] for w in trimmed[0]["words"])


def test_original_laughter_survives_separator_suppression_without_guessing_identity():
    source = dict(sha256="0" * 64, durationMs=1000)
    rows = [lane("G000001_0", "S1", laugh=0)]
    original = [dict(startMs=100, endMs=500, maxScore=0.3)]
    result, tracks, _, events = assemble_characters(
        "test",
        source,
        [dict(id="G000001", startMs=0, endMs=1000)],
        rows,
        [dict(speaker_id="S1", first_seen_us=0)],
        source_events=original,
    )
    assert events and result["unresolved_laughter_events"] == 0
    assert result["laughter_events"][0]["speaker"] == "S1"
    assert result["provisional_laughter_events"] == 1
    assert any(c["text"] == "[웃음]" and c["speakers"] == ["S1"] and c["requires_review"] for c in result["captions"])
    assert not any(tracks["lanes"][0]["laughter"])
    assert CharacterMotion(tracks, {"S1": "P1"}).at("P1", 200000)["laughing"]


def test_short_clean_turns_jointly_supply_speaker_anchor():
    from momo.audio_lab.segment_engine import anchors_from_speech

    class Encoder:
        def encode(self, pcm):
            assert len(pcm) >= 48000
            return np.array([1.0, 0.0])

    activity = np.zeros((500, 8))
    for start in (0, 100, 200, 300):
        activity[start : start + 50, 0] = 0.9
    anchors, records = anchors_from_speech(
        np.ones(160000), activity, np.ones(500), np.zeros(500), {0: "S1"}, Encoder()
    )
    assert "S1" in anchors and records[0]["speechMs"] >= 3000


def test_preview_and_review_enforce_owner_and_manual_review_needs_no_api_key(
    segment_client, monkeypatch, tmp_path
):
    client, lab = segment_client
    captured = []
    fake_process(monkeypatch, captured)
    upload_id = upload(client)
    identity = client.post(
        "/api/audio/jobs", json={"upload_id": upload_id, "config": {"pipeline": "segments"}}
    ).json()["id"]
    directory = lab.jobs[identity].directory
    prior, original, _ = cached_job(tmp_path)
    original["config"]["topics"] = "openai:test-model"
    for path in prior.iterdir():
        (directory / path.name).write_bytes(path.read_bytes())
    (directory / "result.json").write_text(json.dumps(original), encoding="utf-8")
    (directory / "progress.json").write_text(json.dumps(dict(state="complete", stage="complete", stages=[])))
    response = client.get(f"/api/audio/jobs/{identity}/segments/G000001_0/audio")
    assert response.status_code == 200 and response.content.startswith(b"RIFF")
    body = dict(reuse_job_id=identity, segment_action=dict(segment_id="G000001_0", speaker_id="S2"))
    assert client.post("/api/audio/jobs", json=body).status_code == 200
    assert "credentials" not in captured[-1]
    body["segment_action"]["rerun"] = True
    assert client.post("/api/audio/jobs", json=body).status_code == 401
    assert client.get(f"/api/audio/jobs/{identity}/segments/G000099_0/audio").status_code == 404
    lab.jobs[identity].owner = "different-session"
    assert client.get(f"/api/audio/jobs/{identity}/segments/G000001_0/audio").status_code == 404
    assert client.post("/api/audio/jobs", json=body).status_code == 404


def test_resume_failed_job_uses_cached_segments_and_retries_topic_without_reinference(tmp_path, monkeypatch):
    prior, original, _ = cached_job(tmp_path)
    original["partial"] = True
    original["stages"].pop("stt")
    original["stages"].pop("topics")
    (prior / "partial.json").write_text(json.dumps(original), encoding="utf-8")
    output = tmp_path / "resumed"
    output.mkdir()
    monkeypatch.setattr(
        segment_worker, "SegmentEngine", lambda *args: pytest.fail("Cached segments must be reused")
    )
    calls = []

    def partition(*args):
        calls.append(args[0])
        return [], {}

    monkeypatch.setattr(worker, "partition", partition)
    worker.run(
        dict(
            id="resumed",
            directory=str(output),
            source="unused",
            input=original["input"],
            config=original["config"],
            reuse=str(prior / "partial.json"),
            resume_segments=True,
        )
    )
    result = json.loads((output / "result.json").read_text(encoding="utf-8"))
    assert len(calls) == 1 and len(calls[0]) == 2
    assert result["stages"]["stt"]["data"]["processed_groups"] == 0
