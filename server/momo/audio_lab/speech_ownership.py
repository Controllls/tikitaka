"""One speech owner for uncertain separator output, on the original clock.

Two stems are not evidence of two speakers. Preserve simultaneous speech only
when distinct voice matches or explicit manual identities support it. Keep raw
ASR and audio unchanged; derived intervals also drive captions and mouth masks.
"""
from collections import defaultdict

from .dialogue_dedup import confident_voice, strength


def independent(a, b):
    def identity(lane):
        if lane.get("manually_reviewed"):
            return lane.get("speaker")
        voice = confident_voice(lane)
        return voice if voice == lane.get("speaker") else None
    first, second = identity(a), identity(b)
    return bool(first and second and first != second)


def resolve(lanes):
    groups = defaultdict(list)
    for lane in lanes:
        lane["speech_conflicts"] = []
        if lane.get("state") == "complete" and lane.get("separated"):
            groups[lane["group_id"]].append(lane)
    for rows in groups.values():
        entries = [(lane, i, u) for lane in rows for i, u in enumerate(lane.get("utterances", []))
                   if i not in lane.get("duplicate_utterance_indices", [])]
        boundaries = sorted({u[key] for _, _, u in entries for key in ("startMs", "endMs")})
        owned = defaultdict(list)
        for start, end in zip(boundaries, boundaries[1:]):
            active = [(lane, i, u) for lane, i, u in entries if u["startMs"] < end and start < u["endMs"]]
            active.sort(key=lambda item: strength(item[0], [item[1]]), reverse=True)
            selected = []
            for lane, index, utterance in active:
                conflict = next((winner for winner, _, _ in selected if winner is not lane and
                                 not independent(winner, lane)), None)
                if conflict is not None:
                    lane["speech_conflicts"].append(dict(utterance_index=index, startMs=start, endMs=end,
                        kept_segment_id=conflict["id"], method="uncertain_separation_single_owner",
                        requires_review=True, reason="서로 다른 두 목소리라는 근거가 부족하여 겹친 대사는 한 화자만 재생"))
                    continue
                selected.append((lane, index, utterance))
                intervals = owned[(lane["id"], index)]
                if intervals and intervals[-1][1] == start:
                    intervals[-1][1] = end
                else:
                    intervals.append([start, end])
        for lane in rows:
            derived = []
            for index, utterance in enumerate(lane.get("utterances", [])):
                for start, end in owned[(lane["id"], index)]:
                    row = {**utterance, "startMs": start, "endMs": end}
                    if (start, end) != (utterance["startMs"], utterance["endMs"]):
                        # Retain the unique tail with its own words, rather than
                        # replaying an entire sentence after the overlapping part.
                        words = [{**w, "startMs": max(start, w["startMs"]), "endMs": min(end, w["endMs"])}
                                 for w in utterance.get("words", []) if start <= (w["startMs"] + w["endMs"]) / 2 < end
                                 and w["startMs"] < end and start < w["endMs"]]
                        if utterance.get("words") and not words:
                            continue
                        if words:
                            row.update(words=words, text="".join(w["text"] for w in words).strip(),
                                       startMs=words[0]["startMs"], endMs=words[-1]["endMs"])
                    derived.append(dict(utterance_index=index, utterance=row))
            lane["speech_utterances"] = derived
