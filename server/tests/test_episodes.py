"""Durable/authenticated mobile orchestration; AI is stubbed in these contract tests."""
import hashlib
import json
import time

import pytest
from sqlalchemy import select

from momo.config import settings
from momo.db import SessionLocal, Job, JobArtifact
from momo.episodes import pipeline
from momo.errors import ServiceError
from momo.maintenance import sweep
from momo.storage import storage
from momo.worker import run_job
from test_api import wav


CAST = {"people": [{"speaker": "S1", "name": "Test", "character": "01-bear"}]}


def submit(client, headers, key="episode", audio=None):
    audio = audio or wav(1)
    return client.post("/v1/episodes", headers={**headers, "Idempotency-Key": key},
        data={"sha256": hashlib.sha256(audio).hexdigest(), "options": '{"consent_version":"server-audio-v1"}'},
        files={"audio": ("recording.wav", audio, "audio/wav")})


@pytest.fixture
def engine(monkeypatch):
    def execute(job, source, directory, progress, check):
        result = dict(job_id=job.id, audio_sha256=job.audio_sha256, duration_ms=1000, data_origin="fixture")
        name = "lab-result.json" if job.options["task"] == "episode_analysis" else "video_clip_1.mp4"
        file = directory / name
        file.write_bytes(b"fixture artifact, not real inference/video")
        result.update(clips=[{"startMs": 0, "endMs": 1000}], speakers=[{"speaker_id": "S1", "first_seen_us": 0}])
        return result, [(name, file)]
    monkeypatch.setattr(pipeline, "run", execute)


def test_analysis_render_and_files_are_durable_idempotent_owner_scoped(client, users, engine):
    headers, other = users[0][1], users[1][1]
    submitted = submit(client, headers)
    assert submitted.status_code == 202, submitted.text
    parent = submitted.json()["job_id"]
    assert submit(client, headers).json()["job_id"] == parent
    assert client.get(f"/v1/episodes/{parent}/result", headers=headers).status_code == 409
    assert run_job(parent, allow_hard_exit=False)
    assert not run_job(parent, allow_hard_exit=False)
    assert client.get("/v1/analyses", headers=headers).json()["items"] == []
    assert client.get(f"/v1/analyses/{parent}/result", headers=headers).status_code == 404
    assert client.post(f"/v1/episodes/{parent}/render", json=CAST, headers={**other,"Idempotency-Key":"render"}).status_code == 404
    response = client.post(f"/v1/episodes/{parent}/render", json=CAST, headers={**headers,"Idempotency-Key":"render"})
    assert response.status_code == 202, response.text
    render = response.json()["job_id"]
    assert client.post(f"/v1/episodes/{parent}/render", json=CAST, headers={**headers,"Idempotency-Key":"render"}).json()["job_id"] == render
    assert run_job(render, allow_hard_exit=False)
    for suffix in ("", "/result", "/files/video_clip_1.mp4"):
        assert client.get(f"/v1/episodes/{render}{suffix}", headers=other).status_code == 404
        assert client.get(f"/v1/episodes/{render}{suffix}").status_code == 401
    response = client.get(f"/v1/episodes/{render}/files/video_clip_1.mp4", headers=headers)
    assert response.status_code == 200
    assert response.headers["etag"] == '"'+hashlib.sha256(response.content).hexdigest()+'"'
    assert client.get(f"/v1/episodes/{parent}/files/lab-result.json", headers=headers).status_code == 404
    with SessionLocal() as db:
        original, child = db.get(Job, parent), db.get(Job, render)
        assert child.options["audio_config"] == original.options["audio_config"]
        assert child.source_key != original.source_key
        paths = [a.storage_key for a in db.scalars(select(JobArtifact).where(JobArtifact.job_id == render))]
        child.result_expires_at = time.time()-1
        db.commit()
    assert client.get(f"/v1/episodes/{render}/files/video_clip_1.mp4", headers=headers).status_code == 410
    sweep()
    assert all(not storage.exists(path) for path in paths)


