"""Sparse, original-clock character analysis. No exclusive speech/laugh label."""
import math
from bisect import bisect_right

import numpy as np

POLICY = "character-segments-v1"
STEP_MS = 20


def independent_tracks(acoustic, duration_ms, threshold=.1):
    from ..inference.acoustic import laughter_track
    count = math.ceil(duration_ms / STEP_MS)
    times = (np.arange(count) + .5) * .02
    frames = acoustic.get("frames", [])
    vad = np.asarray(acoustic["vad"])
    speech = np.interp(times, (np.arange(len(vad)) + .5) * .032, vad) if len(vad) else np.zeros(count)
    if frames:
        centers = np.arange(len(frames)) * .16 + .4875
        laughing = np.interp(times, centers, laughter_track(frames, threshold_scale=threshold/.1))
        # The weak detector is a candidate gate for separation, never a laughing-person verdict.
        candidates = np.interp(times, centers, [f["laughter"] for f in frames])
    else:
        laughing = np.array([s if k == "laughter" else 0 for k, s in zip(acoustic["kinds"], acoustic["scores"])])
        candidates = laughing.copy()
    energy = np.asarray(acoustic["energy"][:count])
    audible = np.asarray(acoustic["rms"][:count]) > .00018
    return speech * audible, laughing * audible, candidates * audible, energy


def ranges(flags):
    padded = np.r_[False, flags, False].astype(int)
    edges = np.diff(padded)
    return [(int(a), int(b)) for a, b in zip(np.flatnonzero(edges == 1), np.flatnonzero(edges == -1))]


