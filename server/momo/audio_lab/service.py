import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse
import psutil
from pydantic import BaseModel, Field

from .models import AudioConfig, SpeakerReview, catalog
from .limits import DISK_RESERVE, upload_limit_message
from ..config import settings
from ..video_lab.scene import Scene, builtins, resolve_scene

@dataclass
class Upload:
    owner: str
    path: Path
    name: str
    sha256: str


@dataclass
class Job:
    owner: str
    directory: Path
    process: subprocess.Popen
    created: float
    started: float = field(default_factory=time.monotonic)
    cancelled: bool = False
    upload: Upload | None = None


class SegmentAction(BaseModel):
    segment_id: str = Field(pattern=r"^G[0-9]{6}_[01]$")
    speaker_id: str | None = Field(default=None, pattern=r"^S[1-8]$")
    rerun: bool = False


class JobRequest(BaseModel):
    upload_id: str | None = None
    reuse_job_id: str | None = None
    config: AudioConfig = AudioConfig()
    scene: Scene | None = None
    segment_action: SegmentAction | None = None
    resume_segments: bool = False
    review_speakers: SpeakerReview | None = None


class AudioLab:
    def __init__(self):
        parent = settings.lab_temp_root
        if parent is None and settings.model_root.exists():
            parent = settings.model_root.resolve().parent / "lab-temp"
        if parent is not None:
            parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="momo-audio-lab-", dir=parent)
        self.root = Path(self.temp.name)
        self.uploads: dict[str, Upload] = {}
        self.jobs: dict[str, Job] = {}
        self.uploading = False
        self.starting = False
        self.assets = builtins()

    def owned(self, mapping, identity, owner):
        item = mapping.get(identity)
        if not item or item.owner != owner:
            raise HTTPException(404, "이 세션의 파일·실험을 찾을 수 없습니다.")
        return item

    def stop(self, job):
        if job.process.poll() is not None:
            return
        try:
            process = psutil.Process(job.process.pid)
            if abs(process.create_time() - job.created) > .01:
                return
            children = process.children(recursive=True)
            for child in reversed(children):
                try:
                    child.kill()
                except psutil.NoSuchProcess:
                    pass
            process.kill()
            job.process.wait(timeout=10)
            psutil.wait_procs(children, timeout=5)
        except psutil.NoSuchProcess:
            pass

    def close(self):
        for job in self.jobs.values():
            self.stop(job)
        self.temp.cleanup()

    def status(self, identity, job):
        path = job.directory / "progress.json"
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {"state": "running", "stage": "starting", "stages": []}
        if job.cancelled:
            data.update(state="cancelled", error="사용자가 분석을 취소했습니다. 이미 전송한 API 요청은 과금될 수 있습니다.")
        elif (exit_code := job.process.poll()) is not None and data["state"] not in {"complete", "failed"}:
            stage = next((s for s in data.get("stages", []) if s["id"] == data.get("stage")), {})
            label = stage.get("label", "분석")
            code = f"0x{exit_code & 0xFFFFFFFF:08X}"
            message = f"{label} 단계에서 분석 프로세스가 비정상 종료되었습니다 (종료 코드 {code}). 하이라이트 추출이 완료되지 않았습니다."
            data.update(state="failed", error=message, exit_code=exit_code)
            for entry in data.get("stages", []):
                if entry["state"] == "running":
                    entry["state"] = "failed"
            checkpoint = job.directory / "checkpoint.json"
            try:
                partial = json.loads(checkpoint.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                partial = {"id": identity, "created_at": datetime.fromtimestamp(job.created, timezone.utc).isoformat(),
                    "input": {"name": job.upload.name if job.upload else "원본 파일"}, "config": {},
                    "requested_mode": data.get("requested_mode", "topics"), "stages": {},
                    "topics": [], "clips": [], "utterances": [], "artifacts": []}
            partial.update(partial=True, error=message, failed_stage=data.get("stage"),
                           stage_status=data.get("stages", []), elapsed_ms=data.get("elapsed_ms", 0))
            # The process is already dead; preserve a downloadable partial result.
            (job.directory / "partial.json").write_text(json.dumps(partial, ensure_ascii=False), encoding="utf-8")
        data.update(id=identity)
        if data["state"] == "running":
            data["elapsed_ms"] = round((time.monotonic() - job.started) * 1000)
        if data["state"] in {"complete", "failed"}:
            result = job.directory / ("result.json" if data["state"] == "complete" else "partial.json")
            if result.is_file():
                data["result"] = json.loads(result.read_text(encoding="utf-8"))
        return data


def install_routes(app, lab, session_for, key_for, claude_key_for=None):
    @app.get("/api/audio/catalog")
    async def models(request: Request):
        session_for(request)
        return catalog()

    @app.post("/api/audio/uploads")
    async def upload(request: Request, filename: str = "recording.wav"):
        session = session_for(request)
        if lab.uploading:
            raise HTTPException(409, "다른 파일을 업로드 중입니다.")
        if len(lab.uploads) >= 12:
            raise HTTPException(409, "임시 파일 12개 한도입니다. 테스트 서버를 다시 시작하면 임시 자료가 정리됩니다.")
        if len(filename) > 250 or not filename.strip():
            raise HTTPException(422, "파일 이름을 확인해 주세요.")
        if any(job.process.poll() is None for job in lab.jobs.values()):
            raise HTTPException(409, "진행 중인 분석이 끝난 뒤 새 파일을 업로드해 주세요.")
        identity = secrets.token_hex(16)
        path = lab.root / (identity + ".media")
        count, digest = 0, hashlib.sha256()
        available = shutil.disk_usage(lab.root).free - DISK_RESERVE
        declared = int(request.headers.get("content-length", "0"))
        if declared > available:
            raise HTTPException(507, "원본 파일을 저장할 공간이 부족합니다. 실험 저장 드라이브의 공간을 확보해 주세요.")
        lab.uploading = True
        try:
            with path.open("wb") as stream:
                async for chunk in request.stream():
                    count += len(chunk)
                    if count > settings.lab_max_audio_bytes:
                        raise HTTPException(413, upload_limit_message())
                    if count > available:
                        raise HTTPException(507, "원본 파일을 저장할 공간이 부족합니다.")
                    stream.write(chunk)
                    digest.update(chunk)
            if count == 0:
                raise HTTPException(422, "파일이 비어 있습니다.")
            lab.uploads[identity] = Upload(session.csrf, path, Path(filename.replace("\\", "/")).name, digest.hexdigest())
            return {"upload_id": identity, "name": lab.uploads[identity].name, "bytes": count, "sha256": digest.hexdigest()}
        except BaseException:
            path.unlink(missing_ok=True)
            raise
        finally:
            lab.uploading = False

    @app.post("/api/audio/jobs")
    async def start(body: JobRequest, request: Request):
        session = session_for(request)
        if bool(body.upload_id) == bool(body.reuse_job_id):
            raise HTTPException(422, "음성 파일 또는 재사용할 분석 하나를 지정하세요.")
        reuse = None
        if body.review_speakers and (not body.reuse_job_id or body.segment_action or body.resume_segments):
            raise HTTPException(422, "화자 재검토에는 완료된 분석 하나만 지정하세요.")
        if body.segment_action and not body.reuse_job_id:
            raise HTTPException(422, "구간 작업에는 기존 분석 ID가 필요합니다.")
        if body.resume_segments and (not body.reuse_job_id or body.segment_action):
            raise HTTPException(422, "구간 이어하기에는 기존 분석 하나만 지정하세요.")
        if body.reuse_job_id:
            previous = lab.owned(lab.jobs, body.reuse_job_id, session.csrf)
            previous_status = lab.status(body.reuse_job_id, previous)
            resumable = body.resume_segments and previous_status["state"] == "failed" and (previous.directory / "segment-plan.json").is_file()
            if (previous_status["state"] != "complete" and not resumable) or not previous.upload:
                raise HTTPException(409, "완료된 분석만 다시 사용할 수 있습니다.")
            reuse = previous.directory / ("partial.json" if resumable else "result.json")
            previous_config = json.loads(reuse.read_text(encoding="utf-8"))["config"]
            previous_config.setdefault("pipeline", "experiment")
            body.config = AudioConfig.model_validate(previous_config)
            if body.review_speakers:
                if body.config.pipeline != "segments":
                    raise HTTPException(422, "캐릭터별 구간 분석 결과에서 화자를 재검토할 수 있습니다.")
                body.config.speaker_review = body.review_speakers
            if body.resume_segments and body.config.pipeline != "segments":
                raise HTTPException(422, "캐릭터별 구간 분석 결과만 이어할 수 있습니다.")
            if body.segment_action:
                if body.config.pipeline != "segments":
                    raise HTTPException(422, "캐릭터별 구간 분석 결과에서만 구간을 수정할 수 있습니다.")
                document = json.loads((previous.directory / "character-result.json").read_text(encoding="utf-8"))
                selected = next((row for row in document["segments"] if row["id"] == body.segment_action.segment_id), None)
                if selected is None:
                    raise HTTPException(404, "구간을 찾을 수 없습니다.")
                speaker = body.segment_action.speaker_id
                if speaker and speaker not in {s["speaker_id"] for s in document["speakers"]}:
                    raise HTTPException(422, "현재 녹음에 없는 화자입니다.")
            upload = previous.upload
        else:
            upload = lab.owned(lab.uploads, body.upload_id, session.csrf)
        if body.scene and body.config.mode != "all":
            raise HTTPException(422, "캐릭터 영상 제작은 전체 6개 기능 분석을 사용합니다.")
        try:
            scene = resolve_scene(body.scene, lab.assets, session.csrf) if body.scene else None
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        if lab.starting or session.busy or any(job.process.poll() is None for job in lab.jobs.values()):
            raise HTTPException(409, "이미 분석 중입니다. 완료 또는 취소 후 실행해 주세요.")
        if len(lab.jobs) >= 30:
            raise HTTPException(409, "실험 30개 한도입니다. 결과를 내려받고 테스트 서버를 다시 시작해 주세요.")
        needs_models = not reuse or body.resume_segments or bool(body.segment_action and body.segment_action.rerun)
        credentials = key_for(session) if needs_models and body.config.requires_key() else None
        needs_review = body.config.pipeline == "segments" and (needs_models or body.review_speakers is not None)
        claude_key = claude_key_for(session) if needs_review and body.config.speaker_review.mode == "claude" else None
        identity = secrets.token_hex(16)
        directory = lab.root / identity
        directory.mkdir()
        payload = {"id": identity, "directory": str(directory), "source": str(upload.path),
                   "input": {"name": upload.name, "sha256": upload.sha256}, "config": body.config.model_dump(),
                   "parent_pid": os.getpid(), "parent_created": psutil.Process().create_time()}
        if scene:
            payload["scene"] = scene
        if reuse:
            payload["reuse"] = str(reuse)
        if body.segment_action:
            payload["segment_action"] = body.segment_action.model_dump()
        if body.resume_segments:
            payload["resume_segments"] = True
        if body.review_speakers:
            payload["review_speakers"] = True
        if claude_key:
            payload["claude_key"] = claude_key
        if credentials:
            payload["credentials"] = {"api_key": credentials.api_key.get_secret_value(),
                                      "project_id": credentials.project_id, "organization_id": credentials.organization_id}
        server = Path(__file__).resolve().parents[2]
        overlay_python = server / ".lab-venv/Scripts/python.exe"
        python = str(overlay_python) if overlay_python.is_file() else sys.executable
        environment = {k: v for k, v in os.environ.items() if not k.startswith(("OPENAI_", "ANTHROPIC_"))}
        environment.update(PYTHONUTF8="1", TF_CPP_MIN_LOG_LEVEL="2", HF_HUB_DISABLE_PROGRESS_BARS="1")
        lab.starting = True
        try:
            with (directory / "worker.log").open("wb") as log:
                process = subprocess.Popen([python, "-m", "momo.audio_lab.worker"], cwd=server,
                    stdin=subprocess.PIPE, stdout=log, stderr=log, env=environment,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            job = Job(session.csrf, directory, process, psutil.Process(process.pid).create_time(), upload=upload)
            lab.jobs[identity] = job
            process.stdin.write(json.dumps(payload, ensure_ascii=False).encode("utf-8"))
            process.stdin.close()
        finally:
            lab.starting = False
        return {"id": identity, "state": "running"}

    @app.get("/api/audio/jobs")
    async def listing(request: Request):
        owner = session_for(request).csrf
        return {"jobs": [lab.status(identity, job) for identity, job in lab.jobs.items() if job.owner == owner]}

    @app.get("/api/audio/jobs/{identity}")
    async def status(identity: str, request: Request):
        owner = session_for(request).csrf
        return lab.status(identity, lab.owned(lab.jobs, identity, owner))

    @app.delete("/api/audio/jobs/{identity}")
    async def cancel(identity: str, request: Request):
        owner = session_for(request).csrf
        job = lab.owned(lab.jobs, identity, owner)
        if job.process.poll() is None:
            job.cancelled = True
            await asyncio.to_thread(lab.stop, job)
        return lab.status(identity, job)

    @app.get("/api/audio/jobs/{identity}/files/{filename}")
    async def artifact(identity: str, filename: str, request: Request):
        owner = session_for(request, csrf=False).csrf
        job = lab.owned(lab.jobs, identity, owner)
        import re
        if not re.fullmatch(r"(?:result\.json|transcript\.(?:json|srt)|clip_[0-9]+\.(?:m4a|srt|mp4)|video_(?:full|clip_[0-9]+)\.(?:mp4|png)|(?:app|character)-(?:result|tracks)\.json|app-bundle\.zip|(?:scene|preset|model-config)\.json)", filename):
            raise HTTPException(404, "파일을 찾을 수 없습니다.")
        path = job.directory / filename
        if not path.is_file() or (filename.startswith(("clip_", "video_", "app-")) and not (job.directory / "result.json").is_file()):
            raise HTTPException(404, "아직 생성되지 않은 파일입니다.")
        import mimetypes
        media_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        return FileResponse(path, media_type=media_type, filename=filename,
                            content_disposition_type="inline" if filename.endswith((".m4a", ".mp4", ".png")) else "attachment")

    @app.get("/api/audio/jobs/{identity}/segments/{segment_id}/audio")
    async def segment_audio(identity: str, segment_id: str, request: Request, original: bool = False):
        owner = session_for(request, csrf=False).csrf
        job = lab.owned(lab.jobs, identity, owner)
        import re
        if not re.fullmatch(r"G[0-9]{6}_[01]", segment_id) or not (job.directory / "character-result.json").is_file():
            raise HTTPException(404, "구간을 찾을 수 없습니다.")
        document = json.loads((job.directory / "character-result.json").read_text(encoding="utf-8"))
        segment = next((row for row in document["segments"] if row["id"] == segment_id), None)
        if segment is None:
            raise HTTPException(404, "구간을 찾을 수 없습니다.")
        path = job.directory / f"listen_{segment_id}_{int(original)}.wav"
        def create_preview():
            import numpy as np
            from scipy.io import wavfile
            if path.is_file():
                return
            if not original and segment.get("audio_file"):
                # Stored stems contain context; preview only the owned core interval.
                rate, audio = wavfile.read(job.directory / segment["audio_file"])
                offset = segment["contextStartMs"]
            else:
                audio = np.memmap(job.directory / "audio.f32", dtype=np.float32, mode="r")
                rate, offset = 16000, 0
            try:
                first = round((segment["startMs"] - offset) * rate / 1000)
                end = round((segment["endMs"] - offset) * rate / 1000)
                # Unique temporary files avoid races between concurrent browser range requests.
                pending = path.with_name(path.stem + secrets.token_hex(4) + ".wav")
                wavfile.write(pending, rate, (np.clip(audio[first:end], -1, 1) * 32767).astype(np.int16))
                pending.replace(path)
            finally:
                if getattr(audio, "_mmap", None) is not None:
                    audio._mmap.close()
        await asyncio.to_thread(create_preview)
        return FileResponse(path, media_type="audio/wav", filename=path.name, content_disposition_type="inline")

    from ..video_lab.service import install_video_routes
    install_video_routes(app, lab, session_for)
