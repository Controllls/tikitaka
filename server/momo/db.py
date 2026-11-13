import time
import uuid
from sqlalchemy import JSON, Boolean, Float, ForeignKey, Integer, String, create_engine, event
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker
from .config import settings


def uid():
    return str(uuid.uuid4())


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    username: Mapped[str] = mapped_column(String(128), unique=True)
    password_hash: Mapped[str] = mapped_column(String(512))
    created_at: Mapped[float] = mapped_column(Float, default=time.time)


class Installation(Base):
    __tablename__ = "installations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), unique=True)
    secret_hash: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[float] = mapped_column(Float, default=time.time)
    revoked_at: Mapped[float | None] = mapped_column(Float)


class Session(Base):
    __tablename__ = "sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    family_id: Mapped[str] = mapped_column(String(36), default=uid)
    expires_at: Mapped[float] = mapped_column(Float)
    revoked_at: Mapped[float | None] = mapped_column(Float)


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id: Mapped[str] = mapped_column(ForeignKey("sessions.id"), index=True)
    issued_at: Mapped[float] = mapped_column(Float, default=time.time)
    used_at: Mapped[float | None] = mapped_column(Float)
    expires_at: Mapped[float] = mapped_column(Float)
    revoked_at: Mapped[float | None] = mapped_column(Float)


class Job(Base):
    __tablename__ = "analysis_jobs"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    owner_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    parent_job_id: Mapped[str | None] = mapped_column(String(36))
    status: Mapped[str] = mapped_column(String(24), default="queued", index=True)
    stage: Mapped[str] = mapped_column(String(32), default="queued")
    progress: Mapped[float] = mapped_column(Float, default=0)
    options: Mapped[dict] = mapped_column(JSON)
    pipeline_version: Mapped[str] = mapped_column(String(64), default="momo-server-v1")
    model_lock_id: Mapped[str] = mapped_column(String(64))
    audio_sha256: Mapped[str] = mapped_column(String(64))
    source_key: Mapped[str] = mapped_column(String(128))
    result_key: Mapped[str | None] = mapped_column(String(128))
    tracks_key: Mapped[str | None] = mapped_column(String(128))
    duration_us: Mapped[int | None] = mapped_column(Integer)
    error_json: Mapped[dict | None] = mapped_column(JSON)
    lease_token: Mapped[str | None] = mapped_column(String(36))
    lease_expires_at: Mapped[float | None] = mapped_column(Float)
    attempt_no: Mapped[int] = mapped_column(Integer, default=0)
    cancel_requested_at: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[float] = mapped_column(Float, default=time.time, index=True)
    updated_at: Mapped[float] = mapped_column(Float, default=time.time)
    terminal_at: Mapped[float | None] = mapped_column(Float)
    source_expires_at: Mapped[float | None] = mapped_column(Float)
    result_expires_at: Mapped[float | None] = mapped_column(Float)
    deleted_at: Mapped[float | None] = mapped_column(Float)


class JobArtifact(Base):
    __tablename__ = "job_artifacts"
    job_id: Mapped[str] = mapped_column(ForeignKey("analysis_jobs.id"), primary_key=True)
    name: Mapped[str] = mapped_column(String(64), primary_key=True)
    storage_key: Mapped[str] = mapped_column(String(256), unique=True)
    sha256: Mapped[str] = mapped_column(String(64))
    size: Mapped[int] = mapped_column(Integer)


class Idempotency(Base):
    __tablename__ = "idempotency"
    owner_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    key: Mapped[str] = mapped_column(String(128), primary_key=True)
    request_fingerprint: Mapped[str] = mapped_column(String(64))
    job_id: Mapped[str | None] = mapped_column(String(36))
    reservation_expires_at: Mapped[float] = mapped_column(Float)
    expires_at: Mapped[float] = mapped_column(Float)


class Outbox(Base):
    __tablename__ = "outbox"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    job_id: Mapped[str] = mapped_column(ForeignKey("analysis_jobs.id"), index=True)
    event_type: Mapped[str] = mapped_column(String(32), default="analysis.requested")
    published_at: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[float] = mapped_column(Float, default=time.time)


class WorkerRegistration(Base):
    __tablename__ = "workers"
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    heartbeat_at: Mapped[float] = mapped_column(Float)
    model_lock_id: Mapped[str] = mapped_column(String(64))
    ready: Mapped[bool] = mapped_column(Boolean, default=False)


def make_engine(url):
    engine = create_engine(url, connect_args={"check_same_thread": False, "timeout": 30} if url.startswith("sqlite") else {}, pool_pre_ping=True)
    if url.startswith("sqlite"):
        @event.listens_for(engine, "connect")
        def sqlite_config(connection, _):
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA journal_mode=WAL")
    return engine


settings.storage_root.parent.mkdir(parents=True, exist_ok=True)
engine = make_engine(settings.database_url)
SessionLocal = sessionmaker(engine, expire_on_commit=False)


def get_db():
    with SessionLocal() as db:
        yield db
