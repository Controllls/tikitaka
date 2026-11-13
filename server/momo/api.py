import base64
import gzip
import hashlib
import json
import os
import tempfile
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import Depends, FastAPI, File, Form, Header, Query, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import select, text, update
from . import auth, jobs
from .config import settings
from .contracts import AnalysisOptions, AnalysisResult, MotionTracks, JobStatus
from .db import Base, engine, get_db, Job, JobArtifact, Idempotency, uid
from .highlights.contracts import HighlightOptions, HighlightResult
from .errors import ServiceError
from .storage import storage


@asynccontextmanager
async def lifespan(app):
    settings.validate_runtime()
    if settings.profile != "production":
        Base.metadata.create_all(engine)
    yield


app = FastAPI(title="MOMO Analysis API", version="1.0.0", lifespan=lifespan)
from .phone import install as install_phone
install_phone(app)


class BodyLimit:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        total = 0
        started = False
        metadata_only = scope.get("path") == "/v1/episodes/metadata"
        maximum = 4_000_000 if metadata_only else 155_000_000 if scope.get("path") == "/v1/phone/episodes" else settings.episode_max_audio_bytes if scope.get("path") == "/v1/episodes" else settings.max_audio_bytes
        if metadata_only:
            # Buffer only this small JSON route before FastAPI consumes it: exceptions
            # from receive() inside JSON parsing would otherwise become a generic 400.
            parts = []
            while True:
                message = await receive()
                if message["type"] == "http.disconnect":
                    return
                part = message.get("body", b"")
                total += len(part)
                if total > maximum:
                    error = ServiceError("METADATA_TOO_LARGE", "전사문·감지 결과 용량을 초과했습니다.", 413)
                    return await JSONResponse({"error": error.payload(str(uuid.uuid4()))}, status_code=413)(scope, receive, send)
                parts.append(part)
                if not message.get("more_body", False):
                    break
            original_receive, buffered = receive, b"".join(parts)
            async def metadata_receive():
                nonlocal buffered
                if buffered is not None:
                    body, buffered = buffered, None
                    return {"type": "http.request", "body": body, "more_body": False}
                return await original_receive()
            return await self.app(scope, metadata_receive, send)

        async def limited_receive():
            nonlocal total
            message = await receive()
            total += len(message.get("body", b""))
            if total > maximum + (0 if metadata_only else 5_000_000):
                raise ServiceError("AUDIO_TOO_LARGE", "업로드 크기를 초과했습니다.", 413)
            return message

        async def track_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, track_send)
        except ServiceError as exc:
            if started:
                raise
            await JSONResponse({"error": exc.payload(str(uuid.uuid4()))}, status_code=exc.status)(scope, receive, send)


app.add_middleware(BodyLimit)


@app.middleware("http")
async def request_id(request, call_next):
    request.state.request_id = str(uuid.uuid4())
    response = await call_next(request)
    response.headers["X-Request-ID"] = request.state.request_id
    response.headers["Cache-Control"] = "no-store"
    return response


@app.exception_handler(ServiceError)
async def service_error(request, exc):
    return JSONResponse({"error": exc.payload(getattr(request.state, "request_id", ""))}, exc.status,
                        headers={"Retry-After": str(exc.retry_after)} if exc.retry_after else {})


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    # Never echo login passwords, refresh tokens or form contents from validation errors.
    return JSONResponse({"error": dict(code="INVALID_REQUEST", message="요청 형식을 확인해 주세요.",
                        retryable=False, details={}, request_id=request.state.request_id)}, 422)


class Login(BaseModel):
    username: str = Field(min_length=1, max_length=128)
    password: str = Field(min_length=1, max_length=1024)


class Refresh(BaseModel):
    refresh_token: str = Field(min_length=1, max_length=256)


class InstallationCredentials(BaseModel):
    installation_id: uuid.UUID
    # 32 random bytes encoded as lowercase hex, generated and persisted by the client.
    installation_secret: str = Field(pattern=r"^[a-f0-9]{64}$")


