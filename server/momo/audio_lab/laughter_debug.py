"""Keep unfiltered classifier evidence separately from animation decisions."""
import math

import numpy as np
from scipy.io import wavfile

from .segment_core import ranges

VERSION = "laughter-debug-v1"


def record(acoustic, offset_ms, start_ms, end_ms, threshold):
    rms = np.asarray(acoustic["rms"])
    return dict(version=VERSION, offsetMs=offset_ms, startMs=round(start_ms), endMs=round(end_ms),
                sensitivity=threshold, centerMs=487.5, hopMs=160,
                frames=acoustic.get("frames", []), audible_spans=[
                    [offset_ms + a*20, offset_ms + b*20] for a, b in ranges(rms > .00018)])


def backfill(pcm, directory, lanes, base, root, config, engine, report):
    """Old cache did not retain raw scores. Re-run only the acoustic classifier."""
    from ..inference.acoustic import AcousticEngine
    from .segment_engine import coarse_tracks
    missing = [r for r in lanes if r.get("state") == "complete" and "laughter_debug" not in r]
    original_missing = not base.get("laughter_debug")
    if not missing and not original_missing:
        return engine, dict(backfilled_lanes=0, backfilled_original=False)
    engine = engine or AcousticEngine(root)
    if original_missing:
        acoustic, _, _, _ = coarse_tracks(pcm, engine, None, lambda p: report(p*.35), config.threshold)
        base["laughter_debug"] = acoustic["laughter_debug"]
    for i, lane in enumerate(missing):
        start, end = lane["contextStartMs"], lane["contextEndMs"]
        wave = wavfile.read(directory / lane["audio_file"])[1] if lane.get("audio_file") else np.asarray(
            pcm[round(start*16):round(end*16)])
        acoustic = engine.analyze(wave, include_frames=True)
        lane["laughter_debug"] = record(acoustic, start, lane["startMs"], lane["endMs"], config.threshold)
        report(.35 + .65*(i+1)/max(1, len(missing)))
    return engine, dict(backfilled_lanes=len(missing), backfilled_original=original_missing)


def validate(data):
    if data.get("version") != VERSION or data.get("hopMs") != 160 or data.get("centerMs") != 487.5:
        raise ValueError("웃음 진단 데이터 형식이 올바르지 않습니다.")
    if not all(math.isfinite(data[k]) for k in ("offsetMs", "startMs", "endMs", "sensitivity")) or not .001 <= data["sensitivity"] <= 1:
        raise ValueError("웃음 진단 시각·감도 값이 올바르지 않습니다.")
    if not 0 <= data["offsetMs"] <= data["startMs"] < data["endMs"]:
        raise ValueError("웃음 진단 시각 범위가 올바르지 않습니다.")
    for frame in data["frames"]:
        for source in (frame, *([frame["band"]] if frame.get("band") else [])):
            if any(not isinstance(source.get(k), (int, float)) or not math.isfinite(source[k]) or not 0 <= source[k] <= 1
                   for k in ("laughter", "otherVocal", "nonVocal")):
                raise ValueError("웃음 진단 점수는 0~1 범위여야 합니다.")
    for a, b in data["audible_spans"]:
        if not math.isfinite(a) or not math.isfinite(b) or not 0 <= a < b:
            raise ValueError("웃음 진단 음량 구간이 올바르지 않습니다.")
