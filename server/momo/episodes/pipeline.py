import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

import psutil

from ..config import settings
from ..db import SessionLocal, Job, JobArtifact
from ..errors import ServiceError
from ..storage import storage


def stop(process):
    try:
        children = psutil.Process(process.pid).children(recursive=True)
        for child in reversed(children):
            try:
                child.kill()
            except psutil.NoSuchProcess:
                pass
        if process.poll() is None:
            process.kill()
        process.wait(timeout=15)
    except psutil.NoSuchProcess:
        pass


def run(job, source, directory, progress, check):
    import shutil
    from dotenv import dotenv_values
    server = Path(__file__).resolve().parents[2]
    payload = dict(id=job.id, task=job.options["task"], directory=str(directory), source=str(source),
        input=dict(name="recording.m4a", sha256=job.audio_sha256), config=job.options["audio_config"],
        profile_revision=job.options["profile_revision"], parent_pid=os.getpid(), parent_created=psutil.Process().create_time())
    if job.options["task"] == "episode_render":
        parent_id = job.options["analysis_job_id"]
        payload.update(analysis_job_id=parent_id, cast=job.options["cast"])
        with SessionLocal() as db:
            parent = db.get(Job, parent_id)
            if not parent or parent.owner_id != job.owner_id or parent.deleted_at:
                raise ServiceError("RESULT_EXPIRED", "영상에 사용할 분석 결과가 만료되었습니다.", 410)
            for name in ("lab-result.json", "character-result.json", "character-tracks.json", "app-result.json", "app-tracks.json"):
                item = db.get(JobArtifact, (parent_id, name))
                if item and storage.exists(item.storage_key):
                    shutil.copyfile(storage.get_file(item.storage_key), directory / name)
            if not (directory / "lab-result.json").is_file():
                raise ServiceError("RESULT_EXPIRED", "영상에 사용할 분석 결과가 만료되었습니다.", 410)
    else:
        credentials = {**dotenv_values(server / ".env"), **os.environ}
        config = job.options["audio_config"]
        if any(str(config.get(stage, "")).startswith("openai:") for stage in ("stt", "diarization", "topics")):
            key = credentials.get("OPENAI_API_KEY")
            if not key:
                raise ServiceError("PROVIDER_KEY_REQUIRED", "선택한 모델에 사용할 OpenAI API 키를 서버에 설정해 주세요.")
            payload["credentials"] = dict(api_key=key, project_id=credentials.get("OPENAI_PROJECT_ID") or "", organization_id=credentials.get("OPENAI_ORGANIZATION_ID") or "")
        if config.get("speaker_review", {}).get("mode") == "claude":
            key = credentials.get("ANTHROPIC_API_KEY")
            if not key:
                raise ServiceError("PROVIDER_KEY_REQUIRED", "Claude 화자 검토용 API 키를 서버에 설정해 주세요.")
            payload["claude_key"] = key
    overlay = server / (".lab-venv/Scripts/python.exe" if os.name == "nt" else ".lab-venv/bin/python")
    python = str(overlay) if overlay.is_file() else sys.executable
    environment = {k: v for k, v in os.environ.items() if not k.startswith(("OPENAI_", "ANTHROPIC_"))}
    environment.update(PYTHONUTF8="1", TF_CPP_MIN_LOG_LEVEL="2", HF_HUB_DISABLE_PROGRESS_BARS="1")
    with (directory / "worker.log").open("wb") as log:
        process = subprocess.Popen([python, "-m", "momo.episodes.runner"], cwd=server,
            stdin=subprocess.PIPE, stdout=log, stderr=log, env=environment,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            process.stdin.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            process.stdin.close()
            while process.poll() is None:
                check()
                try:
                    state = json.loads((directory / "progress.json").read_text(encoding="utf-8"))
                    stage = state.get("stage", "starting")
                    fraction = state.get("video_progress", state.get("stage_progress", {})).get("percent", 0) / 100
                    progress(stage, min(.99, max(0, fraction)))
                except (FileNotFoundError, PermissionError, ValueError):
                    pass
                time.sleep(1)
            check()
            if process.returncode:
                path = directory / "episode-error.json"
                message = json.loads(path.read_text(encoding="utf-8"))["message"] if path.is_file() else f"분석 프로세스가 종료되었습니다 (0x{process.returncode & 0xFFFFFFFF:08X}). 추론 환경을 확인해 주세요."
                raise ServiceError("EPISODE_FAILED", message)
        finally:
            stop(process)
    result = json.loads((directory / "episode-result.json").read_text(encoding="utf-8"))
    if result["audio_sha256"] != job.audio_sha256:
        raise ServiceError("HASH_MISMATCH", "제작 결과가 원본 녹음과 일치하지 않습니다.")
    names = [p.name for p in directory.iterdir() if re.fullmatch(
        r"(?:lab-result\.json|character-(?:result|tracks)\.json|app-(?:result|tracks)\.json|clip_[0-9]+\.(?:m4a|srt)|video_clip_[0-9]+\.(?:mp4|png)|captions_[0-9]+\.json)", p.name)]
    return result, [(name, directory / name) for name in names]
