from pathlib import Path
from typing import Literal
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="MOMO_", env_file=".env", extra="ignore")
    database_url: str = "sqlite:///./data/momo.db"
    broker_url: str = "redis://localhost:6379/0"
    storage_root: Path = Path("data/objects")
    model_root: Path = Path("data/models")
    storage_backend: str = "filesystem"
    s3_bucket: str = ""
    s3_endpoint_url: str | None = None
    jwt_secret: str = ""
    jwt_issuer: str = "momo-server"
    jwt_audience: str = "momo-android"
    profile: str = "production"
    # CPU is an explicit development choice; CUDA failures never silently fall back.
    inference_device: str = "cuda"
    cuda_library_root: Path | None = None
    local_dispatch: bool = False
    deadline_seconds: int = 1800
    lease_seconds: int = 90
    queue_seconds: int = 86400
    source_ttl_seconds: int = 86400
    result_ttl_seconds: int = 2592000
    max_audio_bytes: int = 80_000_000
    episode_max_audio_bytes: int = 10_000_000_000
    episode_deadline_seconds: int = 24 * 3600
    episode_config_file: Path | None = None
    episode_work_root: Path | None = None
    highlight_max_seconds: int = 7200
    highlight_topic_device: str = "cpu"
    lab_max_audio_bytes: int = 10_000_000_000
    lab_max_seconds: int = 6 * 3600
    lab_temp_root: Path | None = None
    lab_device: Literal["gpu", "cpu"] = "gpu"
    ffmpeg: str = "ffmpeg"
    ffprobe: str = "ffprobe"

    def validate_runtime(self):
        if len(self.jwt_secret) < 32:
            raise RuntimeError("Set MOMO_JWT_SECRET to a random secret of at least 32 characters")
        if self.profile not in {"production", "development", "fixture"}:
            raise RuntimeError("Unknown MOMO_PROFILE")
        if self.profile == "production" and (self.local_dispatch or self.database_url.startswith("sqlite")):
            raise RuntimeError("Production requires PostgreSQL and the outbox/Celery dispatcher")


settings = Settings()
