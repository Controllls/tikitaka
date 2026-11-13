"""Read version 2 sparse character tracks without converting them to exclusive v1 events."""
import json
import math
from types import SimpleNamespace

import numpy as np

from ..contracts import Caption, Speaker, Warning, Word


def load_characters(experiment, directory):
    data = json.loads((directory / "character-result.json").read_text(encoding="utf-8"))
    tracks = json.loads((directory / "character-tracks.json").read_text(encoding="utf-8"))
    for document in (data, tracks):
        if (document.get("schema_version"), document.get("job_id"), document.get("source_sha256")) != (
                "2.0", experiment["id"], experiment["input"]["sha256"]):
            raise ValueError("캐릭터 분석 자료와 원본·작업이 일치하지 않습니다.")
        if document.get("durationMs") != experiment["input"]["durationMs"]:
            raise ValueError("캐릭터 분석 길이가 원본과 일치하지 않습니다.")
    duration = data["durationMs"]
    from ..audio_lab.laughter_debug import validate as validate_debug
    for block in tracks.get("original_laughter_debug", []):
        # PCM duration can end on a fractional millisecond; the job clock is rounded.
        if block["endMs"] > duration and round(block["endMs"]) == duration:
            block["endMs"] = duration
        validate_debug(block)
        if block["endMs"] > duration:
            raise ValueError("원본 웃음 진단 시각이 파일 길이를 초과합니다.")
    ids = {s["speaker_id"] for s in data["speakers"]}
    for event in tracks.get("fallback_laughter_events", []):
        if (event.get("speaker") not in ids or not 0 <= event["startMs"] < event["endMs"] <= duration or
                not math.isfinite(event["maxScore"]) or not 0 <= event["maxScore"] <= 1 or
                event.get("assignment_source") != "laughter_provisional" or not event.get("requires_review")):
            raise ValueError("추정 배정한 웃음의 화자·시각·점수가 올바르지 않습니다.")
    seen = set()
    for lane in tracks["lanes"]:
        if lane["id"] in seen or not 0 <= lane["startMs"] < lane["endMs"] <= duration or lane.get("speaker") not in ids | {None}:
            raise ValueError("유효하지 않은 캐릭터 구간입니다.")
        seen.add(lane["id"])
        if lane.get("laughter_debug"):
            validate_debug(lane["laughter_debug"])
            if (lane["laughter_debug"]["startMs"], lane["laughter_debug"]["endMs"]) != (lane["startMs"], lane["endMs"]):
                raise ValueError("웃음 진단 시각과 구간 시각이 일치하지 않습니다.")
        if lane.get("state") != "complete":
            continue
        count = math.ceil((lane["endMs"] - lane["startMs"]) / 20)
        for key in ("speech", "laughter", "energy", *(["speech_mask"] if "speech_mask" in lane else [])):
            values = np.asarray(lane[key])
            if len(values) != count or not np.isfinite(values).all() or np.any(values < 0):
                raise ValueError("유효하지 않은 캐릭터 동작 트랙입니다.")
            if key == "speech_mask" and np.any(values > 1):
                raise ValueError("발화 소유 마스크는 0~1 범위여야 합니다.")
    from ..audio_lab.dialogue_motion import VERSION
    if tracks.get("animation_policy") != VERSION:
        # A legacy direct preview must not combine new mouth masks with stale
        # overlapping captions. Normal video rebuilds already upgrade in worker.
        from ..audio_lab.segment_core import assemble_characters
        debug = tracks.get("original_laughter_debug", [])
        data, tracks, _, _ = assemble_characters(experiment["id"], experiment["input"], data.get("groups", []),
            tracks["lanes"], data["speakers"], experiment.get("config", {}).get("laughter_captions", True),
            [e for e in data.get("laughter_events", []) if e.get("origin") == "original"])
        tracks["original_laughter_debug"] = debug
    captions = []
    for i, c in enumerate(data["captions"], 1):
        if not 0 <= c["startMs"] < c["endMs"] <= duration:
            raise ValueError("자막 시각이 원본 범위를 벗어났습니다.")
        label = ", ".join(c["speakers"])
        captions.append(Caption(caption_id=f"C{i}", start_us=round(c["startMs"] * 1000), end_us=round(c["endMs"] * 1000),
            text=f"{label}: {c['text']}" if label else c["text"], speaker_ids=c["speakers"], requires_review=c["requires_review"],
            words=[Word(start_us=round(w["startMs"] * 1000), end_us=round(w["endMs"] * 1000), text=w["text"]) for w in c["words"]]))
    warnings = [Warning(code="CHARACTER_TRACKS_V2", message="캐릭터별 말하기·웃음·음량을 독립 재생합니다. 앱 적용에는 v2 트랙 재생 지원이 필요합니다.")]
    if data["unresolved_segments"]:
        warnings.append(Warning(code="REVIEW_SEGMENTS", message=f"화자 미확정 {data['unresolved_segments']}개 구간은 캐릭터 동작에 배정하지 않았습니다."))
    if data["failed_segments"]:
        warnings.append(Warning(code="FAILED_SEGMENTS", message=f"분석 실패 {data['failed_segments']}개 구간을 재분석해 주세요."))
    if data.get("provisional_segments"):
        warnings.append(Warning(code="PROVISIONAL_SPEAKERS", message=f"{data['provisional_segments']}개 구간은 추정 화자로 재생합니다. 캐릭터·구간 탭에서 확인할 수 있습니다."))
    if data.get("provisional_laughter_events"):
        warnings.append(Warning(code="PROVISIONAL_LAUGHTER", message=f"화자를 확인하지 못한 웃음 {data['provisional_laughter_events']}건을 캐릭터에 추정 배정했습니다. 대사의 화자는 유지합니다."))
    analysis = SimpleNamespace(duration_us=duration * 1000, speakers=[Speaker(**s) for s in data["speakers"]],
                               captions=captions, warnings=warnings)
    return analysis, tracks, {key: key for key in ids}
