from pathlib import Path
from typing import Literal
import urllib.request

from pydantic import BaseModel, Field, model_validator

from ..config import settings
from .limits import MAX_VIDEO_SECONDS

STAGES = {"vad": "음성 구간 감지", "laughter": "웃음 감지", "stt": "음성 인식·자막",
          "diarization": "화자 분리", "voice": "목소리 특징", "topics": "주제·하이라이트"}
DEFAULTS = {"vad": "silero-v5", "laughter": "yamnet", "stt": "whisper-small",
            "diarization": "nemotron", "voice": "ecapa", "topics": "qwen-local"}
OPTIONS = {
    "vad": [("silero-v5", "Silero 5.1.2", "기존 모델 · 로컬"), ("silero-v6", "Silero 6.0", "로컬 · 첫 실행 다운로드")],
    "laughter": [("yamnet", "YAMNet", "기존 모델 · 약 1초 구간"),
                 ("ast", "Audio Spectrogram Transformer", "로컬 · 5초 구간 · 첫 실행 약 350 MB")],
    "stt": [("whisper-small", "Whisper small", "기존 모델 · 단어 시각"),
            ("whisper-base", "Whisper base", "로컬 · 첫 실행 약 145 MB"),
            ("whisper-tiny", "Whisper tiny", "로컬 · 첫 실행 약 75 MB"),
            ("openai:gpt-transcribe", "GPT Transcribe", "API · VAD 구간 단위 시각"),
            ("openai:whisper-1", "OpenAI Whisper-1", "API · 단어 시각 · 2027-02-26 종료 예정")],
    "diarization": [("nemotron", "Nemotron", "기존 모델 · 겹친 발화 지원"),
                    ("ecapa-cluster", "ECAPA + 군집화", "로컬 · 짧은 발화·동시 발화에 제한"),
                    ("xvector-cluster", "X-vector + 군집화", "로컬 · 짧은 발화·동시 발화에 제한"),
                    ("openai:gpt-4o-transcribe-diarize", "OpenAI Diarize", "API · 2027-02-26 종료 예정")],
    "voice": [("ecapa", "ECAPA-TDNN", "기존 모델 · 192차원"),
              ("xvector", "X-vector", "로컬 · 512차원 · 첫 실행 약 17 MB")],
    "topics": [("qwen-local", "Qwen3.5-4B", "기존 로컬 모델 · 문맥 구간별 분석"),
               ("openai:custom", "OpenAI 모델 선택", "API · 계정 모델 목록 / 직접 입력")],
}
DOWNLOADS = {
    "whisper-base": ("Systran/faster-whisper-base", "ebe41f70d5b6dfa9166e2c581c45c9c0cfc57b66", ["model.bin", "config.json", "tokenizer.json", "vocabulary.txt"]),
    "whisper-tiny": ("Systran/faster-whisper-tiny", "d90ca5fe260221311c53c58e660288d3deb8d356", ["model.bin", "config.json", "tokenizer.json", "vocabulary.txt"]),
    "ast": ("MIT/ast-finetuned-audioset-10-10-0.4593", "f826b80d28226b62986cc218e5cec390b1096902", ["model.safetensors", "config.json", "preprocessor_config.json"]),
    "xvector": ("speechbrain/spkrec-xvect-voxceleb", "56895a2df401be4150a159f3a1c653f00051d477", ["embedding_model.ckpt"]),
}
SILERO_COMMIT = "fba061dc5559f696e62171e9a0741782b0fdc23c"


def model_root() -> Path:
    return settings.model_root.resolve()


def catalog() -> dict:
    def note(stage, key, value):
        return value + (f" · {settings.lab_device.upper()}" if stage in {"stt", "topics"} and not key.startswith("openai:") else "")
    return {"stages": [{"id": stage, "label": label, "default": DEFAULTS[stage],
                        "options": [{"id": key, "label": name, "note": note(stage, key, description)} for key, name, description in OPTIONS[stage]]}
                       for stage, label in STAGES.items()], "max_file_bytes": settings.lab_max_audio_bytes,
            "max_seconds": settings.lab_max_seconds, "max_video_seconds": MAX_VIDEO_SECONDS, "execution_device": settings.lab_device,
            "default_pipeline": "segments"}