class Tokens(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str
    expires_in: int


_login_attempts = {}


def limit_login(request, username):
    key = hashlib.sha256(f"{request.client.host}:{username}".encode()).hexdigest()
    if settings.profile == "production":
        import redis
        client = redis.Redis.from_url(settings.broker_url)
        script = "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n"
        try:
            count = client.eval(script, 1, f"login:{key}")
        except redis.RedisError:
            raise ServiceError("SERVICE_UNAVAILABLE", "인증 서비스를 잠시 이용할 수 없습니다.", 503, True)
    else:
        now = time.time()
        for old in [k for k, (_, until) in _login_attempts.items() if until < now]:
            del _login_attempts[old]
        count, until = _login_attempts.get(key, (0, now + 60))
        count += 1
        _login_attempts[key] = (count, until)
    if count > 10:
        raise ServiceError("RATE_LIMITED", "인증 요청이 많습니다. 잠시 후 다시 시도해 주세요.", 429, True, retry_after=60)


@app.post("/v1/auth/installation", response_model=Tokens)
def installation(body: InstallationCredentials, request: Request, db=Depends(get_db)):
    # All installation IDs share the per-IP registration limit; random IDs cannot bypass it.
    limit_login(request, "installation-registration")
    return auth.authenticate_installation(db, str(body.installation_id), body.installation_secret)


@app.post("/v1/auth/login", response_model=Tokens)
def login(body: Login, request: Request, db=Depends(get_db)):
    limit_login(request, body.username)
    return auth.login(db, body.username, body.password)


@app.post("/v1/auth/refresh", response_model=Tokens)
def refresh(body: Refresh, db=Depends(get_db)):
    return auth.rotate(db, body.refresh_token)


@app.post("/v1/auth/logout", status_code=204)
def logout(body: Refresh, db=Depends(get_db)):
    auth.logout(db, body.refresh_token)
    return Response(status_code=204)


@app.post("/v1/analyses", status_code=202, response_model=JobStatus)
@app.post("/v1/highlights", status_code=202, response_model=JobStatus)
def submit(response: Response, request: Request, audio: UploadFile = File(), sha256: str = Form(), options: str = Form(),
           idempotency_key: str = Header(), owner=Depends(auth.current_user), db=Depends(get_db)):
    highlight = request.url.path.endswith("/highlights")
    collection = "highlights" if highlight else "analyses"
    try:
        config = (HighlightOptions if highlight else AnalysisOptions).model_validate_json(options)
    except ValidationError:
        raise ServiceError("UNSUPPORTED_PROFILE", "언어·분석 옵션·동의 버전을 확인해 주세요.")
    import re
    if not re.fullmatch("[a-f0-9]{64}", sha256):
        raise ServiceError("HASH_MISMATCH", "SHA-256 형식이 올바르지 않습니다.")
    signature = jobs.fingerprint(sha256, config.model_dump())
    previous, reserved = jobs.reserve(db, owner, idempotency_key, signature)
    if previous:
        response.headers["Location"] = f"/v1/{collection}/{previous.id}"
        return jobs.status(previous)
    source_key = f"sources/{uid()}.audio"
    temp = None
    try:
        lock = jobs.ensure_ready(db)
        if highlight:
            from .highlights.models import ensure_models
            ensure_models()
        incoming = storage.root / "_uploads"
        incoming.mkdir(exist_ok=True)
        fd, temp = tempfile.mkstemp(prefix="momo-upload-", dir=incoming)
        total = 0
        digest = hashlib.sha256()
        with os.fdopen(fd, "wb") as output:
            while chunk := audio.file.read(1024 * 1024):
                total += len(chunk)
                if total > settings.max_audio_bytes:
                    raise ServiceError("AUDIO_TOO_LARGE", "80MB 이하 파일을 선택해 주세요.", 413)
                digest.update(chunk)
                output.write(chunk)
        if not total:
            raise ServiceError("INVALID_AUDIO", "비어 있는 파일입니다.")
        if digest.hexdigest() != sha256:
            raise ServiceError("HASH_MISMATCH", "원본 파일 해시가 일치하지 않습니다.")
        storage.put_file(source_key, temp)
        job = jobs.create_job(db, owner, idempotency_key, sha256, config.model_dump(), source_key, lock)
    except Exception:
        db.rollback()
        storage.delete(source_key)
        db.execute(update(Idempotency).where(Idempotency.owner_id == owner, Idempotency.key == idempotency_key,
                   Idempotency.job_id.is_(None)).values(reservation_expires_at=0))
        db.commit()
        raise
    finally:
        if temp:
            Path(temp).unlink(missing_ok=True)
        audio.file.close()
    response.headers["Location"] = f"/v1/{collection}/{job.id}"
    return jobs.status(job)


@app.get("/v1/analyses")
@app.get("/v1/highlights")
def list_jobs(request: Request, cursor: str | None = None, limit: int = Query(20, ge=1, le=100),
              owner=Depends(auth.current_user), db=Depends(get_db)):
    query = select(Job).where(Job.owner_id == owner, Job.deleted_at.is_(None))
    task = Job.options["task"].as_string()
    query = query.where(task == "highlights" if request.url.path.endswith("/highlights") else
                        (task.is_(None) | task.not_in(["highlights", "episode_analysis", "episode_render"])))
    if cursor:
        try:
            created, job_id = json.loads(base64.urlsafe_b64decode(cursor.encode()))
            query = query.where((Job.created_at < float(created)) | ((Job.created_at == float(created)) & (Job.id < job_id)))
        except (ValueError, TypeError):
            raise ServiceError("INVALID_CURSOR", "페이지 위치가 올바르지 않습니다.")
    rows = db.scalars(query.order_by(Job.created_at.desc(), Job.id.desc()).limit(limit + 1)).all()
    next_cursor = None
    if len(rows) > limit:
        last = rows[limit - 1]
        next_cursor = base64.urlsafe_b64encode(json.dumps([last.created_at, last.id]).encode()).decode()
    return {"items": [jobs.status(j) for j in rows[:limit]], "next_cursor": next_cursor}


@app.get("/v1/analyses/{job_id}", response_model=JobStatus)
def get_job(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
    return jobs.status(jobs.owned(db, job_id, owner))


def result_file(db, job_id, owner, kind):
    job = jobs.owned(db, job_id, owner)
    if job.result_expires_at and job.result_expires_at <= time.time():
        raise ServiceError("RESULT_EXPIRED", "서버 결과의 보관기간이 만료되었습니다.", 410)
    if job.status != "completed":
        raise ServiceError("JOB_NOT_READY", "분석이 아직 완료되지 않았습니다.", 409, True)
    key = job.result_key if kind == "result" else job.tracks_key
    if not storage.exists(key):
        raise ServiceError("RESULT_EXPIRED", "서버 결과가 만료되었습니다.", 410)
    return storage.get_file(key)


from .episodes.metadata import install as install_metadata
install_metadata(app)
from .episodes.api import install as install_episodes
install_episodes(app, result_file)


@app.get("/v1/analyses/{job_id}/result", response_model=AnalysisResult)
def result(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
    if jobs.owned(db, job_id, owner).options.get("task") in {"highlights", "episode_analysis", "episode_render"}:
        raise ServiceError("NOT_FOUND", "영상 분석 작업이 아닙니다.", 404)
    return AnalysisResult.model_validate_json(result_file(db, job_id, owner, "result").read_bytes())


def owned_highlight(db, job_id, owner):
    job = jobs.owned(db, job_id, owner)
    if job.options.get("task") != "highlights":
        raise ServiceError("NOT_FOUND", "하이라이트 작업을 찾을 수 없습니다.", 404)
    return job


@app.get("/v1/highlights/{job_id}", response_model=JobStatus)
def highlight_status(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
    return jobs.status(owned_highlight(db, job_id, owner))


@app.get("/v1/highlights/{job_id}/result", response_model=HighlightResult)
def highlight_result(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
    owned_highlight(db, job_id, owner)
    return HighlightResult.model_validate_json(result_file(db, job_id, owner, "result").read_bytes())


@app.get("/v1/highlights/{job_id}/files/{name}")
def highlight_file(job_id: str, name: str, owner=Depends(auth.current_user), db=Depends(get_db)):
    owned_highlight(db, job_id, owner)
    result_file(db, job_id, owner, "result")  # Enforce completion and retention before all downloads.
    artifact = db.get(JobArtifact, (job_id, name))
    if not artifact or not storage.exists(artifact.storage_key):
        raise ServiceError("NOT_FOUND", "하이라이트 파일을 찾을 수 없습니다.", 404)
    mime = "audio/mp4" if name.endswith(".m4a") else "application/json" if name.endswith(".json") else "application/x-subrip"
    return FileResponse(storage.get_file(artifact.storage_key), media_type=mime, filename=name,
                        headers={"ETag": f'"{artifact.sha256}"'})


@app.get("/v1/analyses/{job_id}/tracks", response_model=MotionTracks)
def tracks(job_id: str, request: Request, owner=Depends(auth.current_user), db=Depends(get_db)):
    data = result_file(db, job_id, owner, "tracks").read_bytes()
    etag = '"' + hashlib.sha256(data).hexdigest() + '"'
    # Ownership/expiry are checked before considering a client cache hit.
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304, headers={"ETag": etag})
    headers = {"ETag": etag, "Vary": "Accept-Encoding"}
    if "gzip" in request.headers.get("accept-encoding", ""):
        data = gzip.compress(data)
        headers["Content-Encoding"] = "gzip"
    return Response(data, media_type="application/json", headers=headers)


@app.post("/v1/analyses/{job_id}/cancel", response_model=JobStatus)
def cancel(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
    return jobs.status(jobs.cancel(db, jobs.owned(db, job_id, owner)))


@app.post("/v1/analyses/{job_id}/retry", status_code=202, response_model=JobStatus)
def retry(job_id: str, response: Response, idempotency_key: str = Header(),
          owner=Depends(auth.current_user), db=Depends(get_db)):
    original = jobs.owned(db, job_id, owner)
    signature = jobs.fingerprint(original.audio_sha256, {"parent": job_id, "options": original.options})
    previous, _ = jobs.reserve(db, owner, idempotency_key, signature)
    if previous:
        response.headers["Location"] = f"/v1/analyses/{previous.id}"
        return jobs.status(previous)
    new_key = f"sources/{uid()}.audio"
    try:
        if original.status not in {"failed", "cancelled"}:
            raise ServiceError("JOB_NOT_READY", "실패 또는 취소된 작업만 재시도할 수 있습니다.", 409)
        if original.source_expires_at <= time.time() or not storage.exists(original.source_key):
            raise ServiceError("AUDIO_EXPIRED", "원본 보관기간이 만료되었습니다. 다시 업로드해 주세요.", 410)
        if jobs.ensure_ready(db) != original.model_lock_id:
            raise ServiceError("MODEL_NOT_READY", "이 작업에 사용한 모델 버전이 없습니다.", 503, True)
        storage.copy(original.source_key, new_key)
        job = jobs.create_job(db, owner, idempotency_key, original.audio_sha256, original.options,
                              new_key, original.model_lock_id, job_id)
    except Exception:
        db.rollback()
        storage.delete(new_key)
        db.execute(update(Idempotency).where(Idempotency.owner_id == owner, Idempotency.key == idempotency_key,
                   Idempotency.job_id.is_(None)).values(reservation_expires_at=0))
        db.commit()
        raise
    response.headers["Location"] = f"/v1/analyses/{job.id}"
    return jobs.status(job)


@app.delete("/v1/analyses/{job_id}", status_code=202)
def delete(job_id: str, owner=Depends(auth.current_user), db=Depends(get_db)):
    job = jobs.owned(db, job_id, owner, include_deleted=True)
    if not job.deleted_at:
        db.execute(update(Job).where(Job.id == job_id).values(deleted_at=time.time()))
        db.commit()
        jobs.cancel(db, job)
    return {"job_id": job_id, "deletion_requested": True}


@app.get("/health/live")
def live():
    return {"status": "ok"}


@app.get("/health/ready")
def ready(db=Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
        if not settings.local_dispatch:
            import redis
            redis.Redis.from_url(settings.broker_url, socket_connect_timeout=2).ping()
        storage.health()
        jobs.ensure_ready(db)
    except Exception:
        return JSONResponse({"status": "not_ready"}, 503)
    return {"status": "ready", "data_origin": "fixture" if settings.profile == "fixture" else "inference"}
