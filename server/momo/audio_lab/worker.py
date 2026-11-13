"""One experiment per process. Credentials arrive only over stdin, never in job files."""
import asyncio
from datetime import datetime, timezone
import gc
import json
import os
from pathlib import Path
import sys
import threading
import time

import httpx
from ..config import settings

from . import stages
from .models import AudioConfig, STAGES, model_root
from .limits import MAX_VIDEO_SECONDS
from .topic_windows import windows as topic_windows, stitch
from ..highlights.common import timestamp
from ..highlights.selection import group_events
from ..highlights.topics import (SYSTEM, PARTITION_SCHEMA, PROMPT_VERSION, context_content,
                                 boundary_for_event, select_topics, local_model, http_json, validate_partition)


def write_json(path, data):
    temporary = path.with_suffix(".pending")
    temporary.write_text(json.dumps(data, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    # On Windows, a concurrent status read can briefly deny replacing the file.
    for attempt in range(21):
        try:
            temporary.replace(path)
            return
        except PermissionError:
            if attempt == 20:
                raise
            time.sleep(.025)


def partition(utterances, model, config, directory, credentials, root, report=lambda value: None):
    if not utterances:
        return [], {}
    windows = topic_windows(utterances)
    batches, requests = [], []
    execution = {"device": "api" if model.startswith("openai:") else settings.lab_device}
    if model == "qwen-local":
        with local_model(directory, cache_dir=root / "highlights", device=settings.lab_device, on_runtime=execution.update) as url:
            for index, window in enumerate(windows):
                report(index / len(windows))
                first, last = window.context[0]["id"], window.context[-1]["id"]
                guidance = (f"\n이번 입력의 원본 발화 ID 범위는 {first}~{last}입니다. "
                    f"topics[0].start_id는 반드시 {first}여야 합니다. ID를 1부터 다시 매기지 마세요. "
                    "이후 start_id는 입력에 있는 ID만 사용하며 중복 없이 증가해야 합니다.")
                correction = ""
                for attempt in range(2):
                    response = http_json(url + "/v1/chat/completions", {
                        "model": "local-topic-model", "messages": [{"role": "system", "content": SYSTEM + guidance + correction},
                            {"role": "user", "content": context_content(window.context)}],
                        "temperature": 0, "seed": 42, "max_tokens": config.max_output_tokens,
                        "chat_template_kwargs": {"enable_thinking": config.qwen_thinking},
                        "response_format": {"type": "json_object", "schema": PARTITION_SCHEMA}}, timeout=1800)
                    choice = response["choices"][0]
                    requests.append({"usage": response.get("usage", {}), "thinking": config.qwen_thinking,
                                     "window": index + 1, "attempt": attempt + 1})
                    if choice["finish_reason"] == "length":
                        raise ValueError("Qwen 출력 한도에 도달했습니다. 출력 토큰을 늘리거나 추론을 끄고 실행하세요.")
                    raw = choice["message"]["content"]
                    try:
                        parts = validate_partition(json.loads(raw), window.context)
                    except (ValueError, TypeError, KeyError, AttributeError) as exc:
                        # Preserve diagnostics; never invent or renumber model boundaries.
                        write_json(directory / f"topic-invalid-{index + 1}-{attempt + 1}.json",
                                   {"first_id": first, "last_id": last, "response": raw, "error": str(exc)})
                        if attempt:
                            raise ValueError(f"주제 구간 {index + 1}/{len(windows)}의 발화 경계 검증에 실패했습니다. "
                                             "Qwen 응답을 한 번 교정했지만 유효한 경계를 받지 못했습니다.") from exc
                        correction = f"\n이전 응답은 검증에 실패했습니다: {exc}. 전체 주제 목록을 다시 작성하세요."
                        continue
                    batches.append((window, parts))
                    break
    else:
        from ..topic_lab import extract_partition, openai_request

        async def call():
            async with httpx.AsyncClient(timeout=httpx.Timeout(300, connect=20)) as client:
                for index, window in enumerate(windows):
                    report(index / len(windows))
                    payload = {"model": model[7:], "instructions": SYSTEM, "input": context_content(window.context),
                               "store": False, "max_output_tokens": config.max_output_tokens,
                               "text": {"format": {"type": "json_schema", "name": "conversation_topics",
                                                    "strict": True, "schema": PARTITION_SCHEMA}}}
                    data, request_id = await openai_request(client, credentials, "POST", "responses", payload)
                    _, parts = extract_partition(data, window.context)
                    batches.append((window, parts))
                    requests.append({"usage": data.get("usage", {}), "request_id": request_id, "model": data.get("model")})
        asyncio.run(call())
    report(1)
    return stitch(utterances, batches), {"analysis_windows": len(windows), "requests": requests, "execution": execution,
        "note": f"전체 전사를 {len(windows)}개 문맥 구간으로 분석했습니다. 구간 앞뒤 발화를 겹쳐 읽고 원본 발화 ID로 경계를 연결합니다."}


def export_clips(source, directory, clips, utterances):
    import subprocess
    from ..config import settings
    if not clips:
        return
    # Ask only for numeric stream data: filenames/tags may contain non-ASCII text.
    probe = subprocess.run([settings.ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
                            "-select_streams", "v", "-show_entries", "stream=index:stream_disposition=attached_pic",
                            "-of", "json", str(source)], check=True, timeout=30,
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    video_stream = next((stream["index"] for stream in json.loads(probe.stdout)["streams"]
                         if not stream.get("disposition", {}).get("attached_pic")), None)
    for i, clip in enumerate(clips, 1):
        audio, subtitle = f"clip_{i}.m4a", f"clip_{i}.srt"
        subprocess.run([settings.ffmpeg, "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe",
                        "-ss", str(clip["startMs"] / 1000), "-i", str(source), "-t", str(clip["durationMs"] / 1000),
                        "-map", "0:a:0", "-vn", "-map_metadata", "-1", "-c:a", "aac", "-b:a", "128k",
                        "-movflags", "+faststart", str(directory / audio)], check=True, timeout=max(120, clip["durationMs"] / 1000),
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        captions = []
        for row in utterances:
            start, end = max(clip["startMs"], row["startMs"]), min(clip["endMs"], row["endMs"])
            if end > start:
                captions.append(f"{len(captions) + 1}\n{timestamp(start - clip['startMs'], ',')} --> {timestamp(end - clip['startMs'], ',')}\n{row['text']}\n")
        (directory / subtitle).write_text("\n".join(captions), encoding="utf-8")
        clip.update(audio_file=audio, subtitle_file=subtitle)
        clip.pop("source_video_file", None)
        if video_stream is not None:
            video = f"clip_{i}.mp4"
            # Re-encode for accurate boundaries between keyframes and browser playback.
            subprocess.run([settings.ffmpeg, "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe",
                            "-ss", str(clip["startMs"] / 1000), "-i", str(source), "-t", str(clip["durationMs"] / 1000),
                            "-map", f"0:{video_stream}", "-map", "0:a:0", "-map_metadata", "-1", "-sn", "-dn",
                            "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-c:v", "libx264", "-preset", "fast",
                            "-crf", "20", "-pix_fmt", "yuv420p", "-threads", "2", "-c:a", "aac", "-b:a", "128k",
                            "-movflags", "+faststart", str(directory / video)], check=True, timeout=max(600, clip["durationMs"] / 1000 * 3),
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            clip["source_video_file"] = video


def run(payload):
    from ..topic_lab import Connection
    config = AudioConfig.model_validate(payload["config"])
    if config.pipeline == "segments":
        from .segment_worker import run as run_segments
        return run_segments(payload)
    credentials = Connection.model_validate(payload["credentials"]) if payload.get("credentials") else None
    directory, source = Path(payload["directory"]), Path(payload["source"])
    root = model_root()
    started = time.perf_counter()
    selected = config.stages()
    order = ["vad", "laughter", "diarization", "stt", "voice", "topics"] if config.pipeline == "app" else list(STAGES)
    status = {"state": "running", "stage": "decoding", "requested_mode": config.mode, "stages": [
        {"id": key, "label": STAGES[key], "model": getattr(config, key), "state": "pending" if key in selected else "skipped"}
        for key in order]}

    def progress():
        status["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        write_json(directory / "progress.json", status)

    last_report = 0
    def report(fraction):
        nonlocal last_report
        now = time.monotonic()
        if fraction not in (0, 1) and now - last_report < 1:
            return
        last_report = now
        status["stage_progress"] = {"stage": status["stage"], "percent": round(100 * fraction, 1)}
        progress()

    result = {"id": payload["id"], "created_at": datetime.now(timezone.utc).isoformat(),
              "config": config.model_dump(), "requested_mode": config.mode, "executed_stages": selected,
              "input": payload["input"], "stages": {}, "topics": [], "clips": [], "utterances": [],
              "prompt_version": PROMPT_VERSION, "analysis_policy": "app-backend-v1" if config.pipeline == "app" else "model-experiment-v1"}
    progress()
    write_json(directory / "checkpoint.json", result)
    pcm = None
    try:
        metadata = {}
        pcm = stages.decode(source, directory, metadata)
        result["input"].update(metadata)
        duration = round(len(pcm) / 16)
        result["input"]["durationMs"] = duration
        result["input"]["sample_rate"] = 16000
        if payload.get("scene") and duration > MAX_VIDEO_SECONDS * 1000:
            raise ValueError("긴 원본은 먼저 ‘원본에서 하이라이트 추출’을 실행해 주세요. 추출한 10분 이하 클립에서 ‘이 클립으로 영상 만들기’를 선택할 수 있습니다.")
        if "diarization" in selected and config.diarization.startswith("openai:") and duration > MAX_VIDEO_SECONDS * 1000:
            raise ValueError("긴 원본의 OpenAI 화자 분리는 아직 지원하지 않습니다. 원본 하이라이트를 먼저 추출하거나 로컬 화자 모델을 선택해 주세요.")
        if payload.get("reuse"):
            original = json.loads(Path(payload["reuse"]).read_text(encoding="utf-8"))
            result.update({key: original[key] for key in ("stages", "topics", "clips", "utterances")})
            result["reused_analysis_id"] = original.get("reused_analysis_id", original["id"])
            result["analysis_elapsed_ms"] = sum(stage["elapsed_ms"] for stage in original["stages"].values())
            for entry in status["stages"]:
                entry.update(state="complete", elapsed_ms=0, reused=True)
            if config.pipeline == "app":
                from ..contracts import AnalysisResult, MotionTracks
                previous = Path(payload["reuse"]).parent
                analysis = AnalysisResult.model_validate_json((previous / "app-result.json").read_text(encoding="utf-8"))
                tracks = MotionTracks.model_validate_json((previous / "app-tracks.json").read_text(encoding="utf-8"))
                analysis.job_id = tracks.job_id = payload["id"]
                write_json(directory / "app-result.json", analysis.model_dump())
                write_json(directory / "app-tracks.json", tracks.model_dump())
        app_pipeline = None
        if config.pipeline == "app" and not payload.get("reuse"):
            from .app_pipeline import AppPipeline
            app_pipeline = AppPipeline(pcm, root, config, payload["id"], result["input"])
        for entry in status["stages"]:
            key = entry["id"]
            if key not in selected or payload.get("reuse"):
                continue
            entry["state"], status["stage"] = "running", key
            status.pop("stage_progress", None)
            progress()
            start = time.perf_counter()
            if app_pipeline is not None and key != "topics":
                data = app_pipeline.run(key, result["stages"], report)
            elif key == "vad":
                data = stages.vad(pcm, config.vad, root, report)
            elif key == "laughter":
                data = stages.laughter(pcm, config.laughter, root, report)
                data["events"] = group_events(data["windows"], config.threshold)
            elif key == "stt":
                data = stages.transcribe(pcm, config.stt, root, result["stages"]["vad"]["data"], credentials, report)
            elif key == "diarization":
                data = stages.diarize(pcm, config.diarization, root, result["stages"]["vad"]["data"], credentials, config.speaker_count, report)
            elif key == "voice":
                data = stages.voices(pcm, config.voice, root, result["stages"]["diarization"]["data"], result["stages"]["vad"]["data"])
            else:
                utterances = result["utterances"]
                parts, metadata = partition(utterances, config.topics, config, directory, credentials, root, report)
                by_id = {u["id"]: u for u in utterances}
                topics = [dict(p, startMs=by_id[p["start_id"]]["startMs"], endMs=by_id[p["end_id"]]["endMs"])
                          for p in parts]
                events = result["stages"]["laughter"]["data"]["events"]
                decisions = [dict(boundary_for_event(parts, e, utterances), eventId=e["eventId"])
                             for e in events] if parts else []
                clips = select_topics(decisions, utterances, events, duration, config.max_clips) if decisions else []
                for clip in clips:
                    clip["boundaryMethod"] = f"{config.topics} + {config.stt} + {config.laughter}"
                result.update(topics=topics, clips=clips)
                data = {"topics": topics, "clip_count": len(clips), **metadata}
            if key == "stt":
                result["utterances"] = data["utterances"]
                write_json(directory / "transcript.json", {"utterances": result["utterances"], "durationMs": duration})
                (directory / "transcript.srt").write_text("\n".join(
                    f"{i}\n{timestamp(u['startMs'], ',')} --> {timestamp(u['endMs'], ',')}\n{u['text']}\n"
                    for i, u in enumerate(result["utterances"], 1)), encoding="utf-8")
                result["artifacts"] = ["transcript.json", "transcript.srt"]
            entry["elapsed_ms"] = round((time.perf_counter() - start) * 1000)
            status.pop("stage_progress", None)
            entry["state"] = "complete"
            result["stages"][key] = {**entry, "data": data}
            # Retain finished stages even if a native inference library aborts later.
            write_json(directory / "checkpoint.json", result)
            progress()
            gc.collect()
        if app_pipeline is not None and "diarization" in selected:
            analysis, tracks = app_pipeline.assembled()
            write_json(directory / "app-result.json", analysis.model_dump())
            write_json(directory / "app-tracks.json", tracks.model_dump())
            for utterance, caption in zip(result["utterances"], analysis.captions):
                utterance["speakers"] = caption.speaker_ids
                utterance["requires_review"] = caption.requires_review
        elif config.pipeline == "experiment":
            # Alternative adapters expose segments instead of the app's probability tracks.
            segments = result["stages"].get("diarization", {}).get("data", {}).get("segments", [])
            for utterance in result["utterances"]:
                utterance["speakers"] = sorted({s["speaker"] for s in segments
                    if utterance["startMs"] < s["endMs"] and s["startMs"] < utterance["endMs"]})
        status["stage"] = "exporting"
        progress()
        if "stt" in selected or payload.get("reuse"):
            write_json(directory / "transcript.json", {"utterances": result["utterances"], "durationMs": duration})
            (directory / "transcript.srt").write_text("\n".join(
                f"{i}\n{timestamp(u['startMs'], ',')} --> {timestamp(u['endMs'], ',')}\n{u['text']}\n"
                for i, u in enumerate(result["utterances"], 1)), encoding="utf-8")
        export_clips(source, directory, result["clips"], result["utterances"])
        if payload.get("scene"):
            from ..video_lab.render import make_videos, bundle
            entry = {"id": "video", "label": "캐릭터 영상 제작", "state": "running"}
            status["stages"].append(entry)
            status["stage"] = "video"
            def video_progress(index, count, frame, total):
                status["video_progress"] = {"video": index+1, "count": count, "frame": frame, "frames": total,
                                            "percent": round(100*(index+frame/total)/max(count,1))}
                progress()
            video = make_videos(result, pcm, source, directory, payload["scene"], video_progress)
            entry.update(state="complete", elapsed_ms=video["render_ms"])
        result["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        result["artifacts"] = [p.name for p in directory.iterdir() if p.name.startswith(("clip_", "transcript.", "video_", "app-"))]
        if payload.get("scene"):
            status["stage"] = "packaging"
            progress()
            bundle(directory, source, result, payload["scene"])
            result["artifacts"].append("app-bundle.zip")
        result["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        write_json(directory / "result.json", result)
        status["state"], status["stage"] = "complete", "complete"
        progress()
    except Exception as exc:
        from fastapi import HTTPException
        from ..errors import ServiceError
        if isinstance(exc, HTTPException):
            message = str(exc.detail)
        elif isinstance(exc, ServiceError):
            message = exc.message
        elif isinstance(exc, ValueError):
            message = str(exc)[:500]
        else:
            # Do not leak exception reprs that can contain request headers or private paths.
            phase = "영상 제작" if status["stage"] in {"video", "packaging"} else "원본 하이라이트 저장" if status["stage"] == "exporting" else "모델 실행"
            message = f"{phase} 실패 ({type(exc).__name__}). 파일·실행 환경을 확인하세요."
        import traceback
        print(type(exc).__name__ + ": " + " > ".join(f"{Path(frame.filename).name}:{frame.lineno}({frame.name})" for frame in traceback.extract_tb(exc.__traceback__)), file=sys.stderr, flush=True)
        status["state"], status["error"] = "failed", message
        for entry in status["stages"]:
            if entry["state"] == "running":
                entry["state"] = "failed"
        result["partial"] = True
        result["failed_stage"] = status["stage"]
        result["stage_status"] = status["stages"]
        result["error"] = message
        result["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        write_json(directory / "partial.json", result)
        progress()
        raise
    finally:
        if getattr(pcm, "_mmap", None) is not None:
            pcm._mmap.close()


def parent_guard(pid, created):
    import psutil
    while True:
        try:
            if abs(psutil.Process(pid).create_time() - created) > .01:
                os._exit(1)
        except psutil.NoSuchProcess:
            os._exit(1)
        time.sleep(1)


if __name__ == "__main__":
    payload = json.loads(sys.stdin.buffer.read())
    threading.Thread(target=parent_guard, args=(payload["parent_pid"], payload["parent_created"]), daemon=True).start()
    try:
        run(payload)
    except Exception:
        # The sanitized explanation has already been written to progress.json.
        sys.exit(1)
