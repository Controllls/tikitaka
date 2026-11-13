import hashlib
import io
import json
import time
import wave
from concurrent.futures import ThreadPoolExecutor
import pytest
from sqlalchemy import select, func
from momo.config import settings
from momo.db import SessionLocal, Job, Outbox
from momo import jobs
from momo.contracts import AnalysisResult, MotionTracks, validate_pair
from momo.worker import run_job
from momo.maintenance import sweep


def wav(seconds=.2):
    stream = io.BytesIO()
    with wave.open(stream, "wb") as output:
        output.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        output.writeframes(b"\0\0"*round(16000*seconds))
    return stream.getvalue()


def submit(client, headers, key="one", audio=None, sha=None):
    audio = wav(3) if audio is None else audio
    return client.post("/v1/analyses", headers={**headers, "Idempotency-Key": key},
                       data={"sha256": sha or hashlib.sha256(audio).hexdigest(), "options": json.dumps({"consent_version": "server-audio-v1"})},
                       files={"audio": ("../../unsafe.wav", audio, "audio/wav")})


def test_idempotency_and_duplicate_delivery(client, users):
    responses = [submit(client, users[0][1]) for _ in range(3)]
    assert all(r.status_code == 202 for r in responses)
    assert len({r.json()["job_id"] for r in responses}) == 1
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Job)) == 1
        assert db.scalar(select(func.count()).select_from(Outbox)) == 1
    job = responses[0].json()["job_id"]
    assert run_job(job, allow_hard_exit=False)
    assert not run_job(job, allow_hard_exit=False)
    result = AnalysisResult.model_validate(client.get(f"/v1/analyses/{job}/result", headers=users[0][1]).json())
    tracks = MotionTracks.model_validate(client.get(f"/v1/analyses/{job}/tracks", headers=users[0][1]).json())
    validate_pair(result, tracks)
    assert result.data_origin == "fixture"
    assert submit(client, users[0][1], audio=wav(1)).status_code == 409


def test_idempotency_is_owner_scoped(client, users):
    a, b = [submit(client, u[1]).json()["job_id"] for u in users]
    assert a != b


def test_orphan_cleanup_preserves_active_lease_and_referenced_files(client, users):
    import os
    from momo.storage import storage
    now = time.time()
    job_id = submit(client, users[0][1]).json()["job_id"]
    with SessionLocal() as db:
        token = jobs.claim(db, job_id)
        source = db.get(Job, job_id).source_key
    active = f"_work/{job_id}/{token}/audio.f32"
    orphan_keys = ["sources/orphan.audio", "results/dead/old.json", "_uploads/interrupted.audio", "_work/dead/expired/audio.f32"]
    for key in orphan_keys+[active]:
        file = storage.path(key)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"synthetic-test")
        os.utime(file, (now-7200, now-7200))
    sweep(now)
    assert storage.exists(active) and storage.exists(source)
    assert all(not storage.exists(key) for key in orphan_keys)


def test_concurrent_idempotency_reservation(client, users):
    with ThreadPoolExecutor(max_workers=3) as pool:
        responses = list(pool.map(lambda _: submit(client, users[0][1], key="concurrent"), range(3)))
    assert any(r.status_code == 202 for r in responses)
    assert all(r.status_code in {202, 409} for r in responses)
    followups = [submit(client, users[0][1], key="concurrent") for _ in range(3)]
    assert len({r.json()["job_id"] for r in followups}) == 1
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(Job)) == 1
        assert db.scalar(select(func.count()).select_from(Outbox)) == 1


@pytest.mark.parametrize("suffix,method", [("", "get"), ("/result", "get"), ("/tracks", "get"), ("/cancel", "post"), ("/retry", "post"), ("", "delete")])
def test_ownership(client, users, suffix, method):
    job = submit(client, users[0][1]).json()["job_id"]
    headers = {**users[1][1], "Idempotency-Key": "retry", "If-None-Match": '"cached"'}
    assert getattr(client, method)(f"/v1/analyses/{job}{suffix}", headers=headers).status_code == 404


def test_refresh_replay_and_logout(client, users):
    old = users[0][2]
    new = client.post("/v1/auth/refresh", json={"refresh_token": old["refresh_token"]})
    assert new.status_code == 200
    assert client.post("/v1/auth/refresh", json={"refresh_token": old["refresh_token"]}).status_code == 401
    assert client.get("/v1/analyses", headers={"Authorization": "Bearer "+new.json()["access_token"]}).status_code == 401
    other = users[1][2]
    for _ in range(2):
        assert client.post("/v1/auth/logout", json={"refresh_token": other["refresh_token"]}).status_code == 204
    assert client.get("/v1/analyses", headers=users[1][1]).status_code == 401


