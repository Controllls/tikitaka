"""Speech ownership and addressee cues are separate animation signals."""
import math
import re

import numpy as np

from .dialogue_dedup import normalized as normalized, suppress_duplicates

VERSION = "dialogue-motion-v3"


def visible_utterances(lane):
    if "speech_utterances" in lane:
        return [(row["utterance_index"], row["utterance"]) for row in lane["speech_utterances"]]
    hidden = set(lane.get("duplicate_utterance_indices", []))
    return [(i, u) for i, u in enumerate(lane.get("utterances", [])) if i not in hidden]


def prepare_speech(lanes, activity=None, mapping=None, resolve_conflicts=True):
    """Suppress overlapping copies of the same voice, not genuine cross-talk.

    Raw transcripts and acoustic arrays stay intact so a new review is reversible.
    Masks require timed speech; mentioning a listener never opens their mouth.
    """
    for lane in lanes:
        lane.pop("speech_utterances", None)
        lane.pop("speech_conflicts", None)
        lane["duplicate_utterance_indices"] = []
        if activity is not None:
            def probabilities(start, end):
                frames = activity[max(0, int(start // 20)):math.ceil(end / 20)]
                return {speaker: float(np.mean(frames[:, col])) for col, speaker in (mapping or {}).items()
                        if len(frames) and col < frames.shape[1]}
            lane["diarization_evidence"] = probabilities(lane["startMs"], lane["endMs"])
            lane["utterance_diarization"] = {str(i): probabilities(u["startMs"], u["endMs"])
                                             for i, u in enumerate(lane.get("utterances", []))}
    duplicates = suppress_duplicates(lanes)
    if resolve_conflicts:
        from .speech_ownership import resolve
        resolve(lanes)
    for lane in lanes:
        mask = np.zeros(len(lane.get("speech", [])), dtype=np.uint8)
        for _, u in visible_utterances(lane):
            intervals = u.get("words") or [u]
            for word in intervals:
                # Sample at the frame centre; padding across a turn would let
                # two mouths talk again even though caption owners are exclusive.
                start = max(0, math.ceil((word["startMs"] - lane["startMs"]) / 20 - .5))
                end = min(len(mask), math.ceil((word["endMs"] - lane["startMs"]) / 20 - .5))
                if end > start:
                    mask[start:end] = 1
        lane["speech_mask"] = mask.tolist()
    return duplicates


def intent_for(text):
    text = text.strip()
    if "?" in text or re.search(r"(?:나요|까요|습니까|니|어때|어떻게 생각해|맞지|그렇지|거죠)[.!…~\s]*$", text) and text not in {"아니", "아니."}:
        return "question"
    if re.search(r"^(?:저기요|있잖아|여보|자기야|형님|선생님|얘들아|여러분|안녕하세요|반갑습니다)(?:\s|[,!?.]|$)", text):
        return "address"
    return "statement"


def interactions(utterances, speakers, lanes=None):
    ids = {s["speaker_id"] for s in speakers}
    by_lane = {r["id"]: r for r in lanes or []}
    events = []
    for index, u in enumerate(utterances):
        owners = u.get("speakers", [])
        if len(owners) != 1 or owners[0] not in ids:
            continue
        speaker = owners[0]
        hints = by_lane.get(u.get("segment_id"), {}).get("claude_review", {}).get("dialogue", [])
        hint = next((h for h in hints if h.get("utterance_index") == u.get("utterance_index")), None)
        intent = hint["intent"] if hint else intent_for(u["text"])
        if intent not in {"question", "address"}:
            continue
        target = hint.get("addressee") if hint else None
        source = "claude" if hint else "text_rule"
        # A null model addressee is deliberate uncertainty, not a fallback guess.
        if not hint:
            others = ids - {speaker}
            if len(others) == 1:
                target = next(iter(others))
            else:
                next_reply = next((r for r in utterances[index + 1:index + 5] if r["startMs"] >= u["endMs"] and
                    r["startMs"] - u["endMs"] <= 3500 and len(r.get("speakers", [])) == 1 and r["speakers"][0] in others), None)
                if next_reply:
                    target = next_reply["speakers"][0]
        if target not in ids or target == speaker:
            continue
        events.append(dict(startMs=u["startMs"], endMs=u["endMs"], speaker=speaker, addressee=target,
            intent=intent, source=source, segment_id=u.get("segment_id"), requires_review=True))
    return events
