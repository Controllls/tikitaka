import io
import json
import os

from fastapi.testclient import TestClient
import httpx
import numpy as np
import pytest

from momo.audio_lab.models import AudioConfig, STAGES
from momo.audio_lab import service, stages, worker
from momo.topic_lab import Connection, create_app


@pytest.fixture
def audio_client(monkeypatch):
    monkeypatch.setattr("momo.topic_lab.environment_credentials", lambda: None)
    app = create_app()
    with TestClient(app, base_url="http://127.0.0.1:8770") as client:
        token = client.get("/api/session").json()["csrf"]
        client.headers["X-Lab-Token"] = token
        yield client, app.state.audio_lab


def upload(client, data=b"audio contents"):
    result = client.post("/api/audio/uploads?filename=recording.wav", content=data,
                         headers={"Content-Type": "application/octet-stream"})
    assert result.status_code == 200, result.text
    return result.json()["upload_id"]


def fake_process(monkeypatch, captured, *, running=False):
    class Input(io.BytesIO):
        def close(self):
            captured.append(json.loads(self.getvalue()))
            super().close()

    class Process:
        pid = os.getpid()
        def __init__(self, *args, **kwargs):
            self.stdin = Input()
            self.done = not running

        def poll(self):
            return 0 if self.done else None

    monkeypatch.setattr(service.subprocess, "Popen", Process)
    monkeypatch.setattr(service.AudioLab, "stop", lambda self, job: setattr(job.process, "done", True))


def test_catalog_has_six_independently_configurable_stages(audio_client):
    client, _ = audio_client
    data = client.get("/api/audio/catalog").json()
    assert {s["id"] for s in data["stages"]} == set(STAGES)
    assert all(len(s["options"]) >= 2 for s in data["stages"])
    assert client.get("/").status_code == 200
    assert "audio.js" in client.get("/").text
    assert "app.js" in client.get("/text").text


def test_uploaded_audio_stream_owner_and_size(audio_client):
    client, lab = audio_client
    identity = upload(client)
    assert lab.uploads[identity].path.read_bytes() == b"audio contents"
    assert client.post("/api/audio/uploads", content=b"").status_code == 422
    assert client.post("/api/audio/uploads", content=b"x", headers={"Content-Length": "10000000001"}).status_code == 413
    assert client.post("/api/audio/uploads", content=b"x", headers={"X-Lab-Token": "wrong"}).status_code == 403
    client.cookies.clear()
    client.headers["X-Lab-Token"] = client.get("/api/session").json()["csrf"]
    assert client.post("/api/audio/jobs", json={"upload_id": identity}).status_code == 404


def test_original_limit_is_separate_from_designer_sources(audio_client, monkeypatch):
    client, lab = audio_client
    assert client.post("/api/audio/uploads", content=b"x", headers={"Content-Length": "80000001"}).status_code == 200
    assert client.post("/api/video/assets", content=b"x", headers={"Content-Length": "80000001"}).status_code == 413
    monkeypatch.setattr(service.settings, "lab_max_audio_bytes", 10)
    before = set(lab.root.iterdir())
    response = client.post("/api/audio/uploads", content=iter([b"first chunk", b"second chunk"]))
    assert response.status_code == 413
    assert set(lab.root.iterdir()) == before  # No partial file after chunked upload rejection.
    assert lab.uploading is False


def test_original_upload_rejects_insufficient_disk_before_writing(audio_client, monkeypatch):
    from types import SimpleNamespace
    client, lab = audio_client
    monkeypatch.setattr(service.shutil, "disk_usage", lambda _: SimpleNamespace(free=100))
    response = client.post("/api/audio/uploads", content=b"file")
    assert response.status_code == 507
    assert not list(lab.root.iterdir())


