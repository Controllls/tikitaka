"""Solo Celery worker + explicit local dispatcher. API never imports this module."""
import json
import os
import socket
import tempfile
import threading
import time
from pathlib import Path
from celery import Celery
from celery.signals import worker_ready
from sqlalchemy import select, update
from . import jobs
from .config import settings
from .contracts import validate_pair
from .db import Base, engine, SessionLocal, Job, Outbox, WorkerRegistration
from .errors import ServiceError, Cancelled
from .storage import storage

celery = Celery("momo", broker=settings.broker_url)
celery.conf.update(task_acks_late=True, worker_prefetch_multiplier=1,
                   broker_transport_options={"visibility_timeout": max(settings.deadline_seconds, settings.episode_deadline_seconds)+600},
                   task_reject_on_worker_lost=True, task_serializer="json", accept_content=["json"],
                   result_backend=None, broker_connection_retry_on_startup=True)
_pipeline = None
_worker_id = f"{socket.gethostname()}:{os.getpid()}"


def pipeline():
    global _pipeline
    if _pipeline is None:
        from .inference.pipeline import Pipeline
        _pipeline = Pipeline()
        def announce():
            while True:
                try:
                    with SessionLocal() as db:
                        db.merge(WorkerRegistration(id=_worker_id, heartbeat_at=time.time(),
                                 model_lock_id=_pipeline.lock_id, ready=True))
                        db.commit()
                except Exception:
                    pass
                time.sleep(15)
        if settings.profile != "fixture":
            threading.Thread(target=announce, daemon=True).start()
    return _pipeline


