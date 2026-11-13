"""One definition for API, JSON Schema and worker semantic validation. Times are integer us."""
import math
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class AnalysisOptions(Contract):
    language: Literal["ko"] = "ko"
    speaker_count_hint: int = Field(default=0, ge=0, le=8)
    asr_profile: Literal["standard"] = "standard"
    consent_version: Literal["server-audio-v1"]


class Speaker(Contract):
    speaker_id: str = Field(pattern=r"^S[1-8]$")
    first_seen_us: int = Field(ge=0)


class Interval(Contract):
    start_us: int = Field(ge=0)
    end_us: int = Field(gt=0)

    @model_validator(mode="after")
    def ordered(self):
        if self.start_us >= self.end_us:
            raise ValueError("Empty/reversed interval")
        return self


class Event(Interval):
    event_id: str
    kind: Literal["speech", "laughter", "environment", "silence"]
    active_speaker_ids: list[str] = Field(default_factory=list)
    laughing_speaker_ids: list[str] = Field(default_factory=list)
    score: float = Field(ge=0, le=1)
    requires_review: bool = False
    review_reasons: list[str] = Field(default_factory=list)
    energy_reference: float = Field(default=0.0002, ge=0.0002)


class Word(Interval):
    text: str


class Caption(Interval):
    caption_id: str
    text: str
    speaker_ids: list[str] = Field(default_factory=list)
    requires_review: bool = False
    words: list[Word] = Field(default_factory=list)


class AudioInfo(Contract):
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    original_mime: str
    analysis_sample_rate: Literal[16000] = 16000
    analysis_channels: Literal[1] = 1
    decoded_time_origin_us: Literal[0] = 0


class ModelInfo(Contract):
    name: str
    revision: str
    runtime: str
    precision: str


class Warning(Contract):
    code: str
    message: str


class TrackRef(Contract):
    endpoint: str
    frame_step_us: Literal[20000] = 20000
    frame_count: int = Field(gt=0)


class SpeakerSignature(Contract):
    speaker_id: str = Field(pattern=r"^S[1-8]$")
    embedding: list[float] = Field(min_length=192, max_length=192)
    speech_us: int = Field(ge=3000000, le=30000000)
    segments: list[Interval] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def valid_signature(self):
        if abs(sum(v*v for v in self.embedding)-1) > .02:
            raise ValueError("Voice embedding must be normalized")
        if sum(s.end_us-s.start_us for s in self.segments) != self.speech_us:
            raise ValueError("Voice duration differs from selected speech")
        if any(a.end_us > b.start_us for a, b in zip(self.segments, self.segments[1:])):
            raise ValueError("Overlapping voice samples")
        return self


class AnalysisResult(Contract):
    schema_version: Literal["1.0"] = "1.0"
    pipeline_version: Literal["momo-server-v1"] = "momo-server-v1"
    job_id: str
    duration_us: int = Field(ge=100000, le=600000000)
    data_origin: Literal["inference", "fixture"]
    audio: AudioInfo
    speakers: list[Speaker] = Field(max_length=8)
    events: list[Event]
    captions: list[Caption]
    models: list[ModelInfo]
    tracks: TrackRef
    warnings: list[Warning] = Field(default_factory=list)
    voice_model_id: str | None = None
    speaker_signatures: list[SpeakerSignature] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def semantic(self):
        ids = [s.speaker_id for s in self.speakers]
        signature_ids = [s.speaker_id for s in self.speaker_signatures]
        if len(set(signature_ids)) != len(signature_ids) or not set(signature_ids) <= set(ids):
            raise ValueError("Invalid voice signature speaker")
        if self.speaker_signatures and not self.voice_model_id:
            raise ValueError("Voice model ID required")
        if any(s.end_us > self.duration_us for v in self.speaker_signatures for s in v.segments):
            raise ValueError("Voice sample outside audio")
        if len(set(ids)) != len(ids) or ids != [f"S{i+1}" for i in range(len(ids))]:
            raise ValueError("Speaker IDs must follow first-appearance order")
        if [s.first_seen_us for s in self.speakers] != sorted(s.first_seen_us for s in self.speakers):
            raise ValueError("Speakers not in first-appearance order")
        if any(s.first_seen_us >= self.duration_us for s in self.speakers):
            raise ValueError("Speaker first appearance outside audio")
        cursor = 0
        for e in self.events:
            if e.start_us != cursor:
                raise ValueError("Events must partition the complete source timeline")
            cursor = e.end_us
            active, laughing = set(e.active_speaker_ids), set(e.laughing_speaker_ids)
            if len(active) != len(e.active_speaker_ids) or len(laughing) != len(e.laughing_speaker_ids):
                raise ValueError("Duplicate speaker reference")
            if not laughing <= active <= set(ids):
                raise ValueError("Invalid speaker reference")
            if e.kind in {"silence", "environment"} and active:
                raise ValueError("Non-vocal event cannot have speakers")
            if e.kind == "laughter" and not laughing and not e.requires_review:
                raise ValueError("Unknown laughter requires review")
        if cursor != self.duration_us:
            raise ValueError("Incomplete event timeline")
        for collection, key in [(self.events, "event_id"), (self.captions, "caption_id")]:
            if len({getattr(x, key) for x in collection}) != len(collection):
                raise ValueError("Duplicate interval ID")
            if any(x.end_us > self.duration_us for x in collection):
                raise ValueError("Interval exceeds duration")
        for c in self.captions:
            if not set(c.speaker_ids) <= set(ids) or len(set(c.speaker_ids)) != len(c.speaker_ids):
                raise ValueError("Invalid caption speakers")
            if any(w.start_us < c.start_us or w.end_us > c.end_us for w in c.words):
                raise ValueError("Word outside caption")
        if self.tracks.frame_count != math.ceil(self.duration_us / 20000):
            raise ValueError("Invalid frame count")
        return self