def test_all_six_selections_reach_worker_without_changing_default_settings(audio_client, monkeypatch):
    client, _ = audio_client
    captured = []
    fake_process(monkeypatch, captured)
    config = AudioConfig(pipeline="experiment", vad="silero-v6", laughter="ast", stt="whisper-tiny", diarization="xvector-cluster", voice="xvector", topics="qwen-local")
    response = client.post("/api/audio/jobs", json={"upload_id": upload(client), "config": config.model_dump()})
    assert response.status_code == 200
    assert captured[0]["config"] == config.model_dump()
    assert "credentials" not in captured[0]
    assert AudioConfig(pipeline="experiment", ).vad == "silero-v5"


def test_openai_key_only_required_for_executed_stages(audio_client, monkeypatch):
    client, lab = audio_client
    identity = upload(client)
    captured = []
    fake_process(monkeypatch, captured)
    config = AudioConfig(pipeline="experiment", stt="openai:gpt-transcribe", topics="openai:gpt-test")
    assert client.post("/api/audio/jobs", json={"upload_id": identity, "config": config.model_dump()}).status_code == 401
    config.mode = "vad"
    assert client.post("/api/audio/jobs", json={"upload_id": identity, "config": config.model_dump()}).status_code == 200
    client.post("/api/connection", json={"api_key": "sk-audio-test-secret-not-real"})
    config.mode = "all"
    response = client.post("/api/audio/jobs", json={"upload_id": identity, "config": config.model_dump()})
    assert response.status_code == 200
    assert captured[-1]["credentials"]["api_key"] == "sk-audio-test-secret-not-real"
    assert "sk-audio-test" not in response.text
    assert all(b"sk-audio-test" not in p.read_bytes() for p in lab.root.rglob("*") if p.is_file())


def test_busy_cancellation_and_artifact_access(audio_client, monkeypatch):
    client, lab = audio_client
    captured = []
    fake_process(monkeypatch, captured, running=True)
    identity = upload(client)
    job_id = client.post("/api/audio/jobs", json={"upload_id": identity}).json()["id"]
    assert client.post("/api/audio/jobs", json={"upload_id": identity}).status_code == 409
    assert client.post("/api/audio/uploads", content=b"x").status_code == 409
    job = lab.jobs[job_id]
    (job.directory / "clip_1.m4a").write_bytes(b"clip")
    (job.directory / "clip_1.mp4").write_bytes(b"video clip")
    assert client.get(f"/api/audio/jobs/{job_id}/files/clip_1.m4a").status_code == 404
    assert client.get(f"/api/audio/jobs/{job_id}/files/clip_1.mp4").status_code == 404
    (job.directory / "result.json").write_text("{}")
    assert client.get(f"/api/audio/jobs/{job_id}/files/clip_1.m4a").content == b"clip"
    video = client.get(f"/api/audio/jobs/{job_id}/files/clip_1.mp4")
    assert video.content == b"video clip" and video.headers["content-type"] == "video/mp4"
    assert client.get(f"/api/audio/jobs/{job_id}/files/clip_1.mp4", headers={"Range": "bytes=0-4"}).status_code == 206
    assert client.get(f"/api/audio/jobs/{job_id}/files/worker.log").status_code == 404
    assert client.delete(f"/api/audio/jobs/{job_id}").json()["state"] == "cancelled"
    client.cookies.clear()
    client.headers["X-Lab-Token"] = client.get("/api/session").json()["csrf"]
    assert client.get(f"/api/audio/jobs/{job_id}").status_code == 404
    assert client.get(f"/api/audio/jobs/{job_id}/files/clip_1.m4a").status_code == 404
    assert client.get(f"/api/audio/jobs/{job_id}/files/clip_1.mp4").status_code == 404