def test_profile_is_frozen_and_replay_survives_changed_defaults(client, users, tmp_path, monkeypatch):
    profile = tmp_path / "profile.json"
    monkeypatch.setattr(settings, "episode_config_file", profile)
    profile.write_text('{"threshold":0.2}')
    first = submit(client, users[0][1]).json()["job_id"]
    profile.write_text('{"threshold":0.4}')
    assert submit(client, users[0][1]).json()["job_id"] == first
    second = submit(client, users[0][1], "another").json()["job_id"]
    with SessionLocal() as db:
        assert db.get(Job, first).options["audio_config"]["threshold"] == .2
        assert db.get(Job, second).options["audio_config"]["threshold"] == .4
    assert submit(client, users[0][1], audio=wav(2)).status_code == 409


def test_episode_upload_uses_own_limit_and_checks_hash(client, users, monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "episode_work_root", tmp_path / "scratch")
    monkeypatch.setattr(settings, "episode_max_audio_bytes", 2_000_000)
    monkeypatch.setattr(settings, "max_audio_bytes", 100)
    assert submit(client, users[0][1]).status_code == 202
    assert list((settings.episode_work_root / "_uploads").iterdir()) == []
    monkeypatch.setattr(settings, "episode_max_audio_bytes", 100)
    assert submit(client, users[0][1], "too-big").status_code == 413


def test_invalid_cast_and_expired_original_never_render(client, users, engine):
    headers = {**users[0][1], "Idempotency-Key": "render"}
    parent = submit(client, users[0][1]).json()["job_id"]
    assert run_job(parent, allow_hard_exit=False)
    url = f"/v1/episodes/{parent}/render"
    assert client.post(url, json={"people": CAST["people"]*2}, headers=headers).status_code == 422
    bad = {"people": [{**CAST["people"][0], "speaker": "S2"}]}
    assert client.post(url, json=bad, headers=headers).status_code == 400
    with SessionLocal() as db:
        db.get(Job, parent).source_expires_at = time.time()-1
        db.commit()
    assert client.post(url, json=CAST, headers={**headers,"Idempotency-Key":"expired"}).status_code == 410


def test_failed_episode_retries_with_same_engine_and_model_snapshot(client, users, monkeypatch):
    def fail(*args):
        raise ServiceError("EPISODE_FAILED", "fixture failure")
    monkeypatch.setattr(pipeline, "run", fail)
    parent = submit(client, users[0][1]).json()["job_id"]
    assert not run_job(parent, allow_hard_exit=False)
    headers = {**users[0][1], "Idempotency-Key": "retry"}
    response = client.post(f"/v1/analyses/{parent}/retry", headers=headers)
    assert response.status_code == 202
    child = response.json()["job_id"]
    assert client.post(f"/v1/analyses/{parent}/retry", headers=headers).json()["job_id"] == child
    with SessionLocal() as db:
        assert db.get(Job, child).options == db.get(Job, parent).options
        assert db.get(Job, child).pipeline_version == "web-episodes-v1"


def test_model_profile_rejects_silent_typos_and_voice_upload(client, users, tmp_path, monkeypatch):
    profile = tmp_path / "profile.json"
    profile.write_text('{"laughter_threshold":0.4}')  # Actual field is threshold.
    monkeypatch.setattr(settings, "episode_config_file", profile)
    response = client.get("/v1/episodes/profile", headers=users[0][1])
    assert response.status_code == 503 and response.json()["error"]["code"] == "EPISODE_PROFILE_INVALID"
    from momo.episodes.contracts import RenderOptions
    with pytest.raises(ValueError):
        RenderOptions.model_validate({**CAST, "registered_embedding": [1]*192})


def test_partial_segment_analysis_never_becomes_a_finished_episode(tmp_path):
    from momo.episodes.runner import summary
    with pytest.raises(ValueError, match="분석에 실패한"):
        summary({}, {"characters": {"speakers": [], "failed_segments": 1}}, tmp_path)