def plan_segments(activity, speech, laugh_candidates, duration_ms, max_ms=8000, padding_ms=500, threshold=.1):
    """Natural turns with bounded context. Work cores never overlap; labels are global."""
    vocal = (speech >= .35) | (laugh_candidates >= threshold * .25)
    # Close very short internal gaps, retaining standalone nonverbal candidates.
    for start, end in ranges(~vocal):
        if start and end < len(vocal) and end - start <= 10:
            vocal[start:end] = True
    result = []
    for a, b in ranges(vocal):
        if b - a < 5:
            continue
        cursor = a
        while cursor < b:
            limit = min(b, cursor + max_ms // STEP_MS)
            # Split at a stable speaker turn, but keep enough context for short responses.
            last = tuple(np.flatnonzero(activity[cursor] >= .5))
            for i in range(cursor + 1, limit):
                now = tuple(np.flatnonzero(activity[i] >= .5))
                if now != last and i - cursor >= 75 and i + 5 <= b and all(
                        tuple(np.flatnonzero(row >= .5)) == now for row in activity[i:i + 5]):
                    limit = i
                    break
                last = now
            start, end = cursor * STEP_MS, min(duration_ms, limit * STEP_MS)
            # Source separation is also needed for speech plus laughter, even if diarization misses the laugher.
            overlap = bool(np.sum(np.sum(activity[cursor:limit] >= .5, axis=1) > 1) >= 5)
            speech_laugh = bool(np.any((speech[cursor:limit] >= .35) & (laugh_candidates[cursor:limit] >= threshold * .5)))
            candidates = np.flatnonzero(np.mean(activity[cursor:limit] >= .5, axis=0) >= .1).tolist()
            result.append(dict(id=f"G{len(result) + 1:06d}", startMs=start, endMs=end,
                contextStartMs=max(0, start - padding_ms), contextEndMs=min(duration_ms, end + padding_ms),
                columns=candidates, overlap=overlap, separate=overlap or speech_laugh,
                more_than_two=bool(np.any(np.sum(activity[cursor:limit] >= .5, axis=1) > 2))))
            cursor = limit
    return result


def speaker_matches(vectors, anchors, minimum=.60, margin=.08):
    """No output-order identity assumption; duplicate confident matches remain unresolved."""
    answers = []
    for vector in vectors:
        scores = sorted(({"speaker": key, "score": float(np.dot(vector, value))} for key, value in anchors.items()),
                        key=lambda x: -x["score"]) if vector is not None else []
        best = scores[0] if scores else None
        accepted = best and best["score"] >= minimum and (len(scores) == 1 or best["score"] - scores[1]["score"] >= margin)
        answers.append(dict(speaker=best["speaker"] if accepted else None, candidates=scores))
    for answer in answers:
        if answer["speaker"] and sum(a["speaker"] == answer["speaker"] for a in answers) > 1:
            duplicate = answer["speaker"]
            for candidate in answers:
                if candidate["speaker"] == duplicate:
                    candidate["speaker"] = None
    return answers


def trim_utterances(rows, start_ms, end_ms, offset_ms=0):
    """Own words by midpoint to prevent padding duplicates, retain original timestamps."""
    output = []
    for row in rows:
        words, tokens = [], []
        for word in row.get("words", []):
            a, b = word["startMs"] + offset_ms, word["endMs"] + offset_ms
            if start_ms <= (a + b) / 2 < end_ms:
                tokens.append(word["text"])
                if min(end_ms, b) > max(start_ms, a):
                    words.append(dict(text=word["text"], startMs=max(start_ms, a), endMs=min(end_ms, b)))
        if row.get("words"):
            if not words:
                continue
            text = "".join(tokens).strip()
            a, b = words[0]["startMs"], words[-1]["endMs"]
        else:
            a, b = row["startMs"] + offset_ms, row["endMs"] + offset_ms
            if not start_ms <= (a + b) / 2 < end_ms:
                continue
            a, b, text = max(start_ms, a), min(end_ms, b), row["text"]
        if text and b > a:
            output.append(dict(startMs=a, endMs=b, text=text, words=words))
    return output


def assemble_characters(identity, source, groups, lanes, speakers, laughter_captions=True, source_events=None, fallback_speakers=None):
    from .dialogue_motion import VERSION, prepare_speech, visible_utterances, interactions
    duplicate_dialogue = prepare_speech(lanes)
    utterances, events, summaries = [], [], []
    for lane in sorted(lanes, key=lambda row: (row["startMs"], row["id"])):
        speaker = lane.get("speaker")
        summaries.append({key: value for key, value in lane.items() if key not in {"speech", "speech_mask", "laughter", "energy", "utterances", "speech_utterances", "laughter_debug"}})
        for utterance_index, row in visible_utterances(lane):
            utterances.append({**row, "speakers": [speaker] if speaker else [],
                               "requires_review": lane.get("requires_review", speaker is None),
                               "assignment_source": lane.get("assignment_source", "acoustic" if speaker else "unresolved"),
                               "segment_id": lane["id"], "utterance_index": utterance_index})
        for a, b in ranges(np.asarray(lane.get("laughter", [])) >= lane.get("laughter_threshold", .05)):
            if b - a < 6:
                continue
            start, end = lane["startMs"] + a * 20, min(lane["endMs"], lane["startMs"] + b * 20)
            events.append(dict(startMs=start, endMs=end, speaker=speaker, segment_id=lane["id"],
                               requires_review=lane.get("requires_review", speaker is None),
                               assignment_source=lane.get("assignment_source", "acoustic" if speaker else "unresolved"),
                               maxScore=max(lane["laughter"][a:b])))
    # Speech separators can suppress nonverbal laughter. Preserve source evidence
    # before assigning any unresolved laughter for animation.
    covered = np.zeros(math.ceil(source["durationMs"] / 20), dtype=bool)
    for event in events:
        covered[int(event["startMs"] // 20):math.ceil(event["endMs"] / 20)] = True
    for original in source_events or []:
        first, last = int(original["startMs"] // 20), math.ceil(original["endMs"] / 20)
        for a, b in ranges(~covered[first:last]):
            if b - a < 6:
                continue
            start, end = (first + a) * 20, min(source["durationMs"], (first + b) * 20)
            events.append(dict(startMs=start, endMs=end, speaker=None, segment_id=None, origin="original",
                source_group_id=next((g["id"] for g in groups if g["startMs"] < end and start < g["endMs"]), None),
                maxScore=original["maxScore"]))
    from .laughter_assignment import VERSION as LAUGHTER_VERSION, assign
    speakers = [dict(s) for s in speakers]
    fallback = assign(events, lanes, speakers, fallback_speakers, seed=source["sha256"])
    utterances.sort(key=lambda u: (u["startMs"], u["endMs"], u["segment_id"]))
    for i, row in enumerate(utterances, 1):
        row["id"] = i
    # Union simultaneous laughter for highlight scoring; keep per-person events separately.
    union = []
    for event in sorted(events, key=lambda x: x["startMs"]):
        if union and event["startMs"] <= union[-1]["endMs"]:
            union[-1]["endMs"] = max(union[-1]["endMs"], event["endMs"])
            union[-1]["maxScore"] = max(union[-1]["maxScore"], event["maxScore"])
        else:
            union.append({k: event[k] for k in ("startMs", "endMs", "maxScore")})
    for i, event in enumerate(union, 1):
        event.update(eventId=i, windows=[dict(startMs=event["startMs"], endMs=event["endMs"], laughter=event["maxScore"])])
    captions = list(utterances)
    if laughter_captions:
        captions += [dict(startMs=e["startMs"], endMs=e["endMs"], text="[웃음]", kind="laughter",
                         speakers=[e["speaker"]] if e["speaker"] else [], requires_review=e.get("requires_review", e["speaker"] is None),
                         assignment_source=e.get("assignment_source", "unresolved"),
                         segment_id=e["segment_id"], words=[]) for e in events]
    captions.sort(key=lambda c: (c["startMs"], c["endMs"]))
    character = dict(schema_version="2.0", policy=POLICY, job_id=identity, source_sha256=source["sha256"],
        durationMs=source["durationMs"], speakers=speakers, segments=summaries, groups=groups, captions=captions,
        laughter_events=events, unresolved_laughter_events=sum(e["speaker"] is None for e in events),
        provisional_laughter_events=len(fallback), laughter_assignment_policy=LAUGHTER_VERSION,
        failed_segments=sum(s.get("state") == "failed" for s in summaries),
        unresolved_segments=sum(s.get("speaker") is None for s in summaries))
    character["provisional_segments"] = sum(s.get("assignment_source", "").endswith("provisional") for s in summaries)
    turns = interactions(utterances, speakers, lanes)
    character.update(animation_policy=VERSION, duplicate_dialogue=duplicate_dialogue, interactions=turns)
    character["speech_conflicts"] = [dict(segment_id=lane["id"], **event)
                                     for lane in lanes for event in lane.get("speech_conflicts", [])]
    tracks = dict(schema_version="2.0", policy=POLICY, job_id=identity, source_sha256=source["sha256"],
                  durationMs=source["durationMs"], stepMs=20, lanes=lanes, interactions=turns, animation_policy=VERSION)
    tracks["unassigned_laughter_events"] = [e for e in events if e["speaker"] is None]
    tracks["fallback_laughter_events"] = fallback
    tracks["laughter_assignment_policy"] = LAUGHTER_VERSION
    return character, tracks, utterances, union


class CharacterMotion:
    def __init__(self, tracks, mapping):
        from .laughter_assignment import EventTimeline
        self.fallback = {person: EventTimeline([e for e in tracks.get("fallback_laughter_events", []) if e["speaker"] == speaker])
                         for speaker, person in mapping.items()}
        from .dialogue_motion import VERSION, prepare_speech
        if tracks.get("animation_policy") != VERSION or any("speech_mask" not in lane for lane in tracks["lanes"]):
            prepare_speech(tracks["lanes"])
        self.lanes = {}
        for lane in tracks["lanes"]:
            if lane.get("speaker") and lane.get("state") == "complete":
                self.lanes.setdefault(mapping.get(lane["speaker"]), []).append(lane)
        for person, lanes in self.lanes.items():
            lanes.sort(key=lambda row: row["startMs"])
        self.starts = {person: [row["startMs"] for row in lanes] for person, lanes in self.lanes.items()}
        self.ends = {person: np.maximum.accumulate([row["endMs"] for row in lanes]) for person, lanes in self.lanes.items()}

    def _active_lanes(self, person, now):
        lanes = self.lanes.get(person, [])
        index = bisect_right(self.starts.get(person, []), now) - 1
        while index >= 0 and self.ends[person][index] > now:
            if lanes[index]["endMs"] > now:
                yield lanes[index]
            index -= 1

    def event(self, now):
        from types import SimpleNamespace
        active = []
        for person in self.lanes:
            for lane in self._active_lanes(person, now / 1000):
                frame = int((now / 1000 - lane["startMs"]) // 20)
                if (lane["speech"][frame] >= .35 and lane["speech_mask"][frame]) or lane["laughter"][frame] >= lane.get("laughter_threshold", .05):
                    active.append(lane["speaker"])
        for timeline in self.fallback.values():
            active.extend(e["speaker"] for e in timeline.at(now/1000))
        return SimpleNamespace(active_speaker_ids=list(dict.fromkeys(active)))

    def at(self, person, now):
        state = self._lane_state(person, now)
        fallback = self.fallback.get(person)
        active = fallback.at(now/1000) if fallback else []
        if active:
            # A modest visual laugh has no measured per-person amplitude. Never
            # copy the original mixture into speech or inflate acoustic scores.
            local = (now/1000 - min(e["startMs"] for e in active))/1000
            state.update(laughing=True, provisional_laughter=True, mouth=max(state["mouth"], .6*(.35 + .65*(.5 + .5*math.sin(local*2*math.pi*4.6)))),
                         dx=.6*math.sin(local*27)*1.5, dy=.6*math.sin(local*30)*2.6, tilt=.6*math.sin(local*25)*.012)
        return state

    def _lane_state(self, person, now):
        rest = dict(mouth=0., dx=0., dy=0., tilt=0., laughing=False, speaking=False)
        states = [self._sample(lane, now) for lane in self._active_lanes(person, now / 1000)]
        if not states:
            return rest
        state = dict(max(states, key=lambda s: (s["laughing"], s["mouth"])))
        state.update(mouth=max(s["mouth"] for s in states), speaking=any(s["speaking"] for s in states))
        return state

    def _sample(self, lane, now):
        index = int((now / 1000 - lane["startMs"]) // 20)
        rest = dict(mouth=0., dx=0., dy=0., tilt=0., laughing=False, speaking=bool(lane["speech_mask"][index]))
        speech, laugh, energy = (lane[key][index] for key in ("speech", "laughter", "energy"))
        speech *= lane["speech_mask"][index]
        laughing = laugh >= lane.get("laughter_threshold", .05)
        if speech < .35 and not laughing:
            return rest
        level = min(1., energy / max(.0002, lane["energy_reference"]))
        local = (now / 1000 - lane["startMs"]) / 1000
        mouth = level * (.35 + .65 * (.5 + .5 * math.sin(local * 2 * math.pi * 4.6))) if level > .12 else 0
        shake = level if laughing else 0
        return dict(mouth=mouth, laughing=laughing, speaking=rest["speaking"],
                    dx=shake * math.sin(local * 27) * 1.5,
                    dy=shake * math.sin(local * 30) * 2.6, tilt=shake * math.sin(local * 25) * .012)
