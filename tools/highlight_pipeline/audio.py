from __future__ import annotations

import math
from pathlib import Path

from .common import cached, log, run, save_cache, sha256, signature
from .models import LABELS, YAMNET

LAUGH_LABELS = {"Laughter", "Baby laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle"}
SAMPLE_RATE, FRAME, HOP = 16000, 15600, 7800


def laughter_windows(source: Path, source_hash: str, output: Path, *, threads=2) -> list[dict]:
    import numpy as np
    from ai_edge_litert.interpreter import Interpreter

    fingerprint = signature({"source": source_hash, "model": sha256(YAMNET), "labels": sha256(LABELS),
        "decoder": "ffmpeg-mono-f32-16k-v1", "frame": FRAME, "hop": HOP})
    cache = output / "laughter_windows.json"
    if (result := cached(cache, fingerprint)) is not None:
        return result
    pcm = output / "analysis_16k.f32"
    log("오디오를 YAMNet 입력(16 kHz 모노)으로 디코딩")
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", str(source),
        "-map", "0:a:0", "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", str(pcm)])
    if pcm.stat().st_size == 0:
        raise ValueError("디코딩된 오디오가 비어 있습니다.")
    samples = np.memmap(pcm, mode="r", dtype="<f4")
    labels = LABELS.read_text(encoding="utf-8").splitlines()
    indices = [index for index, name in enumerate(labels) if name in LAUGH_LABELS]
    if len(indices) != 6 or "Music" not in labels:
        raise ValueError("YAMNet 라벨 파일이 예상 모델과 일치하지 않습니다.")
    music = labels.index("Music")
    interpreter = Interpreter(model_path=str(YAMNET), num_threads=threads)
    interpreter.allocate_tensors()
    input_info = interpreter.get_input_details()[0]
    output_info = interpreter.get_output_details()[0]
    if tuple(input_info["shape"]) != (FRAME,) or np.prod(output_info["shape"]) != len(labels):
        raise ValueError("지원하지 않는 YAMNet 입력/출력 크기입니다.")
    windows = []
    starts = range(0, max(1, len(samples) - FRAME + 1), HOP)
    previous_percent = -10
    for offset in starts:
        frame = np.zeros(FRAME, dtype=np.float32)
        count = min(FRAME, len(samples) - offset)
        frame[:count] = samples[offset:offset + count]
        interpreter.set_tensor(input_info["index"], frame)
        interpreter.invoke()
        scores = interpreter.get_tensor(output_info["index"]).reshape(-1)
        windows.append({"startMs": offset * 1000 // SAMPLE_RATE,
            "endMs": (offset + count) * 1000 // SAMPLE_RATE,
            "laughter": float(max(scores[indices])), "music": float(scores[music])})
        percent = int((offset + count) / len(samples) * 100)
        if percent >= previous_percent + 10:
            previous_percent = percent
            log(f"YAMNet {percent}% · {len(windows)}개 구간")
    del samples, interpreter
    save_cache(cache, fingerprint, windows)
    return windows


def group_events(windows: list[dict], threshold: float, gap_ms=2500) -> list[dict]:
    if not 0 < threshold <= 1:
        raise ValueError("웃음 기준은 0 초과 1 이하여야 합니다.")
    valid = sorted([w for w in windows if math.isfinite(w["laughter"]) and math.isfinite(w["music"])
        and w["laughter"] >= threshold and w["laughter"] > w["music"] and w["music"] < 0.5
        and 0 <= w["startMs"] < w["endMs"]], key=lambda w: w["startMs"])
    groups = []
    for window in valid:
        if not groups or window["startMs"] - groups[-1][-1]["endMs"] > gap_ms:
            groups.append([window])
        else:
            groups[-1].append(window)
    return [{"eventId": index, "startMs": group[0]["startMs"], "endMs": max(w["endMs"] for w in group),
        "maxScore": max(w["laughter"] for w in group), "windowCount": len(group), "windows": group}
        for index, group in enumerate(groups, 1)]