class SpeakerTrack(Contract):
    speaker_id: str
    activity: list[float]
    laughter_activity: list[float]
    energy_source: Literal["activity_weighted_mixture"] = "activity_weighted_mixture"


class MotionTracks(Contract):
    schema_version: Literal["1.0"] = "1.0"
    job_id: str
    duration_us: int = Field(ge=100000, le=600000000)
    frame_step_us: Literal[20000] = 20000
    frame_count: int = Field(gt=0)
    mixed_voice_energy: list[float]
    speaker_tracks: list[SpeakerTrack]

    @model_validator(mode="after")
    def semantic(self):
        if self.frame_count != math.ceil(self.duration_us / self.frame_step_us):
            raise ValueError("Invalid frame count")
        if len(self.mixed_voice_energy) != self.frame_count or any(x < 0 for x in self.mixed_voice_energy):
            raise ValueError("Invalid energy")
        if len({s.speaker_id for s in self.speaker_tracks}) != len(self.speaker_tracks):
            raise ValueError("Duplicate track")
        for track in self.speaker_tracks:
            for array in (track.activity, track.laughter_activity):
                if len(array) != self.frame_count or any(x < 0 or x > 1 for x in array):
                    raise ValueError("Invalid activity")
        return self


def validate_pair(result: AnalysisResult, tracks: MotionTracks):
    if (result.job_id, result.duration_us, result.tracks.frame_count) != (
        tracks.job_id, tracks.duration_us, tracks.frame_count
    ) or {s.speaker_id for s in result.speakers} != {s.speaker_id for s in tracks.speaker_tracks}:
        raise ValueError("Result/track mismatch")


class ErrorDetail(Contract):
    code: str
    message: str
    retryable: bool = False
    details: dict = Field(default_factory=dict)
    request_id: str = ""


class JobStatus(Contract):
    job_id: str
    parent_job_id: str | None = None
    status: Literal["queued", "running", "cancel_requested", "cancelled", "completed", "failed"]
    stage: str
    progress: float
    created_at: str
    updated_at: str
    source_expires_at: str | None = None
    result_expires_at: str | None = None
    poll_after_seconds: int = 2
    error: ErrorDetail | None = None


class Person(Contract):
    person_id: str = Field(pattern=r"^P[1-4]$")
    name: str
    seat: Literal["driver", "front_passenger", "rear_left", "rear_right"]
    asset_ref: str | None = None
    parts: dict[str, str] = Field(default_factory=dict)
    manifest_ref: str | None = None
    pose: Literal["front", "left", "right", "down"] = "front"


class CabinLayout(Contract):
    car_left: float = -120
    car_top: float = 220
    car_width: float = 780
    front_height: float = 900
    rear_height: float = 620
    front_root_y: float = 1000
    rear_root_y: float = 730


class EventOverride(Contract):
    event_id: str
    person_ids: list[str] | None = None
    kind_override: Literal["speech", "laughter", "environment", "silence"] | None = None
    acknowledged: bool = False


class CaptionOverride(Contract):
    caption_id: str
    text: str


class SceneProject(Contract):
    """Local Android document only; no API accepts or uploads character assets."""
    project_id: str
    title: str
    audio_local_ref: str
    source_audio_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    duration_us: int = Field(ge=100000, le=600000000)
    analysis_job_id: str | None = None
    people: list[Person] = Field(min_length=1, max_length=4)
    speaker_mapping: dict[str, str] = Field(default_factory=dict)
    event_overrides: list[EventOverride] = Field(default_factory=list)
    caption_overrides: list[CaptionOverride] = Field(default_factory=list)
    sample_visuals: bool = False
    background_ref: str | None = None
    background_is_video: bool = False
    vehicle_ref: str | None = None
    renderer_version: Literal["momo-android-motion-v1"] = "momo-android-motion-v1"
    width: Literal[540] = 540
    height: Literal[960] = 960
    fps: Literal[30] = 30
    seed: int = 1
    preset_fingerprint: str = ""
    seatbelts_ref: str | None = None
    steering_wheel_ref: str | None = None
    layout: CabinLayout = Field(default_factory=CabinLayout)

    @model_validator(mode="after")
    def semantic(self):
        ids = {p.person_id for p in self.people}
        if len(ids) != len(self.people) or len({p.seat for p in self.people}) != len(self.people):
            raise ValueError("Duplicate person/seat")
        if not set(self.speaker_mapping.values()) <= ids or not set(self.speaker_mapping) <= {f"S{i}" for i in range(1, 9)}:
            raise ValueError("Unknown speaker/person mapping")
        for override in self.event_overrides:
            if override.person_ids is not None and (not set(override.person_ids) <= ids or len(set(override.person_ids)) != len(override.person_ids)):
                raise ValueError("Unknown/duplicate manual person")
        if len({o.event_id for o in self.event_overrides}) != len(self.event_overrides):
            raise ValueError("Duplicate event override")
        if len({o.caption_id for o in self.caption_overrides}) != len(self.caption_overrides):
            raise ValueError("Duplicate caption override")
        return self