def run_job(job_id, allow_hard_exit=True):
    engine_pipeline = pipeline()
    with SessionLocal() as db:
        token = jobs.claim(db, job_id)
        if not token:
            return False
        job = db.get(Job, job_id)
    episode = job.options.get("task", "").startswith("episode_")
    deadline = settings.episode_deadline_seconds if episode else settings.deadline_seconds
    stop = threading.Event()
    started = time.monotonic()

    def heartbeat():
        while not stop.wait(2):
            now = time.time()
            with SessionLocal() as db:
                current = db.get(Job, job_id)
                if not current or current.lease_token != token:
                    if allow_hard_exit:
                        os._exit(70)  # Lost ownership: no remaining model task may publish.
                    return
                expired = time.monotonic()-started > deadline
                interrupted = (current.deleted_at or current.cancel_requested_at)
                if expired or (interrupted and now-interrupted > 10):
                    err = ServiceError("DEADLINE_EXCEEDED", "분석 제한 시간을 초과했습니다.") if expired else None
                    jobs.finish_error(db, job_id, token, err)
                    if allow_hard_exit:
                        os._exit(70)  # supervisor restarts; solo pool soft limits cannot interrupt native inference.
                    return
                db.execute(update(Job).where(Job.id == job_id, Job.lease_token == token,
                           Job.status.in_(["running", "cancel_requested"])).values(lease_expires_at=now+settings.lease_seconds))
                db.commit()

    threading.Thread(target=heartbeat, daemon=True).start()
    result_key, tracks_key = f"results/{job_id}/{token}.json", f"tracks/{job_id}/{token}.json"
    artifact_keys = []

    def check():
        if time.monotonic()-started > deadline:
            raise ServiceError("DEADLINE_EXCEEDED", "분석 제한 시간을 초과했습니다.")
        with SessionLocal() as db:
            jobs.check_lease(db, job_id, token)

    def progress(stage, value):
        with SessionLocal() as db:
            jobs.progress(db, job_id, token, stage, value)

    try:
        if job.model_lock_id != engine_pipeline.lock_id:
            raise ServiceError("MODEL_NOT_READY", "작업에 고정된 모델 버전이 없습니다.", 503)
        work_base = settings.episode_work_root if episode and settings.episode_work_root else storage.root / "_work"
        work_root = work_base / job_id / token
        work_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="momo-worker-", dir=work_root) as temp:
            temp = Path(temp)
            if episode:
                from .episodes.pipeline import run
                from .highlights.common import sha256
                engine_pipeline.release_models()
                result, files = run(job, storage.get_file(job.source_key), temp, progress, check)
                artifacts = []
                for name, file in files:
                    check()
                    key = f"episodes/{job_id}/{token}/{name}"
                    artifact_keys.append(key)
                    storage.put_file(key, file)
                    artifacts.append(dict(name=name, storage_key=key, sha256=sha256(file), size=file.stat().st_size))
                (temp / "published.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
                storage.put_file(result_key, temp / "published.json")
                check()
                with SessionLocal() as db:
                    published = jobs.publish(db, job_id, token, result_key, None, round(result["duration_ms"] * 1000), artifacts)
                if not published:
                    raise Cancelled()
                return True
            engine_pipeline.load_models()
            if job.options.get("task") == "highlights":
                from .highlights.pipeline import run
                from .highlights.common import sha256
                result, files = run(job, storage.get_file(job.source_key), temp, engine_pipeline, progress, check)
                artifacts = []
                for name, file in files:
                    check()
                    key = f"highlights/{job_id}/{token}/{name}"
                    artifact_keys.append(key)
                    storage.put_file(key, file)
                    artifacts.append(dict(name=name, storage_key=key, sha256=sha256(file), size=file.stat().st_size))
                (temp/"result.json").write_text(result.model_dump_json(), encoding="utf-8")
                storage.put_file(result_key, temp/"result.json")
                check()
                with SessionLocal() as db:
                    published = jobs.publish(db, job_id, token, result_key, None, result.duration_ms*1000, artifacts)
                if not published:
                    raise Cancelled()
                print(json.dumps({"job_id": job_id, "task": "highlights", "clips": len(result.clips),
                                  "seconds": round(time.monotonic()-started, 3), "data_origin": result.data_origin}), flush=True)
                return True
            result, tracks = engine_pipeline.run(job, storage.get_file(job.source_key), temp/"audio.f32", progress, check)
            check()
            validate_pair(result, tracks)
            (temp/"result.json").write_text(result.model_dump_json(), encoding="utf-8")
            (temp/"tracks.json").write_text(tracks.model_dump_json(), encoding="utf-8")
            storage.put_file(result_key, temp/"result.json")
            storage.put_file(tracks_key, temp/"tracks.json")
            with SessionLocal() as db:
                published = jobs.publish(db, job_id, token, result_key, tracks_key, result.duration_us)
            if not published:
                raise Cancelled()
            # Metrics contain no waveform/transcript/credentials.
            print(json.dumps({"job_id": job_id, "seconds": round(time.monotonic()-started, 3),
                              "stage_seconds": engine_pipeline.metrics, "data_origin": result.data_origin}), flush=True)
        return True
    except Exception as exc:
        for key in artifact_keys:
            storage.delete(key)
        storage.delete(result_key)
        storage.delete(tracks_key)
        if isinstance(exc, Cancelled):
            error = None
        elif isinstance(exc, ServiceError):
            error = exc
        elif isinstance(exc, (OSError, ConnectionError)):
            error = ServiceError("ANALYSIS_FAILED", "저장소 연결에 실패했습니다.", 503, True)
        else:
            oom = "out of memory" in str(exc).lower() or "cuda_error_out_of_memory" in str(exc).lower()
            error = ServiceError("GPU_OOM" if oom else "ANALYSIS_FAILED", "GPU 메모리가 부족합니다." if oom else "분석에 실패했습니다. 서버 실행 환경을 확인해 주세요.")
        with SessionLocal() as db:
            current = db.get(Job, job_id)
            if error and error.retryable and current and current.attempt_no < 3 and current.status == "running" and not current.deleted_at:
                changed = db.execute(update(Job).where(Job.id == job_id, Job.lease_token == token,
                    Job.status == "running", Job.deleted_at.is_(None)).values(status="queued", stage="queued",
                    progress=0, lease_token=None, lease_expires_at=None, updated_at=time.time())).rowcount
                if changed:
                    db.add(Outbox(job_id=job_id))
                db.commit()
            else:
                jobs.finish_error(db, job_id, token, error)
        return False
    finally:
        stop.set()


@celery.task(name="momo.analyze")
def analyze(job_id):
    return run_job(job_id)


@worker_ready.connect
def initialize_worker(**kwargs):
    pipeline()


def dispatch_once(local=False):
    # Sending before marking published tolerates a crash through the worker's atomic lease.
    with SessionLocal() as db:
        row = db.scalar(select(Outbox).join(Job, Job.id == Outbox.job_id).where(
            Outbox.published_at.is_(None), Job.status == "queued", Job.deleted_at.is_(None)
        ).order_by(Outbox.created_at).with_for_update(skip_locked=True))
        if not row:
            return False
        job_id = row.job_id
        if not local:
            analyze.apply_async(args=[job_id])
        row.published_at = time.time()
        db.commit()
    if local:
        run_job(job_id)
    return True


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["local", "dispatcher"])
    args = parser.parse_args()
    settings.validate_runtime()
    if args.mode == "local":
        Base.metadata.create_all(engine)
        pipeline()
    while True:
        try:
            if not dispatch_once(args.mode == "local"):
                time.sleep(2)
        except KeyboardInterrupt:
            break
