import math
import re

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


def utterances_from_segments(segments: list[dict]) -> list[dict]:
    """Turn word alignment into prompt-sized utterances; these are NOT clip boundaries."""
    words = []
    for segment in segments:
        for word in segment.get("words") or []:
            start, end = round(word["start"] * 1000), round(word["end"] * 1000)
            if word["word"].strip() and 0 <= start <= end:
                words.append({"startMs": start, "endMs": end, "text": word["word"]})
    groups, current = [], []
    for word in words:
        if current and (word["startMs"] - current[-1]["endMs"] > 1500 or
            sum(len(w["text"]) for w in current) + len(word["text"]) > 160):
            groups.append(current)
            current = []
        current.append(word)
        if re.search(r"[?!。]$|(?<!\.)\.(?!\.)$", word["text"].strip()):
            groups.append(current)
            current = []
    if current:
        groups.append(current)
    return [{"id": index, "startMs": group[0]["startMs"], "endMs": max(w["endMs"] for w in group),
        "text": "".join(w["text"] for w in group).strip(), "words": group}
        for index, group in enumerate(groups, 1)]


