from typing import Literal
from pydantic import Field, model_validator
from ..contracts import Contract


class HighlightOptions(Contract):
    task: Literal["highlights"] = "highlights"
    language: Literal["ko"] = "ko"
    threshold: Literal[0.1] = 0.1
    max_clips: Literal[3] = 3
    consent_version: Literal["automatic-highlights-v1"]


class HighlightClip(Contract):
    number: int = Field(ge=1, le=3)
    title: str = Field(min_length=1, max_length=100)
    start_ms: int = Field(ge=0)
    end_ms: int = Field(gt=0)
    score: float = Field(ge=0, le=1)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    size: int = Field(gt=0, le=80_000_000)


class HighlightResult(Contract):
    schema_version: Literal["1.0"] = "1.0"
    job_id: str
    audio_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    duration_ms: int = Field(gt=0)
    threshold: Literal[0.1] = 0.1
    data_origin: Literal["inference", "fixture"]
    clips: list[HighlightClip] = Field(max_length=3)

    @model_validator(mode="after")
    def ordered(self):
        end = 0
        for number, clip in enumerate(self.clips, 1):
            if clip.number != number or not end <= clip.start_ms < clip.end_ms <= self.duration_ms:
                raise ValueError("Invalid/overlapping highlight interval")
            end = clip.end_ms
        return self
