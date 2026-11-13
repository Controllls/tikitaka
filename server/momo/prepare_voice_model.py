"""Pinned SpeechBrain ECAPA weights. No remote Python/YAML execution or user audio."""
import hashlib
import json
import urllib.request
from .config import settings

REVISION = "0f99f2d0ebe89ac095bcc5903c4dd8f72b367286"
SHA256 = "0575cb64845e6b9a10db9bcb74d5ac32b326b8dc90352671d345e2ee3d0126a2"
MODEL_ID = "ecapa-voxceleb-" + SHA256[:16] + "-v1"
URL = f"https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/resolve/{REVISION}/embedding_model.ckpt"


def verify(root):
    file = root / "voice/embedding_model.ckpt"
    with file.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != SHA256:
            raise RuntimeError("Speaker encoder integrity check failed")
    return file


def prepare():
    file = settings.model_root / "voice/embedding_model.ckpt"
    file.parent.mkdir(parents=True, exist_ok=True)
    try:
        verify(settings.model_root)
    except (OSError, RuntimeError):
        pending = file.with_suffix(".pending")
        urllib.request.urlretrieve(URL, pending)
        with pending.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != SHA256:
                pending.unlink(missing_ok=True)
                raise RuntimeError("Speaker encoder download hash mismatch")
        pending.replace(file)
    lock = dict(model_id=MODEL_ID, revision=REVISION, sha256=SHA256, url=URL,
                license="Apache-2.0", bytes=file.stat().st_size, dimensions=192, sample_rate=16000)
    (file.parent/"model-lock.json").write_text(json.dumps(lock, indent=2), encoding="utf-8")
    print("Speaker encoder verified: " + MODEL_ID)


if __name__ == "__main__":
    prepare()
