"""Resolve repeated speech across separated stems on the original word clock."""
from collections import defaultdict
from difflib import SequenceMatcher
import re
import unicodedata

import numpy as np


def normalized(text):
    return re.sub(r"[^\w]", "", unicodedata.normalize("NFKC", text)).lower()


def ranked_voice(lane):
    return sorted(lane.get("candidates", []), key=lambda c: -c["score"])


def confident_voice(lane):
    rows = ranked_voice(lane)
    return rows[0]["speaker"] if rows and rows[0]["score"] >= .60 and (len(rows) == 1 or rows[0]["score"]-rows[1]["score"] >= .08) else None


def evidence(lane, indices):
    rows = lane.get("utterance_diarization", {})
    local = [rows[str(i)] for i in indices if str(i) in rows]
    if local:
        return {s: float(np.mean([r.get(s, 0) for r in local])) for s in set().union(*(r.keys() for r in local))}
    return lane.get("diarization_evidence", lane.get("assignment_evidence", {}).get("diarization", {}))


def solo(probabilities, minimum=.8, maximum=.25):
    rows = sorted(probabilities.items(), key=lambda p: -p[1])
    return rows[0][0] if rows and rows[0][1] >= minimum and (len(rows) == 1 or rows[1][1] < maximum) else None


def voice_support(a, b, a_indices, b_indices):
    voices = [ranked_voice(r) for r in (a, b)]
    same = all(v and v[0]["score"] >= .2 and (len(v) == 1 or v[0]["score"]-v[1]["score"] >= .04) for v in voices)
    same = bool(same and voices[0][0]["speaker"] == voices[1][0]["speaker"] and max(v[0]["score"] for v in voices) >= .35)
    probabilities = [evidence(a, a_indices), evidence(b, b_indices)]
    single = [solo(p) for p in probabilities]
    common = single[0] if single[0] == single[1] else None
    compatible = bool(common and all(any(c["speaker"] == common and c["score"] >= .2 and
        c["score"] >= v[0]["score"]-.15 for c in v) for v in voices))
    leakage = all("separation_leakage" in r.get("review_reasons", []) for r in (a, b))
    strong = leakage or compatible or (same and sum(v[0]["score"] for v in voices) >= .85)
    return dict(supported=same or compatible or leakage, strong=strong,
                solo=common if compatible else None, probabilities=probabilities)


def strength(lane, indices):
    voice = {c["speaker"]: c["score"] for c in lane.get("candidates", [])}
    assigned = lane.get("speaker")
    original = lane.get("acoustic_speaker", assigned)
    local = evidence(lane, indices)
    ranked = sorted(local.items(), key=lambda p: -p[1])
    dominant = ranked[0][0] if ranked and ranked[0][1] >= .7 and (len(ranked) == 1 or ranked[0][1]-ranked[1][1] >= .2) else None
    return (bool(lane.get("manually_reviewed")), bool(assigned and dominant == assigned),
            voice.get(original, 0) + .2*max(voice.values(), default=0),
            float(np.mean(lane.get("energy", [0]))), lane["id"])


def overlap(a, b):
    return max(0, min(a["endMs"], b["endMs"])-max(a["startMs"], b["startMs"]))


def text_match(text, reference):
    a, b = normalized(text), normalized(reference)
    # SequenceMatcher's greedy ties can depend on argument order. A duplicated
    # pair must receive the same similarity whichever stem currently owns it.
    matches = [SequenceMatcher(None, a, b, autojunk=False), SequenceMatcher(None, b, a, autojunk=False)]
    count = max(sum(m.size for m in match.get_matching_blocks()) for match in matches)
    x, y = unicodedata.normalize("NFD", a), unicodedata.normalize("NFD", b)
    return dict(similarity=max(m.ratio() for m in matches), coverage=count/max(1, min(len(a), len(b))),
                phonetic_similarity=max(SequenceMatcher(None, x, y, autojunk=False).ratio(), SequenceMatcher(None, y, x, autojunk=False).ratio()),
                shortest=min(len(a), len(b)), longest=max((m.size for match in matches for m in match.get_matching_blocks()), default=0), exact=a == b)


