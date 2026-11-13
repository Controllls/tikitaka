import math
from pathlib import Path
import tempfile
import unittest

from momo.highlights.selection import group_events
from momo.highlights.common import cached, save_cache, signature
from momo.highlights.selection import utterances_from_segments
from momo.highlights.topics import anchor_ids, boundary_for_event, select_topics, validate_boundary, validate_partition


def window(start, score=.2, music=0):
    return {"startMs": start, "endMs": start + 975, "laughter": score, "music": music}


def utterance(index, start, end):
    return {"id": index, "startMs": start, "endMs": end, "text": f"발화 {index}", "words": []}


class AudioTests(unittest.TestCase):
    def test_threshold_and_music_filter(self):
        events = group_events([window(0, .099), window(10000, .1), window(20000, .9, .6),
            window(30000, .2, .3), window(40000, math.nan)], .1)
        self.assertEqual([10000], [event["startMs"] for event in events])

    def test_adjacent_windows_become_one_event_without_padding(self):
        events = group_events([window(1000), window(1500, .3), window(10000)], .1)
        self.assertEqual(2, len(events))
        self.assertEqual((1000, 2475, 2), (events[0]["startMs"], events[0]["endMs"], events[0]["windowCount"]))

    def test_invalid_threshold(self):
        for threshold in [0, -1, 1.1, math.nan]:
            with self.assertRaises(ValueError):
                group_events([], threshold)

    def test_no_laughter_is_valid_empty_result(self):
        self.assertEqual([], group_events([window(0, 0)], .1))


class BoundaryTests(unittest.TestCase):
    def test_shared_partition_maps_two_laughs_to_the_same_complete_topic(self):
        speech = [utterance(i, i * 1000, i * 1000 + 800) for i in range(1, 10)]
        parts = validate_partition({"topics": [
            {"start_id": 1, "title": "이야기 하나"}, {"start_id": 3, "title": "이야기 둘"},
            {"start_id": 8, "title": "이야기 셋"}]}, speech)
        a = boundary_for_event(parts, {"startMs": 4100, "endMs": 4500}, speech)
        b = boundary_for_event(parts, {"startMs": 6100, "endMs": 6500}, speech)
        self.assertEqual((3, 7), (a["start_id"], a["end_id"]))
        self.assertEqual(a, b)

    def test_laughter_between_speech_includes_both_sides(self):
        self.assertEqual([1, 2], anchor_ids({"startMs": 1500, "endMs": 1800},
            [utterance(1, 0, 1000), utterance(2, 2000, 3000)]))

    def test_partition_endpoints_come_from_next_start(self):
        context = [utterance(i, i * 1000, i * 1000 + 500) for i in range(10, 15)]
        result = validate_partition({"topics": [{"start_id": 10, "title": "첫 이야기"},
            {"start_id": 13, "title": "다음 이야기"}]}, context)
        self.assertEqual([(10, 12), (13, 14)], [(p["start_id"], p["end_id"]) for p in result])

    def test_partition_rejects_missing_prefix_duplicates_and_invented_ids(self):
        context = [utterance(1, 0, 10), utterance(2, 20, 30)]
        for starts in [[2], [1, 1], [1, 3], [True]]:
            with self.assertRaises(ValueError):
                validate_partition({"topics": [{"start_id": s, "title": "주제"} for s in starts]}, context)

    def test_boundary_must_contain_target(self):
        value = {"start_id": 1, "end_id": 2, "title": "주제",
            "needs_more_before": False, "needs_more_after": False}
        with self.assertRaises(ValueError):
            validate_boundary(value, {1, 2, 3}, [3])
        validate_boundary(value, {1, 2, 3}, [2])

    def test_selected_times_are_topic_boundaries_not_fixed_seconds(self):
        utterances = [utterance(1, 1000, 3000), utterance(2, 4000, 9000)]
        events = group_events([window(5000)], .1)
        selected = select_topics([{"start_id": 1, "end_id": 2, "title": "한 주제", "eventId": 1}],
            utterances, events, 20000, 3)
        self.assertEqual((1000, 9000), (selected[0]["startMs"], selected[0]["endMs"]))

    def test_identical_topics_preserve_laughs_outside_word_alignment(self):
        utterances = [utterance(1, 2000, 3000)]
        events = group_events([window(0), window(5000)], .1)
        decisions = [{"start_id": 1, "end_id": 1, "title": "한 주제", "eventId": i} for i in [1, 2]]
        selected = select_topics(decisions, utterances, events, 10000, 3)
        self.assertEqual(1, len(selected))
        self.assertEqual((0, 5975, 2), (selected[0]["startMs"], selected[0]["endMs"], selected[0]["eventCount"]))

    def test_stronger_overlap_wins_and_output_is_chronological(self):
        utterances = [utterance(i, i * 1000, i * 1000 + 900) for i in range(1, 10)]
        events = group_events([window(1000, .2), window(6000, .8)], .1)
        decisions = [{"start_id": 1, "end_id": 5, "title": "약한 후보", "eventId": 1},
            {"start_id": 4, "end_id": 8, "title": "강한 후보", "eventId": 2}]
        selected = select_topics(decisions, utterances, events, 10000, 3)
        self.assertEqual(["강한 후보"], [t["title"] for t in selected])


class TranscriptAndCacheTests(unittest.TestCase):
    def test_utterance_words_keep_original_alignment(self):
        segments = [{"words": [{"word": " 안녕?", "start": 1., "end": 1.5},
            {"word": " 다음", "start": 4., "end": 4.5}, {"word": " 이야기", "start": 4.5, "end": 5.}]}]
        utterances = utterances_from_segments(segments)
        self.assertEqual([(1000, 1500), (4000, 5000)], [(u["startMs"], u["endMs"]) for u in utterances])
        self.assertEqual("다음 이야기", utterances[1]["text"])

    def test_cached_data_cannot_cross_sources_or_parameters(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "cache.json"
            first = signature({"source": "a", "model": 1})
            save_cache(path, first, [1, 2])
            self.assertEqual([1, 2], cached(path, first))
            self.assertIsNone(cached(path, signature({"source": "b", "model": 1})))
            self.assertIsNone(cached(path, signature({"source": "a", "model": 2})))


if __name__ == "__main__":
    unittest.main()
