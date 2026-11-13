import math
import numpy as np
from ..contracts import (AnalysisResult, AudioInfo, Caption, Event, MotionTracks,
                         Speaker, SpeakerTrack, TrackRef, Warning, validate_pair)
from .features import quantile


def speaker_order(activity, laughter):
    """Canonical first-appearance order; activity must already have the vocal mask."""
    return sorted((int(active[0]), column) for column in range(8)
                  if len(active := np.flatnonzero((activity[:, column] > .5) | (laughter[:, column] >= .5))))


def assemble(job_id, sha, mime, duration_us, acoustic, activity, laughter, captions, models,
             hint=0, origin="inference"):
    count = math.ceil(duration_us/20000)
    if activity.shape != (count, 8) or laughter.shape != (count, 8) or not np.isfinite(activity).all() or not np.isfinite(laughter).all():
        raise ValueError("Invalid inference tracks")
    # Ignore model activity in environmental/silent frames without relabeling those sounds.
    vocal = np.array([k in {"speech", "laughter"} for k in acoustic["kinds"]])
    activity = activity.copy()
    activity[~vocal] = 0
    first_seen = speaker_order(activity, laughter)
    ids = {column: f"S{i+1}" for i, (_, column) in enumerate(first_seen)}
    speakers = [Speaker(speaker_id=ids[c], first_seen_us=f*20000) for f, c in first_seen]
    events = []
    for i, kind in enumerate(acoustic["kinds"]):
        active = [ids[c] for _, c in first_seen if activity[i, c] > .5 or laughter[i, c] >= .5] if vocal[i] else []
        laughing = [ids[c] for _, c in first_seen if laughter[i, c] >= .5] if kind == "laughter" else []
        reasons = (["unknown_speaker"] if vocal[i] and not active else []) + (["unknown_laughter_speaker"] if kind == "laughter" and not laughing else [])
        end = min(duration_us, (i+1)*20000)
        signature = (kind, active, laughing, reasons)
        if events and (events[-1].kind, events[-1].active_speaker_ids, events[-1].laughing_speaker_ids, events[-1].review_reasons) == signature:
            events[-1].end_us = end
            events[-1].score = max(events[-1].score, float(acoustic["scores"][i]))
        else:
            events.append(Event(event_id=f"E{len(events)+1}", start_us=i*20000, end_us=end, kind=kind,
                                active_speaker_ids=active, laughing_speaker_ids=laughing,
                                score=float(np.clip(acoustic["scores"][i], 0, 1)),
                                requires_review=bool(reasons), review_reasons=reasons))
    for event in events:
        event.energy_reference = max(.0002, quantile(acoustic["energy"][event.start_us//20000:math.ceil(event.end_us/20000)]))
    for caption in captions:
        overlapping = [e for e in events if e.end_us > caption.start_us and e.start_us < caption.end_us and e.kind == "speech"]
        single = {e.active_speaker_ids[0] for e in overlapping if len(e.active_speaker_ids) == 1}
        clear = bool(overlapping) and len(single) == 1 and all(len(e.active_speaker_ids) == 1 and not e.requires_review for e in overlapping)
        caption.speaker_ids = list(single) if clear else []
        caption.requires_review = not clear
    warnings = []
    if hint and hint != len(speakers):
        warnings.append(Warning(code="SPEAKER_COUNT_MISMATCH", message="입력한 화자 수와 감지 결과가 다릅니다. 자동 병합하지 않았습니다."))
    if any(e.requires_review for e in events):
        warnings.append(Warning(code="REVIEW_SPEAKERS", message="일부 구간의 화자를 확인해 주세요. 원본 음성과 자막은 유지됩니다."))
    tracks = MotionTracks(job_id=job_id, duration_us=duration_us, frame_count=count,
                          mixed_voice_energy=np.asarray(acoustic["energy"]).tolist(), speaker_tracks=[
                              SpeakerTrack(speaker_id=ids[c], activity=activity[:, c].tolist(),
                                           laughter_activity=laughter[:, c].tolist()) for _, c in first_seen])
    result = AnalysisResult(job_id=job_id, duration_us=duration_us, data_origin=origin,
                            audio=AudioInfo(sha256=sha, original_mime=mime), speakers=speakers, events=events,
                            captions=captions, models=models, warnings=warnings,
                            tracks=TrackRef(endpoint=f"/v1/analyses/{job_id}/tracks", frame_count=count))
    validate_pair(result, tracks)
    return result, tracks


def fixture(job_id, sha, duration_us, mime="audio/wav"):
    """Explicit synthetic two-person timeline, never invoked by production inference."""
    count = math.ceil(duration_us/20000)
    t = np.arange(count)*.02
    scale = duration_us/1e6/3
    phase = t/scale
    kinds = np.where(phase < .4, "silence", np.where(phase < 1.2, "speech", np.where(phase < 1.9, "laughter", np.where(phase < 2.5, "speech", "silence")))).tolist()
    activity = np.zeros((count, 8), np.float32)
    activity[(phase >= .4) & (phase < 1.9), 0] = .9
    activity[(phase >= 1.5) & (phase < 2.5), 1] = .8
    laughter = np.zeros_like(activity)
    laughter[(phase >= 1.2) & (phase < 1.9), 0] = .9
    energy = np.where((phase >= .4) & (phase < 2.5), .02*(.5+.5*np.sin(t*15)), 0)
    captions = [Caption(caption_id="C1", start_us=round(.4*scale*1e6), end_us=round(1.2*scale*1e6), text="함께 달리는 오늘의 이야기"),
                Caption(caption_id="C2", start_us=round(1.9*scale*1e6), end_us=round(2.5*scale*1e6), text="이 장면을 기억해 두자")]
    return assemble(job_id, sha, mime, duration_us, dict(kinds=kinds, scores=[.9]*count, energy=energy),
                    activity, laughter, captions, [], origin="fixture")