@pytest.mark.parametrize("with_checkpoint", [True, False])
def test_native_crash_reports_failed_stage_and_retains_finished_work(audio_client, monkeypatch, with_checkpoint):
    client, lab = audio_client
    fake_process(monkeypatch, [])
    job_id = client.post("/api/audio/jobs", json={"upload_id": upload(client)}).json()["id"]
    job = lab.jobs[job_id]
    job.process.poll = lambda: -1073740791  # Windows native abort, 0xC0000409.
    progress = {"state": "running", "stage": "stt", "requested_mode": "topics", "elapsed_ms": 10000,
        "stages": [{"id": "vad", "label": "음성 구간 감지", "state": "complete"},
                   {"id": "stt", "label": "음성 인식·자막", "state": "running"},
                   {"id": "topics", "label": "주제·하이라이트", "state": "pending"}]}
    (job.directory / "progress.json").write_text(json.dumps(progress), encoding="utf-8")
    if with_checkpoint:
        (job.directory / "checkpoint.json").write_text(json.dumps({"id": job_id,
            "stages": {"vad": {"data": {"spans": [{"startMs": 0, "endMs": 1000}]}}}}), encoding="utf-8")
    data = client.get(f"/api/audio/jobs/{job_id}").json()
    assert data["state"] == "failed" and data["exit_code"] == -1073740791
    assert "음성 인식·자막" in data["error"] and "0xC0000409" in data["error"]
    assert "메모리" not in data["error"]  # A native abort alone does not diagnose OOM.
    assert data["stages"][1]["state"] == "failed" and data["stages"][2]["state"] == "pending"
    partial = data["result"]
    assert partial["partial"] is True and partial["failed_stage"] == "stt"
    assert partial["stage_status"] == data["stages"]
    if with_checkpoint:
        assert partial["stages"]["vad"]["data"]["spans"][0]["endMs"] == 1000
    else:
        assert partial["input"]["name"] == "recording.wav" and partial["clips"] == []
    assert (job.directory / "partial.json").is_file()
    assert client.get("/api/audio/jobs").json()["jobs"][0]["result"]["partial"] is True


@pytest.mark.parametrize("stage", list(STAGES))
def test_model_ids_restricted_to_supported_adapters(stage):
    with pytest.raises(ValueError):
        AudioConfig(pipeline="experiment", **{stage: "https://attacker/model.py"})


def test_individual_function_dependencies_and_original_clock():
    assert AudioConfig(pipeline="experiment", mode="voice").stages() == ["vad", "diarization", "voice"]
    assert AudioConfig(pipeline="experiment", mode="laughter").stages() == ["laughter"]
    assert AudioConfig(pipeline="experiment", mode="topics").stages() == ["vad", "laughter", "stt", "topics"]
    spans = [{"startMs": 3000, "endMs": 65000}, {"startMs": 90000, "endMs": 95000}]
    chunks = stages.speech_chunks(spans)
    assert chunks == [{"startMs": 3000, "endMs": 31000}, {"startMs": 31000, "endMs": 59000},
                      {"startMs": 59000, "endMs": 65000}, {"startMs": 90000, "endMs": 95000}]


def test_openai_audio_transport_uses_file_and_selected_model_without_leaking_errors():
    requests = []
    def respond(request):
        requests.append(request)
        return httpx.Response(200, json={"text": "example", "usage": {"seconds": 1}})
    with httpx.Client(transport=httpx.MockTransport(respond)) as client:
        result = stages.audio_api(np.zeros(16000, np.float32), "gpt-transcribe", Connection(api_key="sk-test"), client=client)
    assert result["text"] == "example"
    assert requests[0].url.path == "/v1/audio/transcriptions"
    assert b"gpt-transcribe" in requests[0].content and b"RIFF" in requests[0].content
    assert requests[0].headers["authorization"] == "Bearer sk-test"
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(401, json={"error": "sk-secret"}))) as client:
        with pytest.raises(ValueError) as error:
            stages.audio_api(np.zeros(16000, np.float32), "gpt-transcribe", Connection(api_key="sk-test"), client=client)
        assert "sk-secret" not in str(error.value)


def test_gpt_transcription_keeps_vad_chunk_times_and_makes_no_word_times(monkeypatch, tmp_path):
    calls = []
    def transcribe(pcm, model, credentials):
        calls.append(len(pcm))
        return {"text": "안녕하세요", "usage": {"seconds": len(pcm) / 16000}}
    monkeypatch.setattr(stages, "audio_api", transcribe)
    result = stages.transcribe(np.zeros(160000, np.float32), "openai:gpt-transcribe", tmp_path,
                               {"spans": [{"startMs": 5000, "endMs": 8000}]}, Connection(api_key="test"))
    assert calls == [48000]
    assert result["utterances"] == [{"id": 1, "startMs": 5000, "endMs": 8000, "text": "안녕하세요"}]
    assert result["timing"] == "vad-chunks"


