"""Download only pinned, allowlisted model sources. Run before starting a worker."""
import argparse
import concurrent.futures
import hashlib
import json
import shutil
import urllib.request
from pathlib import Path
from .config import settings

NEMOTRON = "353b6f8ad2cac3580e982d7fbdf0a010786b0406"
WHISPER = "536b0662742c02347bc0e980a01041f333bce120"
TF_MODELS = "19f287775ae950e22a9b5fb82727e72cff2e3664"
NBASE = f"https://huggingface.co/onnx-community/Nemotron-3-Diarization-ONNX/resolve/{NEMOTRON}/"
WBASE = f"https://huggingface.co/Systran/faster-whisper-small/resolve/{WHISPER}/"
TBASE = f"https://raw.githubusercontent.com/tensorflow/models/{TF_MODELS}/research/audioset/yamnet/"
SOURCES = [
    ("nemotron/onnx/model.onnx", NBASE + "onnx/model.onnx", "12a7c98fc1ee6ec98ea728c7ffd9fcf6b245f3c9c1ea9f7c2a7cc47c023e3f9d"),
    ("nemotron/onnx/model.onnx_data", NBASE + "onnx/model.onnx_data", "c293d9b5930eb9f6172f095ced052c0d1bbdbeb2594a115497583ca209b1dbd6"),
    *[("nemotron/" + n, NBASE + n, None) for n in ["config.json", "processor_config.json"]],
    ("whisper/model.bin", WBASE + "model.bin", "3e305921506d8872816023e4c273e75d2419fb89b24da97b4fe7bce14170d671"),
    *[("whisper/" + n, WBASE + n, None) for n in ["config.json", "tokenizer.json", "vocabulary.txt"]],
    *[("yamnet/" + n, TBASE + n, None) for n in ["params.py", "features.py", "yamnet.py", "yamnet_class_map.csv"]],
    ("yamnet/yamnet.h5", "https://storage.googleapis.com/audioset/yamnet.h5", None),
]


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(silero_source=None):
    root = settings.model_root
    root.mkdir(parents=True, exist_ok=True)
    checked_in = Path(__file__).resolve().parents[2] / "contracts" / "model-lock.json"
    prior = json.loads(checked_in.read_text()) if checked_in.exists() else {}
    known = {f["path"]: f["sha256"] for f in prior.get("files", [])}

    def download(row):
        name, url, expected = row
        expected = expected or known.get(name)
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or (expected and sha(path) != expected):
            temp = path.with_suffix(path.suffix + ".partial")
            print(f"Downloading {name}", flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "MOMO-model-setup/1"})
            with urllib.request.urlopen(req, timeout=120) as response, temp.open("wb") as out:
                shutil.copyfileobj(response, out, 1024 * 1024)
            if expected and sha(temp) != expected:
                temp.unlink()
                raise RuntimeError(f"SHA-256 mismatch: {name}")
            temp.replace(path)
        return dict(path=name, url=url, sha256=sha(path), bytes=path.stat().st_size)

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        files = list(pool.map(download, SOURCES))
    vad = root / "silero/silero-v5.1.2.onnx"
    expected = "2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f"
    if silero_source:
        vad.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(silero_source, vad)
    if not vad.exists() or sha(vad) != expected:
        raise RuntimeError("Supply --silero-source from the original v13 vendor/vad/silero-v5.1.2.onnx")
    files.append(dict(path="silero/silero-v5.1.2.onnx", url="local:v13/vendor/vad", sha256=expected, bytes=vad.stat().st_size))
    lock = dict(version=1, pipeline_version="momo-server-v1", revisions=dict(nemotron=NEMOTRON,
                whisper=WHISPER, yamnet=TF_MODELS, silero="5.1.2"),
                licenses=dict(nemotron="Open Model, Data, and Weights License 1.1 (openmdw-1.1)",
                              whisper="MIT", yamnet="Apache-2.0", silero="MIT"), files=files)
    content = json.dumps(lock, indent=2, ensure_ascii=False) + "\n"
    (root / "model-lock.json").write_text(content, encoding="utf-8")
    if not checked_in.exists():
        checked_in.write_text(content, encoding="utf-8")
    print("All model files verified; lock written.", flush=True)
    from .prepare_voice_model import prepare as prepare_voice
    prepare_voice()


def verify():
    lock_file = settings.model_root / "model-lock.json"
    lock = json.loads(lock_file.read_text(encoding="utf-8"))
    for item in lock["files"]:
        file = settings.model_root / item["path"]
        if not file.is_file() or file.stat().st_size != item["bytes"] or sha(file) != item["sha256"]:
            raise RuntimeError(f"Model integrity check failed: {item['path']}")
    return lock


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--silero-source", type=Path)
    args = parser.parse_args()
    prepare(args.silero_source)
