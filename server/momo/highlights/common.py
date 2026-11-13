from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
CREATE_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def log(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    temporary.replace(path)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def signature(value) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def run(args: list[str], *, cwd: Path | None = None, logfile: Path | None = None) -> str:
    result = subprocess.run([str(a) for a in args], cwd=cwd, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, creationflags=CREATE_FLAGS)
    stderr = result.stderr.decode("utf-8", errors="replace")
    if logfile:
        logfile.write_text(stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} failed ({result.returncode}): {stderr[-3000:]}")
    return result.stdout.decode("utf-8", errors="replace")


def probe(path: Path) -> dict:
    return json.loads(run(["ffprobe", "-v", "error", "-show_format", "-show_streams",
        "-show_chapters", "-of", "json", str(path)]))


def timestamp(ms: int, separator=".") -> str:
    hours, rest = divmod(max(0, round(ms)), 3_600_000)
    minutes, rest = divmod(rest, 60_000)
    seconds, millis = divmod(rest, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}{separator}{millis:03d}"


def cached(path: Path, fingerprint: str):
    if path.exists():
        data = read_json(path)
        if data.get("fingerprint") == fingerprint:
            log(f"캐시 사용: {path.name}")
            return data["data"]
    return None


def save_cache(path: Path, fingerprint: str, data) -> None:
    write_json(path, {"fingerprint": fingerprint, "data": data})