def suppress_duplicates(lanes):
    groups = defaultdict(list)
    for lane in lanes:
        if lane.get("state") == "complete" and lane.get("separated"):
            groups[lane["group_id"]].append(lane)
    decisions = []
    for rows in groups.values():
        # Evaluate both directions: a turn inside one group can change the best
        # owner. Original diarization is read at utterance time, not group average.
        proposals = []
        for winner in rows:
            for loser in rows:
                if winner is loser:
                    continue
                if (winner.get("manually_reviewed") and loser.get("manually_reviewed") and
                        winner.get("speaker") and loser.get("speaker") and winner["speaker"] != loser["speaker"]):
                    continue
                av, bv = confident_voice(winner), confident_voice(loser)
                if av and bv and av != bv:
                    continue
                pair = []
                for index, utterance in enumerate(loser.get("utterances", [])):
                    refs = [(i, u) for i, u in enumerate(winner.get("utterances", [])) if overlap(utterance, u) >=
                            min(120, .2*min(utterance["endMs"]-utterance["startMs"], u["endMs"]-u["startMs"])) and overlap(utterance, u) > 0]
                    if not refs:
                        continue
                    indices = [i for i, _ in refs]
                    if strength(winner, indices) <= strength(loser, [index]):
                        continue
                    intervals = sorted((max(utterance["startMs"], u["startMs"]), min(utterance["endMs"], u["endMs"])) for _, u in refs)
                    merged = []
                    for start, end in intervals:
                        if merged and start <= merged[-1][1]:
                            merged[-1][1] = max(merged[-1][1], end)
                        else:
                            merged.append([start, end])
                    covered = sum(b-a for a, b in merged)
                    duration = min(utterance["endMs"]-utterance["startMs"], sum(u["endMs"]-u["startMs"] for _, u in refs))
                    time_overlap = covered/max(1, duration)
                    if time_overlap < .65:
                        continue
                    metrics = text_match(utterance["text"], " ".join(u["text"] for _, u in refs))
                    support = voice_support(winner, loser, indices, [index])
                    shortest = metrics["shortest"]
                    exact = metrics["exact"] and shortest >= 4 and support["supported"]
                    near_exact = shortest >= 10 and metrics["similarity"] >= .9 and time_overlap >= .85
                    # Combine acoustic evidence with syllable/jamo agreement. The
                    # short-text coverage test handles a phrase inside a longer ASR sentence.
                    fuzzy = shortest >= 5 and support["supported"] and (
                        (metrics["similarity"] >= .55 and metrics["phonetic_similarity"] >= .68) or
                        (metrics["coverage"] >= .85 and metrics["longest"] >= 4) or
                        (support["strong"] and metrics["similarity"] >= .4 and metrics["phonetic_similarity"] >= .5 and metrics["longest"] >= 3))
                    method = "matching_phrase" if exact or near_exact or fuzzy else None
                    pair.append(dict(winner=winner, loser=loser, index=index, indices=indices, utterance=utterance,
                                     method=method, metrics=metrics, support=support, time_overlap=time_overlap))
                proposals.extend(pair)
        # A turn inside a group can reverse which stem is reliable. Share the
        # nearby duplicate evidence across BOTH directions of that stem pair.
        anchors = defaultdict(list)
        for p in proposals:
            if p["method"]:
                anchors[frozenset((p["winner"]["id"], p["loser"]["id"]))].append(p)
        for p in proposals:
            if not p["method"] and p["metrics"]["shortest"] >= 5 and p["metrics"]["phonetic_similarity"] >= .3:
                singles = [solo(v, .9, .15) for v in p["support"]["probabilities"]]
                u = p["utterance"]
                nearby = anchors[frozenset((p["winner"]["id"], p["loser"]["id"]))]
                close = any(max(u["startMs"]-a["utterance"]["endMs"], a["utterance"]["startMs"]-u["endMs"], 0) <= 500 for a in nearby)
                if close and p["time_overlap"] >= .8 and singles[0] and singles[0] == singles[1] == p["support"]["solo"]:
                    p["method"] = "single_speaker_continuation"
        proposals = [p for p in proposals if p["method"]]
        proposals.sort(key=lambda p: strength(p["winner"], p["indices"]), reverse=True)
        for p in proposals:
            winner, loser, index = p["winner"], p["loser"], p["index"]
            if index in loser["duplicate_utterance_indices"] or any(i in winner["duplicate_utterance_indices"] for i in p["indices"]):
                continue
            loser["duplicate_utterance_indices"].append(index)
            u = p["utterance"]
            decisions.append(dict(segment_id=loser["id"], utterance_index=index, kept_segment_id=winner["id"],
                kept_utterance_indices=p["indices"], startMs=u["startMs"], endMs=u["endMs"],
                removed_text=u["text"], kept_text=" ".join(winner["utterances"][i]["text"] for i in p["indices"]),
                similarity=round(p["metrics"]["similarity"], 3), phonetic_similarity=round(p["metrics"]["phonetic_similarity"], 3),
                time_overlap=round(p["time_overlap"], 3), method=p["method"], requires_review=True,
                reason="동시간대 구절·발음과 목소리 근거가 겹치는 중복 전사" if p["method"] == "matching_phrase" else
                "인접한 중복 구절과 원본의 단독 발화 근거가 이어지는 중복 전사 추정"))
    return decisions
