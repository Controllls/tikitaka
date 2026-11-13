import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time

from fastapi import Depends, File, Form, Header, Response, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy import update

from .. import auth, jobs
from ..config import settings
from ..contracts import JobStatus
from ..db import get_db, Idempotency, JobArtifact, uid
from ..errors import ServiceError
from ..storage import storage
from .contracts import EpisodeOptions, RenderOptions, profile


def owned(db, job_id, owner):
    job = jobs.owned(db, job_id, owner)
    if job.options.get("task") not in {"episode_analysis", "episode_render"}:
        raise ServiceError("NOT_FOUND", "에피소드 작업을 찾을 수 없습니다.", 404)
    return job


def release(db, owner, key):
    db.rollback()
    db.execute(update(Idempotency).where(Idempotency.owner_id == owner, Idempotency.key == key,
        Idempotency.job_id.is_(None)).values(reservation_expires_at=0))
    db.commit()


def install(app, result_file):
    @app.get("/v1/episodes/profile")
    def current_profile(owner=Depends(auth.current_user)):
        config, revision = profile()
        return {"version": "web-episodes-v1", "config": config, "revision": revision,
                "max_audio_bytes": settings.episode_max_audio_bytes, "max_seconds": settings.lab_max_seconds,
                "renderer": "web-character-renderer", "captions": "sidecar", "voice_matching": "on-device"}

    @app.post("/v1/episodes", status_code=202, response_model=JobStatus)
    def submit(response: Response, audio: UploadFile = File(), sha256: str = Form(), options: str = Form(),
               idempotency_key: str = Header(), owner=Depends(auth.current_user), db=Depends(get_db)):
        try:
            config = EpisodeOptions.model_validate_json(options)
        except ValueError:
            raise ServiceError("UNSUPPORTED_PROFILE", "녹음 전송 동의를 확인해 주세요.")
        if not re.fullmatch(r"[a-f0-9]{64}", sha256):
            raise ServiceError("HASH_MISMATCH", "원본 해시를 확인해 주세요.")
        signature = jobs.fingerprint(sha256, {"task": "episode_analysis", **config.model_dump()})
        previous, _ = jobs.reserve(db, owner, idempotency_key, signature)
        if previous:
            audio.file.close()
            response.headers["Location"] = f"/v1/episodes/{previous.id}"
            return jobs.status(previous)
        source_key, temporary = f"sources/{uid()}.audio", None
        try:
            lock = jobs.ensure_ready(db)
            selected, revision = profile()
            incoming = (settings.episode_work_root or storage.root) / "_uploads"
            incoming.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix="episode-", dir=incoming)
            total, digest = 0, hashlib.sha256()
            with os.fdopen(fd, "wb") as output:
                while chunk := audio.file.read(1024 * 1024):
                    total += len(chunk)
                    if total > settings.episode_max_audio_bytes:
                        raise ServiceError("AUDIO_TOO_LARGE", "에피소드 원본의 업로드 용량 한도를 초과했습니다.", 413)
                    digest.update(chunk)
                    output.write(chunk)
            if not total or digest.hexdigest() != sha256:
                raise ServiceError("HASH_MISMATCH", "원본 파일이 비어 있거나 해시가 일치하지 않습니다.")
            storage.put_file(source_key, temporary)
            saved = {**config.model_dump(), "task": "episode_analysis", "audio_config": selected, "profile_revision": revision}
            job = jobs.create_job(db, owner, idempotency_key, sha256, saved, source_key, lock)
        except Exception:
            storage.delete(source_key)
            release(db, owner, idempotency_key)
            raise
        finally:
            audio.file.close()
            if temporary:
                Path(temporary).unlink(missing_ok=True)
        response.headers["Location"] = f"/v1/episodes/{job.id}"
        return jobs.status(job)

    @app.get("/v1/episodes/{job_id}", response_model=JobStatus)
    def status(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
        return jobs.status(owned(db, job_id, owner))

    @app.get("/v1/episodes/{job_id}/result")
    def result(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
        owned(db, job_id, owner)
        return json.loads(result_file(db, job_id, owner, "result").read_bytes())

    @app.get("/v1/episodes/{job_id}/files/{name}")
    def artifact(job_id: str, name: str, owner=Depends(auth.current_user), db=Depends(get_db)):
        owned(db, job_id, owner)
        result_file(db, job_id, owner, "result")
        if not re.fullmatch(r"(?:video_clip_[0-9]+\.(?:mp4|png)|captions_[0-9]+\.json|clip_[0-9]+\.(?:m4a|srt))", name):
            raise ServiceError("NOT_FOUND", "파일을 찾을 수 없습니다.", 404)
        item = db.get(JobArtifact, (job_id, name))
        if item is None or not storage.exists(item.storage_key):
            raise ServiceError("RESULT_EXPIRED", "완성 파일을 다시 받아야 합니다.", 410)
        mime = "video/mp4" if name.endswith(".mp4") else "image/png" if name.endswith(".png") else "application/json" if name.endswith(".json") else "audio/mp4" if name.endswith(".m4a") else "application/x-subrip"
        return FileResponse(storage.get_file(item.storage_key), media_type=mime, filename=name,
                            headers={"ETag": f'"{item.sha256}"'})

    @app.post("/v1/episodes/{job_id}/render", status_code=202, response_model=JobStatus)
    def render(job_id: str, body: RenderOptions, response: Response, idempotency_key: str = Header(),
               owner=Depends(auth.current_user), db=Depends(get_db)):
        parent = owned(db, job_id, owner)
        if parent.options["task"] != "episode_analysis":
            raise ServiceError("NOT_FOUND", "에피소드 분석 작업을 찾을 수 없습니다.", 404)
        signature = jobs.fingerprint(parent.audio_sha256, {"parent": job_id, **body.model_dump()})
        previous, _ = jobs.reserve(db, owner, idempotency_key, signature)
        if previous:
            response.headers["Location"] = f"/v1/episodes/{previous.id}"
            return jobs.status(previous)
        source_key = f"sources/{uid()}.audio"
        try:
            data = json.loads(result_file(db, job_id, owner, "result").read_bytes())
            if not data["clips"] or not {p.speaker for p in body.people} <= {p["speaker_id"] for p in data["speakers"]}:
                raise ServiceError("INVALID_REQUEST", "분석한 화자와 하이라이트를 확인해 주세요.")
            if parent.source_expires_at <= time.time() or not storage.exists(parent.source_key):
                raise ServiceError("AUDIO_EXPIRED", "서버의 원본 보관기간이 만료되었습니다.", 410)
            lock = jobs.ensure_ready(db)
            storage.copy(parent.source_key, source_key)
            saved = {**parent.options, "task": "episode_render", "analysis_job_id": parent.id, "cast": body.model_dump()}
            job = jobs.create_job(db, owner, idempotency_key, parent.audio_sha256, saved, source_key, lock, parent.id)
        except Exception:
            storage.delete(source_key)
            release(db, owner, idempotency_key)
            raise
        response.headers["Location"] = f"/v1/episodes/{job.id}"
        return jobs.status(job)
