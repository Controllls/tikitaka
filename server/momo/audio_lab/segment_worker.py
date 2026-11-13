"""Versioned character pipeline and independently cached segment jobs."""
from datetime import datetime, timezone
import gc
import json
from pathlib import Path
import time

import numpy as np

from .models import AudioConfig, STAGES, model_root
from .segment_core import POLICY, plan_segments, assemble_characters, ranges
from .segment_engine import SegmentEngine, anchors_from_speech, coarse_tracks, link_file, read_cached
from .limits import MAX_VIDEO_SECONDS


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def run(payload):
    from .worker import write_json, partition, export_clips
    from . import stages
    from ..highlights.common import timestamp
    from ..highlights.topics import boundary_for_event, select_topics, PROMPT_VERSION
    from ..topic_lab import Connection
    directory, source = Path(payload["directory"]), Path(payload["source"])
    config = AudioConfig.model_validate(payload["config"])
    root = model_root()
    started = time.perf_counter()
    status = dict(state="running", stage="decoding", requested_mode=config.mode, stages=[
        dict(id=key, label=STAGES[key] if key != "stt" else "구간별 자막·웃음·동작", model=getattr(config, key), state="pending")
        for key in config.stages()])
    result = dict(id=payload["id"], created_at=datetime.now(timezone.utc).isoformat(), config=config.model_dump(),
        requested_mode=config.mode, executed_stages=config.stages(), input=dict(payload["input"]),
        analysis_policy=POLICY, prompt_version=PROMPT_VERSION, stages={}, topics=[], clips=[], utterances=[])
    last_report = 0
    def progress(fraction=None):
        nonlocal last_report
        now = time.monotonic()
        if fraction not in (None, 0, 1) and now - last_report < .5:
            return
        last_report = now
        status["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        if fraction is not None:
            status["stage_progress"] = dict(stage=status["stage"], percent=round(fraction * 100, 1))
        write_json(directory / "progress.json", status)
    def checkpoint():
        write_json(directory / "checkpoint.json", result)
    stage_start = time.perf_counter()
    def begin(key):
        nonlocal stage_start
        status["stage"] = key
        status.pop("stage_progress", None)
        stage_start = time.perf_counter()
        for entry in status["stages"]:
            if entry["id"] == key:
                entry["state"] = "running"
        progress(0)
    def finish(key, data, reused=False):
        entry = next(row for row in status["stages"] if row["id"] == key)
        entry.update(state="complete", elapsed_ms=0 if reused else round((time.perf_counter() - stage_start) * 1000))
        if reused:
            entry["reused"] = True
        result["stages"][key] = {**entry, "data": data}
        checkpoint()
        progress(1)
    progress()
    checkpoint()
    pcm = None
    try:
        previous = Path(payload["reuse"]).parent if payload.get("reuse") else None
        action = payload.get("segment_action")
        original = read(Path(payload["reuse"])) if previous else None
        if original and (original["input"]["sha256"] != result["input"]["sha256"] or original["analysis_policy"] != POLICY):
            raise ValueError("재사용할 구간 분석의 원본·방식이 일치하지 않습니다.")
        if previous and (previous / "audio.f32").is_file():
            link_file(previous / "audio.f32", directory / "audio.f32")
            pcm = np.memmap(directory / "audio.f32", dtype=np.float32, mode="r")
            result["input"] = original["input"]
        else:
            metadata = {}
            pcm = stages.decode(source, directory, metadata)
            result["input"].update(metadata, durationMs=round(len(pcm) / 16), sample_rate=16000)
        duration = result["input"]["durationMs"]
        if payload.get("scene") and duration > MAX_VIDEO_SECONDS * 1000:
            raise ValueError("긴 원본은 하이라이트를 추출한 뒤 10분 이하 클립으로 영상을 만들어 주세요.")
        acoustic_engine = encoder = None
        if previous:
            base = read(previous / "segment-plan.json")
            link_file(previous / "segment-base.npz", directory / "segment-base.npz")
            arrays = np.load(directory / "segment-base.npz")
            activity, recovered = arrays["activity"], arrays["recovered"]
            arrays.close()
            mapping = {int(k): v for k, v in base["mapping"].items()}
            anchors = {key: np.array(value) for key, value in base["anchors"].items()}
            groups, speakers = base["groups"], base["speakers"]
            for key in ("vad", "laughter", "diarization", "voice"):
                begin(key)
                finish(key, original["stages"][key]["data"], reused=True)
        else:
            from ..inference.acoustic import AcousticEngine
            from ..inference.diarization import DiarizationEngine
            from ..inference.voice import VoiceEncoder
            from ..inference.laughter import recover, vocal_only
            from .execution import stt_execution
            begin("vad")
            vad = stages.vad(pcm, "silero-v5", root, progress)
            finish("vad", vad)
            begin("laughter")
            acoustic_engine = AcousticEngine(root)
            acoustic, speech, laughter, candidates = coarse_tracks(pcm, acoustic_engine, vad["probabilities"], progress, config.threshold)
            finish("laughter", dict(events=[], windows=[], policy=POLICY, resolution="20 ms", note="원본 후보 탐지 후 구간별로 다시 분석합니다."))
            begin("diarization")
            device = stt_execution(root)["device"]
            diarizer = DiarizationEngine(root, device)
            activity = diarizer.analyze(pcm, progress=lambda p: progress(p * .5))
            recovered = np.zeros_like(activity)
            if "laughter" in acoustic["kinds"]:
                secondary = diarizer.analyze(vocal_only(pcm, acoustic["kinds"]), progress=lambda p: progress(.5 + p * .5))
                recovered = recover(activity, secondary, acoustic["kinds"])
                del secondary
            del diarizer
            gc.collect()
            vocal = (speech >= .35) | (candidates >= config.threshold*.25)
            first = sorted((int(ix[0]), col) for col in range(activity.shape[1])
                           if len(ix := np.flatnonzero(((activity[:, col] >= .5) | (recovered[:, col] >= .5)) & vocal)))
            mapping = {col: f"S{i + 1}" for i, (_, col) in enumerate(first)}
            speakers = [dict(speaker_id=mapping[col], first_seen_us=frame * 20000) for frame, col in first]
            groups = plan_segments(activity, speech, candidates, duration, threshold=config.threshold)
            finish("diarization", dict(speaker_count=len(speakers), segments=[dict(startMs=g["startMs"], endMs=g["endMs"],
                speaker=mapping[col]) for g in groups for col in g["columns"] if col in mapping],
                execution=dict(device=device, backend="ONNX Runtime"), group_count=len(groups)))
            begin("voice")
            encoder = VoiceEncoder(root)
            anchors, signatures = anchors_from_speech(pcm, activity, speech, laughter, mapping, encoder, config.threshold)
            finish("voice", dict(signatures=signatures, similarities=[], note="녹음 전체의 깨끗한 단독 발화로 만든 공통 화자 기준입니다."))
            base = dict(policy=POLICY, source_sha256=result["input"]["sha256"], groups=groups, speakers=speakers,
                        mapping=mapping, anchors={key: value.tolist() for key, value in anchors.items()},
                        source_laughter_events=[dict(startMs=a * 20, endMs=min(duration, b * 20), maxScore=float(np.max(laughter[a:b])))
                                                for a, b in ranges(laughter >= config.threshold*.5) if b - a >= 6],
                        laughter_threshold=config.threshold*.5, sensitivity=config.threshold,
                        laughter_debug=acoustic.get("laughter_debug", []))
            np.savez_compressed(directory / "segment-base.npz", activity=activity, recovered=recovered)
        if base["source_sha256"] != result["input"]["sha256"] or base["policy"] != POLICY:
            raise ValueError("저장된 구간 기준이 원본과 일치하지 않습니다.")
        write_json(directory / "segment-plan.json", base)
        begin("stt")
        requested_group = action["segment_id"].split("_")[0] if action else None
        if requested_group and requested_group not in {g["id"] for g in groups}:
            raise ValueError("재분석할 구간을 찾을 수 없습니다.")
        lanes, pending, overrides = [], [], {}
        for group in groups:
            cached = read_cached(previous, group) if previous else None
            if cached:
                overrides.update({row["id"]: row.get("speaker") for row in cached if row.get("manually_reviewed")})
            retry_failed = payload.get("resume_segments") and cached and any(row.get("state") == "failed" for row in cached)
            if cached is not None and not retry_failed and not (action and action.get("rerun") and group["id"] == requested_group):
                # Copy JSON instead of hardlinking mutable review decisions; audio is immutable.
                for lane in cached:
                    if lane.get("audio_file"):
                        link_file(previous / lane["audio_file"], directory / lane["audio_file"])
                lanes.extend(cached)
                write_json(directory / f"segment_{group['id']}.json", cached)
            else:
                pending.append(group)
        prior_stt = original["stages"].get("stt", {}).get("data", {}) if original else {}
        execution = prior_stt.get("execution", {})
        separation_execution = prior_stt.get("separation", {})
        if pending:
            from ..inference.acoustic import AcousticEngine
            from ..inference.voice import VoiceEncoder
            acoustic_engine = acoustic_engine or AcousticEngine(root)
            encoder = encoder or VoiceEncoder(root)
            engine = SegmentEngine(pcm, directory, root, config, acoustic_engine, encoder, anchors, mapping, activity, recovered)
            engine.separate(pending, lambda p: progress(p * .35))
            def completed(group, rows):
                for row in rows:
                    if row["id"] in overrides:
                        row.setdefault("acoustic_speaker", row.get("speaker"))
                        row.update(speaker=overrides[row["id"]], manually_reviewed=True,
                                   assignment_source="manual", requires_review=not bool(overrides[row["id"]]),
                                   review_reasons=[] if overrides[row["id"]] else ["manual_unknown"])
                lanes.extend(rows)
                write_json(directory / f"segment_{group['id']}.json", rows)
                status["segment_progress"] = dict(complete=len({r["group_id"] for r in lanes}), total=len(groups))
                progress()
            engine.analyze(pending, completed, lambda p: progress(.35 + p * .65))
            execution, separation_execution = engine.execution, engine.separation_execution
            del engine
        if action and not action.get("rerun"):
            lane = next((row for row in lanes if row["id"] == action["segment_id"]), None)
            if lane is None:
                raise ValueError("수정할 구간을 찾을 수 없습니다.")
            if action.get("speaker_id") and action["speaker_id"] not in {s["speaker_id"] for s in speakers}:
                raise ValueError("현재 녹음에 없는 화자입니다.")
            lane.setdefault("acoustic_speaker", lane.get("speaker"))
            lane.update(speaker=action.get("speaker_id"), manually_reviewed=True, assignment_source="manual",
                        assignment_reason="사용자가 직접 지정한 화자입니다.",
                        requires_review=not bool(action.get("speaker_id")),
                        review_reasons=[] if action.get("speaker_id") else ["manual_unknown"])
            write_json(directory / f"segment_{lane['group_id']}.json", [r for r in lanes if r["group_id"] == lane["group_id"]])
        from .dialogue_motion import prepare_speech
        prepare_speech(lanes, activity, mapping, resolve_conflicts=False)
        if not previous or pending or payload.get("review_speakers"):
            from .speaker_review import review
            status["speaker_review"] = dict(state="running", mode=config.speaker_review.mode)
            progress()
            reviewed_lanes = lanes
            if previous and not payload.get("review_speakers") and config.speaker_review.mode != "claude":
                pending_ids = {g["id"] for g in pending}
                reviewed_lanes = [r for r in lanes if r["group_id"] in pending_ids]
            result["speaker_review"] = review(reviewed_lanes, speakers, activity, mapping, config.speaker_review,
                                              payload.get("claude_key"), progress)
            status["speaker_review"] = result["speaker_review"]
            grouped = {}
            for row in reviewed_lanes:
                grouped.setdefault(row["group_id"], []).append(row)
            for identity, rows in grouped.items():
                write_json(directory / f"segment_{identity}.json", rows)
        elif original and original.get("speaker_review"):
            result["speaker_review"] = dict(original["speaker_review"], reused=True)
            from .speaker_review import VERSION as REVIEW_VERSION, assign_local
            if result["speaker_review"].get("version") != REVIEW_VERSION:
                # Old cached local guesses forced sibling stems onto different
                # people. Upgrade those guesses without rerunning ASR or an API.
                for lane in lanes:
                    if lane.get("assignment_source") == "local_provisional" and not lane.get("manually_reviewed"):
                        lane["speaker"] = lane.get("acoustic_speaker")
                if config.speaker_review.mode != "conservative":
                    assign_local(lanes, speakers, activity, mapping)
                result["speaker_review"].update(version=REVIEW_VERSION, local_assignments_upgraded=True)
                for group in groups:
                    write_json(directory / f"segment_{group['id']}.json", [r for r in lanes if r["group_id"] == group["id"]])
        if payload.get("scene", {}).get("config", {}).get("laughter_debug"):
            from .laughter_debug import backfill
            status["diagnostic_message"] = "영상 진단용 원점수 준비 · 전사·화자 모델은 재실행하지 않습니다."
            progress()
            acoustic_engine, debug_info = backfill(pcm, directory, lanes, base, root, config, acoustic_engine, progress)
            result["laughter_debug"] = debug_info
            write_json(directory / "segment-plan.json", base)
            # Persist new raw evidence in this new job only; future video rebuilds reuse it.
            if debug_info["backfilled_lanes"]:
                grouped = {}
                for row in lanes:
                    grouped.setdefault(row["group_id"], []).append(row)
                for identity, rows in grouped.items():
                    write_json(directory / f"segment_{identity}.json", rows)
            status.pop("diagnostic_message", None)
        character, tracks, utterances, events = assemble_characters(payload["id"], result["input"], groups, lanes, speakers,
            config.laughter_captions, base.get("source_laughter_events", []),
            [p["speaker"] for p in payload.get("scene", {}).get("config", {}).get("people", [])] or None)
        tracks["original_laughter_debug"] = base.get("laughter_debug", [])
        character["speaker_review"] = result.get("speaker_review")
        write_json(directory / "character-result.json", character)
        write_json(directory / "character-tracks.json", tracks)
        result.update(characters=character, utterances=utterances, analysis_incomplete=bool(character["failed_segments"]))
        if previous:
            result["reused_analysis_id"] = original["id"]
            result["segment_action"] = action
        result["stages"]["laughter"]["data"].update(events=events, windows=[w for e in events for w in e["windows"]],
            sensitivity=config.threshold, laughter_threshold=config.threshold*.5)
        finish("stt", dict(utterances=utterances, text="\n".join(u["text"] for u in utterances), execution=execution,
            separation=separation_execution, worker_count=2, gpu_concurrency=1, processed_groups=len(pending),
            reused_groups=len(groups) - len(pending), failed_segments=character["failed_segments"],
            note="캐릭터별 구간 분석 · 말하기와 웃음 동시 보존 · 원본 시각 기준"), reused=not pending)
        del acoustic_engine, encoder
        gc.collect()
        begin("topics")
        if original and not pending and not (action and action.get("rerun")) and "topics" in original["stages"]:
            # Caption deduplication changes IDs, not the already selected source
            # time ranges. Reindex cached topic/clip references without an API call.
            topics, clips = [], []
            for rows, target, keys in ((original["topics"], topics, ("start_id", "end_id")),
                                       (original["clips"], clips, ("startUtteranceId", "endUtteranceId"))):
                for row in rows:
                    matches = [u["id"] for u in utterances if u["startMs"] < row["endMs"] and row["startMs"] < u["endMs"]]
                    if matches:
                        target.append({**row, keys[0]: min(matches), keys[1]: max(matches)})
            result.update(topics=topics, clips=clips)
            finish("topics", {**original["stages"]["topics"]["data"], "topics": topics, "clip_count": len(clips)}, reused=True)
        else:
            credentials = Connection.model_validate(payload["credentials"]) if payload.get("credentials") else None
            parts, metadata = partition(utterances, config.topics, config, directory, credentials, root, progress)
            by_id = {u["id"]: u for u in utterances}
            topics = [dict(part, startMs=by_id[part["start_id"]]["startMs"],
                           endMs=max(by_id[i]["endMs"] for i in range(part["start_id"], part["end_id"] + 1))) for part in parts]
            decisions = [dict(boundary_for_event(parts, e, utterances), eventId=e["eventId"]) for e in events] if parts else []
            clips = select_topics(decisions, utterances, events, duration, config.max_clips) if decisions else []
            # Overlapping utterances are ordered by start time; the last ID need
            # not have the latest end time. Preserve all selected speech tails.
            for clip in clips:
                clip["endMs"] = min(duration, max(clip["endMs"], max(u["endMs"] for u in utterances
                    if clip["startUtteranceId"] <= u["id"] <= clip["endUtteranceId"])))
                clip["durationMs"] = clip["endMs"] - clip["startMs"]
                clip["boundaryMethod"] = "character segments + " + config.topics
            result.update(topics=topics, clips=clips)
            finish("topics", dict(topics=topics, clip_count=len(clips), **metadata))
        status["stage"] = "exporting"
        progress()
        captions = character["captions"]
        write_json(directory / "transcript.json", dict(utterances=utterances, captions=captions, durationMs=duration))
        (directory / "transcript.srt").write_text("\n".join(
            f"{i}\n{timestamp(row['startMs'], ',')} --> {timestamp(row['endMs'], ',')}\n{row['text']}\n"
            for i, row in enumerate(captions, 1)), encoding="utf-8")
        export_clips(source, directory, result["clips"], captions)
        if payload.get("scene"):
            from ..video_lab.render import make_videos, bundle
            status["stage"] = "video"
            entry = dict(id="video", label="캐릭터 영상 제작", state="running")
            status["stages"].append(entry)
            def video_progress(i, count, frame, total):
                status["video_progress"] = dict(video=i + 1, count=count, frame=frame, frames=total,
                    percent=round((i + frame / total) / max(1, count) * 100))
                progress()
            video = make_videos(result, pcm, source, directory, payload["scene"], video_progress)
            entry.update(state="complete", elapsed_ms=video["render_ms"])
            bundle(directory, source, result, payload["scene"])
        result["artifacts"] = [p.name for p in directory.iterdir() if p.name.startswith(("clip_", "transcript.", "video_", "character-", "app-bundle"))]
        result["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        write_json(directory / "result.json", result)
        status.update(state="complete", stage="complete")
        progress()
    except Exception as exc:
        import traceback
        traceback.print_exc()
        message = str(exc)[:500] if isinstance(exc, ValueError) else f"구간 분석 실패 ({type(exc).__name__}). worker.log를 확인해 주세요."
        status.update(state="failed", error=message)
        for entry in status["stages"]:
            if entry["state"] == "running":
                entry["state"] = "failed"
        result.update(partial=True, error=message, failed_stage=status["stage"], stage_status=status["stages"],
                      elapsed_ms=round((time.perf_counter() - started) * 1000))
        write_json(directory / "partial.json", result)
        progress()
        raise
    finally:
        if getattr(pcm, "_mmap", None) is not None:
            pcm._mmap.close()
