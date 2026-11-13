from __future__ import annotations

from datetime import datetime
from pathlib import Path
import shutil

from . import __version__
from .audio import group_events, laughter_windows
from .common import ROOT, log, probe, read_json, sha256, write_json
from .models import MODEL_CACHE, TOPIC_REPO, TOPIC_REVISION
from .speech import save_transcripts, transcribe
from .topics import infer_topics
from .video import export_audio, find_font, render_videos


def execute(args) -> dict:
    source = Path(args.input).expanduser().resolve()
    if not source.is_file():
        raise ValueError(f"입력 파일이 없습니다: {source}")
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            raise ValueError(f"{tool}가 PATH에 없습니다. FFmpeg를 설치해 주세요.")
    font = find_font(args.font)
    metadata = probe(source)
    if not any(stream.get("codec_type") == "audio" for stream in metadata["streams"]):
        raise ValueError("입력 파일에 오디오 트랙이 없습니다.")
    duration = round(float(metadata["format"]["duration"]) * 1000)
    if duration <= 0:
        raise ValueError("입력 오디오가 비어 있습니다.")
    source_hash = sha256(source)
    output = Path(args.output).expanduser().resolve() if args.output else (
        ROOT / "build/reports/highlight_pipeline" / f"{source.stem}_{datetime.now():%Y%m%d_%H%M%S}")
    if source.is_relative_to(output):
        raise ValueError("입력 파일을 포함하는 폴더를 출력 폴더로 사용할 수 없습니다.")
    config = {"pipelineVersion": __version__, "sourcePath": str(source), "sourceSha256": source_hash,
        "threshold": args.threshold, "maxClips": args.max_clips, "sttModel": args.stt_model,
        "language": args.language, "topicModel": TOPIC_REPO, "topicRevision": TOPIC_REVISION,
        "device": args.device, "encoder": args.encoder, "font": str(font), "verify": not args.no_verify}
    record = output / "run.json"
    if output.exists() and any(output.iterdir()):
        if not record.is_file() or read_json(record).get("config") != config:
            raise ValueError("출력 폴더가 다른 작업의 파일을 포함합니다. 새 출력 폴더를 지정해 주세요.")
    output.mkdir(parents=True, exist_ok=True)
    write_json(record, {"config": config, "status": "running"})
    write_json(output / "source_metadata.json", metadata)
    cache_dir = Path(args.model_cache).expanduser().resolve() if args.model_cache else MODEL_CACHE
    log(f"출력 폴더: {output}")
    try:
        windows = laughter_windows(source, source_hash, output)
        events = group_events(windows, args.threshold)
        write_json(output / "laughter_events.json", {"threshold": args.threshold, "events": events})
        log(f"웃음 후보 {len(events)}곳 · 기준 {args.threshold}")
        speech = transcribe(source, source_hash, duration, output, model_name=args.stt_model,
            language=args.language, device=args.device, cache_dir=cache_dir)
        save_transcripts(speech, output)
        topics = infer_topics(speech["utterances"], events, source_hash, duration, output,
            max_clips=args.max_clips, cache_dir=cache_dir, device=args.device)
        video = None
        if topics:
            export_audio(source, topics, speech["utterances"], output, font)
            video = render_videos(topics, speech["utterances"], output, font, args.encoder, not args.no_verify)
        if sha256(source) != source_hash:
            raise RuntimeError("처리 중 입력 파일이 변경되었습니다. 결과를 원본과 다시 확인해야 합니다.")
        result = {"schemaVersion": 1, "sourcePath": str(source), "sourceSha256": source_hash,
            "sourceUnchanged": True, "sourceDurationMs": duration, "laughterThreshold": args.threshold,
            "windowCount": len(windows), "laughterEventCount": len(events), "selectedCount": len(topics),
            "boundaryMethod": "local Qwen model chooses STT utterance IDs; no fixed-second context padding",
            "topics": topics, "video": video, "outputDirectory": str(output),
            "emptyReason": None if topics else ("no_laughter_candidates" if not events else "no_transcribed_speech")}
        write_json(output / "result.json", result)
        write_json(record, {"config": config, "status": "complete"})
        if video:
            log(f"완료: {output / video['file']}")
        else:
            log("조건에 맞는 대화가 없어 영상을 생성하지 않았습니다. result.json과 전사 결과를 확인하세요.")
        return result
    except BaseException as error:
        write_json(record, {"config": config, "status": "failed", "error": str(error)})
        raise