def test_cancel_and_retry_immutable(client, users):
    job = submit(client, users[0][1]).json()["job_id"]
    cancelled = client.post(f"/v1/analyses/{job}/cancel", headers=users[0][1]).json()
    assert cancelled["status"] == "cancelled"
    assert not run_job(job, allow_hard_exit=False)
    retry = client.post(f"/v1/analyses/{job}/retry", headers={**users[0][1], "Idempotency-Key": "retry"})
    assert retry.status_code == 202
    assert retry.json()["job_id"] != job and retry.json()["parent_job_id"] == job
    assert client.get(f"/v1/analyses/{job}", headers=users[0][1]).json()["status"] == "cancelled"


@pytest.mark.parametrize("first", ["cancel", "delete", "publish"])
def test_publication_races(client, users, first):
    job_id = submit(client, users[0][1]).json()["job_id"]
    with SessionLocal() as db:
        token = jobs.claim(db, job_id)
        if first == "publish":
            assert jobs.publish(db, job_id, token, "r", "t", 3000000)
        if first == "delete":
            assert client.delete(f"/v1/analyses/{job_id}", headers=users[0][1]).status_code == 202
        else:
            response = client.post(f"/v1/analyses/{job_id}/cancel", headers=users[0][1])
            assert response.json()["status"] == ("completed" if first == "publish" else "cancel_requested")
        assert not jobs.publish(db, job_id, token, "r2", "t2", 3000000)


def test_lease_expiry_requeue_and_old_token_rejected(client, users):
    job_id = submit(client, users[0][1]).json()["job_id"]
    with SessionLocal() as db:
        old = jobs.claim(db, job_id, now=time.time()-200)
    sweep()
    with SessionLocal() as db:
        assert db.get(Job, job_id).status == "queued"
        new = jobs.claim(db, job_id)
        assert new and new != old
        assert not jobs.publish(db, job_id, old, "r", "t", 3000000)


def test_expiry_etag_tombstone(client, users):
    job_id = submit(client, users[0][1]).json()["job_id"]
    run_job(job_id, allow_hard_exit=False)
    response = client.get(f"/v1/analyses/{job_id}/tracks", headers=users[0][1])
    assert response.status_code == 200
    assert client.get(f"/v1/analyses/{job_id}/tracks", headers={**users[0][1], "If-None-Match": response.headers["etag"]}).status_code == 304
    with SessionLocal() as db:
        job = db.get(Job, job_id)
        job.result_expires_at = time.time()-1
        db.commit()
    assert client.get(f"/v1/analyses/{job_id}/tracks", headers={**users[0][1], "If-None-Match": response.headers["etag"]}).status_code == 410
    for _ in range(2):
        assert client.delete(f"/v1/analyses/{job_id}", headers=users[0][1]).status_code == 202
    assert client.get(f"/v1/analyses/{job_id}", headers=users[0][1]).status_code == 404


def test_bad_hash_limit_corrupt_silent(client, users, monkeypatch):
    assert submit(client, users[0][1], sha="0"*64).json()["error"]["code"] == "HASH_MISMATCH"
    monkeypatch.setattr(settings, "max_audio_bytes", 64)
    assert submit(client, users[0][1], key="large").status_code == 413
    monkeypatch.setattr(settings, "max_audio_bytes", 80_000_000)
    corrupt = submit(client, users[0][1], key="corrupt", audio=b"not audio").json()["job_id"]
    assert not run_job(corrupt, allow_hard_exit=False)
    assert client.get(f"/v1/analyses/{corrupt}", headers=users[0][1]).json()["error"]["code"] == "INVALID_AUDIO"
    silent = submit(client, users[0][1], key="silent").json()["job_id"]
    assert run_job(silent, allow_hard_exit=False)


def test_retry_source_expired_and_quota(client, users):
    job_id = submit(client, users[0][1]).json()["job_id"]
    client.post(f"/v1/analyses/{job_id}/cancel", headers=users[0][1])
    with SessionLocal() as db:
        db.get(Job, job_id).source_expires_at = time.time()-1
        db.commit()
    assert client.post(f"/v1/analyses/{job_id}/retry", headers={**users[0][1], "Idempotency-Key": "expired"}).status_code == 410
    for i in range(3):
        assert submit(client, users[0][1], key=f"queued{i}").status_code == 202
    assert submit(client, users[0][1], key="fourth").status_code == 429


def test_production_has_no_fixture_fallback(client, users, monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "profile", "production")
    monkeypatch.setattr(settings, "model_root", tmp_path)
    assert submit(client, users[0][1]).status_code == 503