def test_worker_exports_real_timeline_and_preserves_partial_failure(monkeypatch, tmp_path):
    monkeypatch.setattr(stages, "decode", lambda *_: np.zeros(160000, np.float32))
    monkeypatch.setattr(stages, "vad", lambda *_: {"spans": [{"startMs": 1000, "endMs": 8000}]})
    monkeypatch.setattr(stages, "laughter", lambda *_: {"windows": [{"startMs": 4000, "endMs": 5000, "laughter": .8, "music": 0}]})
    monkeypatch.setattr(stages, "transcribe", lambda *_: {"utterances": [{"id": 1, "startMs": 1000, "endMs": 8000, "text": "test"}]})
    monkeypatch.setattr(worker, "partition", lambda *_: ([{"start_id": 1, "end_id": 1, "title": "Story", "reason": "reason"}], {}))
    exported = []
    monkeypatch.setattr(worker, "export_clips", lambda source, folder, clips, utterances: exported.extend(clips))
    payload = {"id": "test", "directory": str(tmp_path), "source": str(tmp_path / "audio"),
               "input": {"name": "sample.wav", "sha256": "test"}, "config": AudioConfig(pipeline="experiment", mode="topics").model_dump()}
    worker.run(payload)
    assert exported[0]["startMs"] == 1000
    assert exported[0]["endMs"] == 8000
    assert "qwen-local" in exported[0]["boundaryMethod"]
    assert (tmp_path / "transcript.srt").is_file()
    assert json.loads((tmp_path / "progress.json").read_text(encoding="utf-8"))["state"] == "complete"
    def failure(*_):
        raise ValueError("test failure")
    monkeypatch.setattr(worker, "partition", failure)
    with pytest.raises(ValueError):
        worker.run(payload)
    partial = json.loads((tmp_path / "partial.json").read_text(encoding="utf-8"))
    assert partial["partial"] is True and "stt" in partial["stages"]
    assert partial["failed_stage"] == "topics"
    assert "transcript.srt" in partial["artifacts"]
    assert partial["stage_status"][-1]["state"] == "failed"
    checkpoint = json.loads((tmp_path / "checkpoint.json").read_text(encoding="utf-8"))
    assert set(checkpoint["stages"]) == {"vad", "laughter", "stt"}


@pytest.mark.parametrize("threshold,expected", [(.1, False), (.01, True)])
def test_selected_laughter_threshold_reaches_events_and_character_motion(monkeypatch, tmp_path, threshold, expected):
    from momo.video_lab.adapter import app_analysis, Motion
    pcm = np.full(32000, .02, np.float32)
    monkeypatch.setattr(stages, "decode", lambda *_: pcm)
    monkeypatch.setattr(stages, "laughter", lambda *_: {
        "windows": [{"startMs": 500, "endMs": 1000, "laughter": .015, "music": 0}]})
    worker.run({"id": "sensitivity", "directory": str(tmp_path), "source": str(tmp_path / "audio"),
                "input": {"name": "test.wav", "sha256": "0" * 64},
                "config": AudioConfig(pipeline="experiment", mode="laughter", threshold=threshold).model_dump()})
    result = json.loads((tmp_path / "result.json").read_text(encoding="utf-8"))
    assert result["config"]["threshold"] == threshold
    assert bool(result["stages"]["laughter"]["data"]["events"]) is expected
    # A known speaker lets us check the real event -> animation path independently of diarization quality.
    result["stages"].update({
        "vad": {"model": "silero-v5", "data": {"spans": [{"startMs": 0, "endMs": 2000}]}},
        "diarization": {"model": "nemotron", "data": {"segments": [
            {"startMs": 0, "endMs": 2000, "speaker": "S1"}]}}})
    analysis, tracks, _ = app_analysis(result, pcm)
    assert Motion(analysis, tracks, {"S1": "P1"}).at("P1", 750000)["laughing"] is expected
