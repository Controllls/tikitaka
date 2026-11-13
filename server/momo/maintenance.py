import time
from sqlalchemy import select, update, delete
from . import jobs
from .config import settings
from .db import SessionLocal, Job, JobArtifact, Outbox, Idempotency, RefreshToken
from .errors import ServiceError
from .storage import storage


def sweep(now=None):
    now = now or time.time()
    with SessionLocal() as db:
        for job in db.scalars(select(Job)).all():
            active_lease = job.lease_token and job.lease_expires_at and job.lease_expires_at > now
            if job.status in {"running", "cancel_requested"} and not active_lease:
                if job.deleted_at or job.status == "cancel_requested":
                    values = jobs.terminal_values("cancelled", now)
                elif job.created_at + settings.queue_seconds < now:
                    values = jobs.terminal_values("failed", now)
                    values["error_json"] = ServiceError("DEADLINE_EXCEEDED", "작업 대기 시간이 만료되었습니다.").payload()
                else:
                    values = dict(status="queued", stage="queued", progress=0, lease_token=None, lease_expires_at=None)
                reclaimed = db.execute(update(Job).where(Job.id == job.id, Job.lease_token == job.lease_token,
                    Job.lease_expires_at <= now, Job.status.in_(["running", "cancel_requested"])).values(**values)).rowcount
                if reclaimed and values["status"] == "queued":
                    db.add(Outbox(job_id=job.id))
                db.commit()
                db.refresh(job)
                if not reclaimed:
                    continue
            if job.status == "queued" and job.created_at + settings.queue_seconds < now:
                values = jobs.terminal_values("failed", now)
                values["error_json"] = ServiceError("DEADLINE_EXCEEDED", "대기 시간이 만료되었습니다.").payload()
                db.execute(update(Job).where(Job.id == job.id, Job.status == "queued").values(**values))
                db.commit()
            if not active_lease:
                if job.deleted_at or (job.source_expires_at and job.source_expires_at <= now):
                    storage.delete(job.source_key)
                if job.deleted_at or (job.result_expires_at and job.result_expires_at <= now):
                    for artifact in db.scalars(select(JobArtifact).where(JobArtifact.job_id == job.id)):
                        storage.delete(artifact.storage_key)
                    storage.delete(job.result_key)
                    storage.delete(job.tracks_key)
            # A queued job skipped due to the per-owner running limit gets another delivery.
            if job.status == "queued" and not job.deleted_at:
                pending = db.scalar(select(Outbox.id).where(Outbox.job_id == job.id, Outbox.published_at.is_(None)))
                if not pending:
                    db.add(Outbox(job_id=job.id))
        db.execute(delete(Idempotency).where(Idempotency.expires_at < now))
        db.execute(delete(RefreshToken).where(RefreshToken.expires_at < now))
        db.commit()
        # Crashes can leave an uploaded source before DB commit, or an old lease's partial output.
        # One hour exceeds the 10-minute upload reservation and normal supervisor deadline.
        known = set()
        known.update(db.scalars(select(JobArtifact.storage_key)).all())
        active_prefixes = []
        for job in db.scalars(select(Job)).all():
            known.update(key for key in (job.source_key, job.result_key, job.tracks_key) if key)
            if job.lease_token and job.lease_expires_at and job.lease_expires_at > now:
                token = job.lease_token
                known.update((f"results/{job.id}/{token}.json", f"tracks/{job.id}/{token}.json"))
                active_prefixes.append(f"_work/{job.id}/{token}/")
                active_prefixes.append(f"episodes/{job.id}/{token}/")
        for key, modified in storage.entries():
            if modified >= now-3600 or key in known or any(key.startswith(p) for p in active_prefixes):
                continue
            storage.delete(key)


if __name__ == "__main__":
    while True:
        sweep()
        time.sleep(60)
