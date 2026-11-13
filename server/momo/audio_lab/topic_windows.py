"""Bound prompt size while keeping original IDs and context across window edges."""
from dataclasses import dataclass


@dataclass
class TopicWindow:
    context: list[dict]
    first: int
    last: int


def windows(utterances, core_bytes=6000, overlap_bytes=1800):
    if not utterances:
        return []
    sizes = [len(u["text"].encode("utf-8")) + 48 for u in utterances]
    if max(sizes) > core_bytes + 2 * overlap_bytes:
        raise ValueError("한 발화의 전사문이 너무 깁니다. 음성 인식 결과를 확인해 주세요.")
    if sum(sizes) <= core_bytes + 2 * overlap_bytes:
        return [TopicWindow(utterances, utterances[0]["id"], utterances[-1]["id"])]
    result, start = [], 0
    while start < len(utterances):
        end, size = start, 0
        while end < len(utterances) and (end == start or size + sizes[end] <= core_bytes):
            size += sizes[end]
            end += 1
        left, right = start, end
        # Very large individual utterances consume the context allowance too.
        budget = min(overlap_bytes, max(0, (core_bytes + 2 * overlap_bytes - size) // 2))
        used = 0
        while left > 0 and used + sizes[left - 1] <= budget:
            left -= 1
            used += sizes[left]
        used = 0
        while right < len(utterances) and used + sizes[right] <= budget:
            used += sizes[right]
            right += 1
        result.append(TopicWindow(utterances[left:right], utterances[start]["id"], utterances[end - 1]["id"]))
        start = end
    return result


def stitch(utterances, batches):
    """Discard artificial context-edge starts; keep boundaries in each core region."""
    starts = {}
    first = utterances[0]["id"]
    for window, parts in batches:
        for part in parts:
            identity = part["start_id"]
            if window.first <= identity <= window.last and (identity != window.context[0]["id"] or identity == first):
                starts[identity] = dict(part)
    ordered = [starts[key] for key in sorted(starts)]
    if not ordered or ordered[0]["start_id"] != first:
        raise ValueError("주제 분석의 첫 발화 경계를 확인할 수 없습니다.")
    for i, part in enumerate(ordered):
        part["end_id"] = ordered[i + 1]["start_id"] - 1 if i + 1 < len(ordered) else utterances[-1]["id"]
    return ordered
