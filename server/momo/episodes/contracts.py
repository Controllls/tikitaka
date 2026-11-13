import hashlib
import json
from typing import Literal

from pydantic import Field, model_validator

from ..contracts import Contract
from ..audio_lab.models import AudioConfig
from ..config import settings
from ..errors import ServiceError


class EpisodeOptions(Contract):
    consent_version: Literal["server-audio-v1"]


class CastMember(Contract):
    speaker: str = Field(pattern=r"^S[1-8]$")
    name: str = Field(min_length=1, max_length=30)
    character: Literal["01-bear", "02-rabbit", "03-chick", "04-teal", "05-seal", "06-fox", "07-sheep", "08-frog"]


class RenderOptions(Contract):
    people: list[CastMember] = Field(min_length=1, max_length=4)

    @model_validator(mode="after")
    def unique(self):
        if len({p.speaker for p in self.people}) != len(self.people):
            raise ValueError("A speaker must have exactly one character")
        return self


def profile():
    """Only non-secret model settings are snapshotted; keys stay in the worker environment."""
    path = settings.episode_config_file or settings.storage_root.parent / "episode-profile.json"
    try:
        raw = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
        if not isinstance(raw, dict) or set(raw) - set(AudioConfig.model_fields):
            raise ValueError("Unknown episode profile fields")
        audio = AudioConfig.model_validate({"pipeline": "segments", **raw, "mode": "all"})
    except (ValueError, OSError):
        raise ServiceError("EPISODE_PROFILE_INVALID", "서버의 영상 제작 모델 설정을 확인해 주세요.", 503)
    data = audio.model_dump()
    return data, hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()
