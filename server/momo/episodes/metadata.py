"""Text/event-only planning. No audio decoder, speaker inference or outbound AI call."""
import hashlib
import json
from typing import Literal

from fastapi import Depends
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .. import auth


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False, strict=True)


class TimedText(Strict):
    start_ms: int = Field(ge=0)
    end_ms: int = Field(gt=0)
    text: str = Field(min_length=1, max_length=2000)


class Laugh(Strict):
    start_ms: int = Field(ge=0)
    end_ms: int = Field(gt=0)
    score: float = Field(ge=0, le=1)


class Metadata(Strict):
    schema_version: Literal["local-metadata-v1"]
    consent_version: Literal["transcript-events-v1"]
    source_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    duration_ms: int = Field(ge=100, le=43_200_000)
    stt_status: Literal["complete", "partial", "unavailable"]
    detector: Literal["yamnet-local-v1", "yamnet-local-v2"]
    laughter_threshold: float = Field(ge=.01, le=1)
    captions: list[TimedText] = Field(max_length=30000)
    laughter: list[Laugh] = Field(max_length=30000)
    clip_seconds: int = Field(default=40, ge=10, le=120)
    max_clips: int = Field(default=3, ge=1, le=10)

    @model_validator(mode="after")
    def timeline(self):
        for rows in (self.captions, self.laughter):
            previous = 0
            for row in rows:
                if not previous <= row.start_ms < row.end_ms <= self.duration_ms:
                    raise ValueError("Overlapping or out-of-range timeline")
                previous = row.end_ms
        if self.stt_status == "unavailable" and self.captions:
            raise ValueError("Unavailable recognition cannot supply captions")
        if any(not c.text.strip() for c in self.captions):
            raise ValueError("Blank caption")
        return self


def plan(body: Metadata):
    # Deterministic retry: identical input returns the same result without storing text.
    digest = hashlib.sha256(json.dumps(body.model_dump(), sort_keys=True,
        ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    candidates = [(r.start_ms, r.end_ms, r.score, "laughter") for r in body.laughter]
    if not candidates:
        # This is a dialogue excerpt, not a claim that the text is funny.
        candidates = [(r.start_ms, r.end_ms, min(len(r.text) / 200, .8), "dialogue") for r in body.captions]
    selected = []
    length = min(body.duration_ms, body.clip_seconds * 1000)
    for start, end, score, reason in sorted(candidates, key=lambda r: (-r[2], r[0])):
        left = max(0, min(start - length * 2 // 3, body.duration_ms - length))
        right = left + length
        for c in selected:
            if left < c["end_ms"] and c["start_ms"] < right:
                if end <= c["start_ms"]:
                    right = min(right, c["start_ms"])
                elif start >= c["end_ms"]:
                    left = max(left, c["end_ms"])
                else:
                    right = left
                    break
        if right-left < min(10000, body.duration_ms):
            continue
        captions = [c.model_dump() for c in body.captions if c.start_ms < right and c.end_ms > left]
        laughter = [c.model_dump() for c in body.laughter if c.start_ms < right and c.end_ms > left]
        title = (captions[0]["text"][:36] if captions else "웃음이 담긴 순간")
        selected.append(dict(start_ms=left, end_ms=right, score=score, reason=reason,
            title=title, captions=captions, laughter=laughter))
        if len(selected) >= body.max_clips:
            break
    selected.sort(key=lambda c: c["start_ms"])
    warnings = ["화자 식별 없이 선택한 캐릭터가 대사와 웃음을 연기합니다."]
    if body.stt_status != "complete":
        warnings.append("기기 음성 인식이 불완전하여 자막 또는 대화 구간이 누락될 수 있습니다.")
    return dict(schema_version="local-plan-v1", plan_id=digest, source_sha256=body.source_sha256,
        duration_ms=body.duration_ms, selector="event-context-rules-v1", speaker_identity="unknown",
        clips=selected, warnings=warnings)


def install(app):
    @app.post("/v1/episodes/metadata")
    def metadata(body: Metadata, owner=Depends(auth.current_user)):
        return plan(body)