class SpeakerReview(BaseModel):
    mode: Literal["local", "claude", "conservative"] = "local"
    model: str = Field(default="", max_length=200, pattern=r"^[a-zA-Z0-9_.-]*$")

    @model_validator(mode="after")
    def selected(self):
        if self.mode == "claude" and not self.model.startswith("claude-"):
            raise ValueError("화자 재검토에 사용할 Claude 모델을 선택해 주세요.")
        return self


class AudioConfig(BaseModel):
    pipeline: Literal["app", "experiment", "segments"] = "app"
    segment_separation: bool = True
    laughter_captions: bool = True
    speaker_review: SpeakerReview = Field(default_factory=SpeakerReview)
    vad: str = DEFAULTS["vad"]
    laughter: str = DEFAULTS["laughter"]
    stt: str = DEFAULTS["stt"]
    diarization: str = DEFAULTS["diarization"]
    voice: str = DEFAULTS["voice"]
    topics: str = DEFAULTS["topics"]
    mode: str = "all"
    threshold: float = Field(default=.1, gt=0, le=1, allow_inf_nan=False)
    max_clips: int = Field(default=3, ge=1, le=10)
    max_output_tokens: int = Field(default=8192, ge=1024, le=32768)
    speaker_count: int = Field(default=0, ge=0, le=8)
    qwen_thinking: bool = False

    @model_validator(mode="after")
    def supported(self):
        import re
        if self.pipeline in {"app", "segments"}:
            if any(getattr(self, key) != value for key, value in DEFAULTS.items() if key != "topics") or (self.pipeline == "app" and self.threshold != .1):
                raise ValueError("앱과 동일 모드에서는 앱 기본 음성 모델과 판정 규칙을 사용합니다. 모델·감도 변경은 모델별 실험 모드를 선택해 주세요.")
        if self.pipeline == "segments" and self.threshold < .001:
            raise ValueError("웃음 판정 기준은 0.001~1 범위로 입력해 주세요.")
        if self.pipeline == "segments" and self.mode not in {"all", "topics"}:
            raise ValueError("캐릭터별 구간 분석은 전체 분석 또는 원본 하이라이트 추출로 실행하세요.")
        if self.mode not in {"all", *STAGES}:
            raise ValueError("Unknown experiment mode")
        for stage in STAGES:
            value = getattr(self, stage)
            if stage == "topics" and value.startswith("openai:") and value != "openai:custom":
                if re.fullmatch(r"[a-zA-Z0-9_.:\-]{1,200}", value[7:]):
                    continue
            if value not in {row[0] for row in OPTIONS[stage]} or value == "openai:custom":
                raise ValueError(f"Unsupported model for {stage}")
        return self

    def stages(self) -> list[str]:
        if self.pipeline == "segments":
            return ["vad", "laughter", "diarization", "voice", "stt", "topics"]
        dependencies = {"vad": {"vad"}, "laughter": {"laughter"}, "stt": {"vad", "stt"},
                        "diarization": {"vad", "diarization"}, "voice": {"vad", "diarization", "voice"},
                        "topics": {"vad", "laughter", "stt", "topics"}}
        chosen = set(STAGES) if self.mode == "all" else dependencies[self.mode]
        order = list(STAGES)
        if self.pipeline == "app":
            if chosen & {"laughter", "stt", "diarization", "voice"}:
                chosen |= {"vad", "laughter"}
            order = ["vad", "laughter", "diarization", "stt", "voice", "topics"]
        return [key for key in order if key in chosen]

    def requires_key(self) -> bool:
        return any(getattr(self, stage).startswith("openai:") for stage in self.stages())


def prepare(name: str, root: Path) -> Path:
    name = "xvector" if name == "xvector-cluster" else name
    destination = root / "audio-lab" / name
    if name in DOWNLOADS:
        from huggingface_hub import snapshot_download
        repo, revision, files = DOWNLOADS[name]
        if not all((destination / file).is_file() for file in files):
            snapshot_download(repo, revision=revision, allow_patterns=files, local_dir=str(destination), token=False)
        return destination
    if name == "silero-v6":
        destination.mkdir(parents=True, exist_ok=True)
        path = destination / "silero.onnx"
        if not path.is_file():
            url = f"https://raw.githubusercontent.com/snakers4/silero-vad/{SILERO_COMMIT}/src/silero_vad/data/silero_vad.onnx"
            pending = path.with_suffix(".pending")
            with urllib.request.urlopen(url, timeout=90) as source, pending.open("wb") as target:
                import shutil
                shutil.copyfileobj(source, target)
            pending.replace(path)
        return path
    return root
