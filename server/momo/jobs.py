import hashlib
import json
import time
from datetime import datetime, timezone
from sqlalchemy import select, update, func, case, or_
from sqlalchemy.exc import IntegrityError
from .config import settings
from .contracts import JobStatus
from .db import Job, JobArtifact, Idempotency, Outbox, User, WorkerRegistration, uid
from .errors import ServiceError, Cancelled

TERMINAL = {"completed", "failed", "cancelled"}


def iso(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat() if value else None


def status(job):
    return JobStatus(job_id=job.id, parent_job_id=job.parent_job_id, status=job.status,
                     stage=job.stage, progress=job.progress, created_at=iso(job.created_at),
                     updated_at=iso(job.updated_at), source_expires_at=iso(job.source_expires_at),
                     result_expires_at=iso(job.result_expires_at), error=job.error_json if job.status == "failed" else None)


def owned(db, job_id, owner, include_deleted=False):
    query = select(Job).where(Job.id == job_id, Job.owner_id == owner)
    if not include_deleted:
        query = query.where(Job.deleted_at.is_(None))
    job = db.scalar(query)
    if not job:
        raise ServiceError("NOT_FOUND", "작업을 찾을 수 없습니다.", 404)
    return job


def fingerprint(audio_hash, options):
    return hashlib.sha256((audio_hash + json.dumps(options, sort_keys=True, separators=(",", ":"))).encode()).hexdigest()


def reserve(db, owner, key, signature, now=None):
    now = now or time.time()
    if not key or len(key) > 128:
        raise ServiceError("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key가 필요합니다.")
    item = db.get(Idempotency, (owner, key))
    if item:
        if item.request_fingerprint != signature:
            raise ServiceError("IDEMPOTENCY_CONFLICT", "같은 키로 다른 요청을 보낼 수 없습니다.", 409)
        if item.job_id:
            return owned(db, item.job_id, owner), False
        if item.reservation_expires_at > now:
            raise ServiceError("IDEMPOTENCY_IN_PROGRESS", "동일한 업로드를 처리하고 있습니다.", 409, True, retry_after=2)
        claimed = db.execute(update(Idempotency).where(Idempotency.owner_id == owner, Idempotency.key == key,
                             Idempotency.reservation_expires_at <= now, Idempotency.job_id.is_(None))
                             .values(reservation_expires_at=now + 600)).rowcount
        db.commit()
        if not claimed:
            raise ServiceError("IDEMPOTENCY_IN_PROGRESS", "업로드 예약 중입니다.", 409, True, retry_after=2)
        return None, True
    db.add(Idempotency(owner_id=owner, key=key, request_fingerprint=signature,
                       reservation_expires_at=now + 600, expires_at=now + settings.result_ttl_seconds + 86400))
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        return reserve(db, owner, key, signature, now)
    return None, True


def model_lock_id():
    if settings.profile == "fixture":
        return "fixture-v1"
    path = settings.model_root / "model-lock.json"
    if not path.is_file():
        raise ServiceError("MODEL_NOT_READY", "서버 모델을 준비해 주세요.", 503, True)
    from .prepare_voice_model import MODEL_ID
    return hashlib.sha256(path.read_bytes() + MODEL_ID.encode()).hexdigest()


def ensure_ready(db):
    lock = model_lock_id()
    # API trusts a fresh registration created only after the worker loaded and verified all engines.
    if settings.profile != "fixture" and not db.scalar(select(WorkerRegistration.id).where(
        WorkerRegistration.ready.is_(True), WorkerRegistration.heartbeat_at > time.time() - 90,
        WorkerRegistration.model_lock_id == lock
    )):
        raise ServiceError("MODEL_NOT_READY", "분석 worker가 아직 준비되지 않았습니다.", 503, True)
    return lock


def create_job(db, owner, key, audio_hash, options, source_key, lock, parent=None):
    # Serialize owner quotas in PostgreSQL. SQLite is development/test only.
    db.scalar(select(User).where(User.id == owner).with_for_update())
    queued = db.scalar(select(func.count()).select_from(Job).where(Job.owner_id == owner,
                       Job.status == "queued", Job.deleted_at.is_(None)))
    if queued >= 3:
        raise ServiceError("RATE_LIMITED", "대기 중인 작업은 최대 3개입니다.", 429, True, retry_after=10)
    job = Job(id=uid(), owner_id=owner, parent_job_id=parent, options=options, audio_sha256=audio_hash,
              source_key=source_key, model_lock_id=lock,
              pipeline_version="web-episodes-v1" if options.get("task", "").startswith("episode_") else
              "momo-highlights-v1" if options.get("task") == "highlights" else "momo-server-v1")
    db.add(job)
    db.flush()
    reservation = db.get(Idempotency, (owner, key))
    reservation.job_id = job.id
    db.add(Outbox(job_id=job.id))
    db.commit()
    return job


def terminal_values(state, now):
    return dict(status=state, updated_at=now, terminal_at=now, lease_token=None, lease_expires_at=None,
                source_expires_at=now + settings.source_ttl_seconds,
                result_expires_at=now + settings.result_ttl_seconds)


def cancel(db, job, now=None):
    now = now or time.time()
    # Atomic conditional writes make cancellation and result publication ordered by the DB.
    values = terminal_values("cancelled", now)
    values["cancel_requested_at"] = now
    db.execute(update(Job).where(Job.id == job.id, Job.status == "queued").values(**values))
    db.execute(update(Job).where(Job.id == job.id, Job.status == "running").values(
        status="cancel_requested", cancel_requested_at=now, updated_at=now))
    db.commit()
    db.refresh(job)
    return job


def claim(db, job_id, now=None):
    now = now or time.time()
    job = db.get(Job, job_id)
    if not job or job.deleted_at or job.status != "queued":
        return None
    db.scalar(select(User).where(User.id == job.owner_id).with_for_update())
    running = db.scalar(select(func.count()).select_from(Job).where(Job.owner_id == job.owner_id,
                       Job.status.in_(["running", "cancel_requested"])))
    if running:
        db.rollback()
        return None
    token = uid()
    changed = db.execute(update(Job).where(Job.id == job_id, Job.status == "queued", Job.deleted_at.is_(None))
                         .values(status="running", lease_token=token, lease_expires_at=now + settings.lease_seconds,
                                 attempt_no=Job.attempt_no + 1, updated_at=now)).rowcount
    db.commit()
    return token if changed else None


def check_lease(db, job_id, token, now=None):
    now = now or time.time()
    db.expire_all()
    job = db.get(Job, job_id)
    if not job or job.deleted_at or job.status == "cancel_requested" or job.lease_token != token:
        raise Cancelled()
    if not job.lease_expires_at or job.lease_expires_at <= now:
        raise Cancelled()
    return job


def progress(db, job_id, token, stage, value):
    check_lease(db, job_id, token)
    db.execute(update(Job).where(Job.id == job_id, Job.lease_token == token, Job.status == "running",
               Job.deleted_at.is_(None)).values(stage=stage, progress=min(.99, value), updated_at=time.time()))
    db.commit()


def publish(db, job_id, token, result_key, tracks_key, duration_us, artifacts=()):
    now = time.time()
    values = terminal_values("completed", now)
    values.update(stage="done", progress=1, result_key=result_key, tracks_key=tracks_key, duration_us=duration_us)
    changed = db.execute(update(Job).where(Job.id == job_id, Job.lease_token == token,
               Job.lease_expires_at > now, Job.status == "running", Job.deleted_at.is_(None)).values(**values)).rowcount
    if changed:
        db.add_all(JobArtifact(job_id=job_id, **artifact) for artifact in artifacts)
    db.commit()
    return bool(changed)


def finish_error(db, job_id, token, error=None):
    now = time.time()
    job = db.get(Job, job_id)
    if not job or job.lease_token != token:
        return
    state = "cancelled" if job.status == "cancel_requested" or job.deleted_at or error is None else "failed"
    values = terminal_values(state, now)
    if error:
        interrupted = or_(Job.status == "cancel_requested", Job.deleted_at.is_not(None))
        values["status"] = case((interrupted, "cancelled"), else_="failed")
        values["error_json"] = error.payload()
    else:
        values["error_json"] = None
    db.execute(update(Job).where(Job.id == job_id, Job.lease_token == token).values(**values))
    db.commit()
