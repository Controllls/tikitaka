from __future__ import annotations

import os
from pathlib import Path
import platform
import urllib.request
import zipfile

from .common import ROOT, log, sha256

LLAMA_TAG = "b12110"
LLAMA_ZIP = f"llama-{LLAMA_TAG}-bin-win-vulkan-x64.zip"
LLAMA_URL = f"https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA_TAG}/{LLAMA_ZIP}"
LLAMA_SHA256 = "9732a5e48887ca54d0bfe4af4324dc85fd56cd8cda19b52df3542fa6ebe6874f"
TOPIC_REPO = "unsloth/Qwen3.5-4B-GGUF"
TOPIC_REVISION = "e87f176479d0855a907a41277aca2f8ee7a09523"
TOPIC_FILE = "Qwen3.5-4B-Q4_K_M.gguf"
YAMNET = ROOT / "app/src/main/assets/yamnet.tflite"
LABELS = ROOT / "app/src/main/assets/yamnet_labels.txt"
MODEL_CACHE = ROOT / "build/tools"
_DLL_HANDLES = []


def prepare_cuda() -> None:
    import sys
    folders = list((Path(sys.prefix) / "Lib/site-packages/nvidia").glob("*/bin"))
    if os.name == "nt":
        for folder in folders:
            _DLL_HANDLES.append(os.add_dll_directory(str(folder)))
        os.environ["PATH"] = os.pathsep.join(map(str, folders)) + os.pathsep + os.environ.get("PATH", "")


def topic_model(cache_dir: Path = MODEL_CACHE) -> Path:
    from huggingface_hub import hf_hub_download
    log("주제 경계 모델 확인: Qwen3.5-4B Q4_K_M")
    return Path(hf_hub_download(repo_id=TOPIC_REPO, filename=TOPIC_FILE,
        revision=TOPIC_REVISION, cache_dir=str(cache_dir / "topic-models")))


def llama_server(cache_dir: Path = MODEL_CACHE) -> Path:
    import shutil
    if binary := shutil.which("llama-server"):
        return Path(binary)
    if os.name != "nt" or platform.machine().lower() not in ("amd64", "x86_64"):
        raise RuntimeError("이 플랫폼에서는 llama-server를 설치한 뒤 PATH에 추가해 주세요.")
    folder = cache_dir / f"llama-{LLAMA_TAG}"
    existing = list(folder.rglob("llama-server.exe")) if folder.exists() else []
    if existing:
        return existing[0]
    cache_dir.mkdir(parents=True, exist_ok=True)
    archive = cache_dir / LLAMA_ZIP
    if not archive.exists() or sha256(archive) != LLAMA_SHA256:
        log("llama.cpp Windows Vulkan 런타임 다운로드")
        temporary = archive.with_suffix(".download")
        urllib.request.urlretrieve(LLAMA_URL, temporary)
        if sha256(temporary) != LLAMA_SHA256:
            raise RuntimeError("llama.cpp 다운로드 체크섬이 일치하지 않습니다.")
        temporary.replace(archive)
    folder.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as zipped:
        for entry in zipped.infolist():
            target = (folder / entry.filename).resolve()
            if not target.is_relative_to(folder.resolve()):
                raise RuntimeError("런타임 압축 파일에 잘못된 경로가 있습니다.")
        zipped.extractall(folder)
    return next(folder.rglob("llama-server.exe"))
