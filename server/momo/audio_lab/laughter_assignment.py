"""Assign detected, unidentified laughter for animation without relabelling speech."""
from bisect import bisect_right
from collections import defaultdict
import hashlib
import json
import math
import random

import numpy as np

VERSION = "laughter-listener-random-v2"
RECENT_MS = 2000


class EventTimeline:
    def __init__(self, events):
        self.events = sorted(events, key=lambda e: e["startMs"])
        self.starts = [e["startMs"] for e in self.events]
        self.ends = []
        end = 0
        for event in self.events:
            end = max(end, event["endMs"])
            self.ends.append(end)

    def at(self, now_ms):
        return self.between(now_ms, now_ms)

    def between(self, start_ms, end_ms):
        index = bisect_right(self.starts, end_ms) - 1
        active = []
        while index >= 0 and self.ends[index] > start_ms:
            event = self.events[index]
            if start_ms < event["endMs"]:
                active.append(event)
            index -= 1
        return active


def speech_timeline(lanes):
    """Use the same word clock and speech gates as mouth motion, never laugh VAD alone."""
    from .dialogue_motion import visible_utterances
    from .segment_core import ranges
    by_speaker = defaultdict(list)
    for lane in lanes:
        if not lane.get("speaker") or lane.get("state") != "complete":
            continue
        speech = np.asarray(lane.get("speech", []))
        mask = lane.get("speech_mask")
        if mask is None:
            mask = np.zeros(len(speech), dtype=bool)
            for _, utterance in visible_utterances(lane):
                for word in utterance.get("words") or [utterance]:
                    first = max(0, math.floor((word["startMs"]-60-lane["startMs"])/20))
                    last = min(len(mask), math.ceil((word["endMs"]+60-lane["startMs"])/20))
                    if last > first:
                        mask[first:last] = True
        for first, last in ranges((speech >= .35) & (np.asarray(mask) > 0)):
            by_speaker[lane["speaker"]].append((lane["startMs"]+first*20, min(lane["endMs"], lane["startMs"]+last*20)))
    intervals = []
    for speaker, spans in by_speaker.items():
        merged = []
        for start, end in sorted(spans):
            if merged and start <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], end)
            else:
                merged.append([start, end])
        intervals.extend(dict(startMs=a, endMs=b, speaker=speaker) for a, b in merged)
    return EventTimeline(intervals)


def overlap_ms(timeline, ids, start, end):
    result = dict.fromkeys(ids, 0)
    for row in timeline.between(start, end):
        if row["speaker"] in result:
            result[row["speaker"]] += max(0, min(end, row["endMs"])-max(start, row["startMs"]))
    return result


def assign(events, lanes, speakers, preferred=None, seed=""):
    """Randomly choose among listeners, then the least speaking characters.

    This is a presentation policy requested by the user, not a verified identity.
    Source and event clocks seed the random choice so video rebuilds are stable.
    Only laughter events change; lane speakers, scores and speech remain intact.
    """
    ids = sorted(set(preferred or [s["speaker_id"] for s in speakers] or ["S1"]))
    timeline = speech_timeline(lanes)
    assigned = []
    for event in events:
        if event.get("speaker"):
            continue
        start, end = event["startMs"], event["endMs"]
        speaking = overlap_ms(timeline, ids, start, end)
        recent = overlap_ms(timeline, ids, max(0, start-RECENT_MS), start)
        least = min(speaking.values())
        candidates = [s for s in ids if speaking[s] == least]
        # During a pause after a line, prefer somebody who did not just say it.
        if least == 0:
            quietest = min(recent[s] for s in candidates)
            candidates = [s for s in candidates if recent[s] == quietest]
        key = json.dumps([VERSION, seed, start, end, event.get("segment_id"), event.get("origin"), candidates], separators=(",", ":"))
        chosen = random.Random(hashlib.sha256(key.encode()).digest()).choice(candidates)
        method = "random_listener" if least == 0 else "random_least_speech"
        reason = ("웃음 구간에서 말하지 않고 직전 2초의 발화도 적은 캐릭터 중 무작위로 배정했습니다." if least == 0 else
                  "모두 말하고 있어 웃음과 겹치는 발화 시간이 가장 짧은 캐릭터 중 무작위로 배정했습니다.")
        event.update(speaker=chosen, original_speaker=None, assignment_source="laughter_provisional",
                     assignment_reason=reason, assignment_method=method, requires_review=True, assignment_policy=VERSION,
                     assignment_activity=dict(speaking_ms=speaking, recent_speaking_ms=recent, recent_window_ms=RECENT_MS,
                                              eligible_speakers=candidates))
        assigned.append(event)
        if chosen not in {s["speaker_id"] for s in speakers}:
            speakers.append(dict(speaker_id=chosen, first_seen_us=round(event["startMs"]*1000)))
    return assigned
