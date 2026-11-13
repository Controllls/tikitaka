import json
import os
import shutil
from pathlib import Path
from ..config import settings
from ..errors import ServiceError

TOPIC_REVISION = "e87f176479d0855a907a41277aca2f8ee7a09523"
MODEL_CACHE = settings.model_root / "highlights"


def topic_model(cache_dir=MODEL_CACHE):
    path = Path(cache_dir) / "topic.gguf"
    if not path.is_file():
        raise ServiceError("MODEL_NOT_READY", "하이라이트 주제 모델을 준비해 주세요.", 503, True)
    return path


def llama_server(cache_dir=MODEL_CACHE):
    name = "llama-server.exe" if os.name == "nt" else "llama-server"
    local = Path(cache_dir) / "runtime" / name
    path = local if local.is_file() else Path(shutil.which(name) or local)
    if not path.is_file():
        raise ServiceError("MODEL_NOT_READY", "하이라이트 주제 분석 런타임을 준비해 주세요.", 503, True)
    return path


def ensure_models():
    if settings.profile == "fixture":
        return
    topic_model()
    llama_server()
    for name in ("yamnet.tflite", "yamnet_labels.txt", "model-lock.json"):
        if not (MODEL_CACHE / name).is_file():
            raise ServiceError("MODEL_NOT_READY", "하이라이트 모델을 준비해 주세요.", 503, True)
    lock = json.loads((MODEL_CACHE / "model-lock.json").read_text(encoding="utf-8"))
    if lock["topic_revision"] != TOPIC_REVISION:
        raise ServiceError("MODEL_NOT_READY", "하이라이트 모델 버전을 확인해 주세요.", 503, True)
