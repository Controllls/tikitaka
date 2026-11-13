import hashlib
import json
import time
import pytest
from sqlalchemy import select
from momo.db import SessionLocal, Job, JobArtifact
from momo.highlights.contracts import HighlightResult
from momo.highlights.selection import group_events, utterances_from_segments
from momo.highlights.topics import select_topics
from momo.maintenance import sweep
from momo.storage import storage
from momo.worker import run_job
from test_api import wav


def submit(client, headers, key="highlights", duration=3):
    audio = wav(duration)
    return client.post("/v1/highlights", headers={**headers, "Idempotency-Key": key},
        data={"sha256": hashlib.sha256(audio).hexdigest(), "options": json.dumps({"consent_version": "automatic-highlights-v1"})},
        files={"audio": ("recording.wav", audio, "audio/wav")})


def test_highlight_job_is_idempotent_and_stores_verified_audio(client, users):
    first = submit(client, users[0][1])
    assert first.status_code == 202, first.text
    job_id = first.json()["job_id"]
    assert submit(client, users[0][1]).json()["job_id"] == job_id
    assert client.get(f"/v1/highlights/{job_id}/result", headers=users[0][1]).status_code == 409
    assert run_job(job_id, allow_hard_exit=False)
    assert not run_job(job_id, allow_hard_exit=False)
    result = HighlightResult.model_validate(client.get(f"/v1/highlights/{job_id}/result", headers=users[0][1]).json())
    assert result.data_origin == "fixture" and result.threshold == .1
    assert len(result.clips) == 1
    clip = client.get(f"/v1/highlights/{job_id}/files/clip_1.m4a", headers=users[0][1])
    assert clip.status_code == 200 and len(clip.content) == result.clips[0].size
    assert hashlib.sha256(clip.content).hexdigest() == result.clips[0].sha256
    assert client.get("/v1/analyses", headers=users[0][1]).json()["items"] == []
    assert len(client.get("/v1/highlights", headers=users[0][1]).json()["items"]) == 1
    assert client.get(f"/v1/analyses/{job_id}/result", headers=users[0][1]).status_code == 404


@pytest.mark.parametrize("suffix", ["", "/result", "/files/clip_1.m4a", "/files/transcript.json"])
def test_highlight_files_and_status_are_owner_scoped(client, users, suffix):
    job_id = submit(client, users[0][1]).json()["job_id"]
    assert run_job(job_id, allow_hard_exit=False)
    assert client.get(f"/v1/highlights/{job_id}{suffix}", headers=users[1][1]).status_code == 404
    assert client.get(f"/v1/highlights/{job_id}{suffix}").status_code == 401


def test_empty_highlights_are_success_and_not_retried(client, users):
    job_id = submit(client, users[0][1], duration=.2).json()["job_id"]
    assert run_job(job_id, allow_hard_exit=False)
    result = client.get(f"/v1/highlights/{job_id}/result", headers=users[0][1]).json()
    assert result["clips"] == []
    assert client.get(f"/v1/highlights/{job_id}/files/clip_1.m4a", headers=users[0][1]).status_code == 404


def test_highlight_retention_and_delete_remove_all_artifacts(client, users):
    job_id = submit(client, users[0][1]).json()["job_id"]
    assert run_job(job_id, allow_hard_exit=False)
    with SessionLocal() as db:
        paths = [a.storage_key for a in db.scalars(select(JobArtifact).where(JobArtifact.job_id == job_id))]
        assert len(paths) == 3 and all(storage.exists(path) for path in paths)
        db.get(Job, job_id).result_expires_at = time.time()-1
        db.commit()
    assert client.get(f"/v1/highlights/{job_id}/files/clip_1.m4a", headers=users[0][1]).status_code == 410
    sweep()
    assert all(not storage.exists(path) for path in paths)


def test_threshold_and_topic_boundaries_keep_stt_context_without_fixed_padding():
    windows = [dict(startMs=10000, endMs=10975, laughter=.1, music=.01),
               dict(startMs=50000, endMs=50975, laughter=.099, music=.01)]
    events = group_events(windows, .1)
    assert len(events) == 1
    utterances = utterances_from_segments([dict(words=[
        dict(start=1, end=4, word="도입."), dict(start=5, end=10, word="이야기."), dict(start=11, end=15, word="마무리.")])])
    selected = select_topics([dict(start_id=1, end_id=3, title="이야기", eventId=0)], utterances,
                             [{**events[0], "eventId": 0}], 20000, 3)
    assert [(clip["startMs"], clip["endMs"]) for clip in selected] == [(1000, 15000)]
