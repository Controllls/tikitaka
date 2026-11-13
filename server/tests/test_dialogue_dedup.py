import copy

from momo.audio_lab.dialogue_dedup import text_match
from momo.audio_lab.dialogue_motion import prepare_speech
from momo.audio_lab.segment_core import CharacterMotion, assemble_characters
from test_dialogue_motion import echo_lanes, SPEAKERS, SOURCE


def utterance(text, start=0, end=1000):
    return dict(text=text, startMs=start, endMs=end, words=[])


def test_four_syllable_duplicate_with_weak_voices_and_single_original_speaker():
    rows = echo_lanes()
    for row in rows:
        row["utterances"] = [utterance("상태에서")]
        row["candidates"] = [dict(speaker="S2", score=.3), dict(speaker="S1", score=.28)]
        row["diarization_evidence"] = dict(S1=.01, S2=.99)
    result, tracks, captions, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    assert len(captions) == 1 and captions[0]["speakers"] == ["S2"]
    assert len(result["duplicate_dialogue"]) == 1
    assert CharacterMotion(tracks, {"S1": "P1", "S2": "P2"}).at("P1", 500000)["mouth"] == 0


def test_split_and_merged_sentences_are_compared_together_and_raw_is_retained():
    rows = echo_lanes()
    rows[0]["utterances"] = [utterance("오늘은 바다에 가고 내일은 산에 갈 거예요")]
    rows[1]["utterances"] = [utterance("오늘은 바다에 가고", 0, 500), utterance("내일은 산에 갈 거예요", 500, 1000)]
    raw = copy.deepcopy(rows[0]["utterances"])
    records = prepare_speech(rows)
    assert len(records) == 1 and records[0]["kept_utterance_indices"] == [0, 1]
    assert not any(rows[0]["speech_mask"]) and rows[0]["utterances"] == raw


def test_corrupted_korean_phrase_uses_voice_and_syllable_evidence():
    rows = echo_lanes()
    rows[0]["utterances"] = [utterance("댓글 좀 달릴꺼에요")]
    rows[1]["utterances"] = [utterance("이런 댓글들이 달릴거에요")]
    records = prepare_speech(rows)
    assert len(records) == 1 and records[0]["method"] == "matching_phrase"


def test_long_exact_duplicate_does_not_require_confident_embeddings():
    rows = echo_lanes()
    for row in rows:
        row["candidates"] = []
    assert len(prepare_speech(rows)) == 1


def test_two_independent_confident_voices_and_short_acknowledgements_remain():
    rows = echo_lanes()
    rows[0]["candidates"] = [dict(speaker="S1", score=.8), dict(speaker="S2", score=.1)]
    rows[1]["candidates"] = [dict(speaker="S2", score=.8), dict(speaker="S1", score=.1)]
    assert not prepare_speech(rows)
    rows = echo_lanes()
    for row in rows:
        row["utterances"] = [utterance("맞아요")]
    assert not prepare_speech(rows)


def test_three_leaked_copies_leave_one_owner_and_are_idempotent():
    rows = echo_lanes()
    third = copy.deepcopy(rows[0])
    third.update(id="G000001_2", speaker="S3")
    rows.append(third)
    first = prepare_speech(rows)
    assert len(first) == 2 and sum(bool(any(r["speech_mask"])) for r in rows) == 1
    assert prepare_speech(rows) == first


def test_word_clock_evidence_can_change_owner_inside_the_same_group():
    rows = echo_lanes()
    for row in rows:
        row["utterances"] = [utterance("상태에서", 0, 400), utterance("다음에는 천천히 이야기해 주세요", 400, 1000)]
        row["candidates"] = [dict(speaker="S1", score=.3), dict(speaker="S2", score=.29)]
        row["utterance_diarization"] = {"0": dict(S1=.01, S2=.99), "1": dict(S1=.99, S2=.01)}
    records = prepare_speech(rows)
    assert len(records) == 2
    assert rows[0]["duplicate_utterance_indices"] == [0]
    assert rows[1]["duplicate_utterance_indices"] == [1]


def test_nonoverlapping_repetition_is_not_suppressed_and_similarity_is_symmetric():
    rows = echo_lanes()
    rows[0]["utterances"] = [utterance("정말 좋은 생각이에요", 0, 400)]
    rows[1]["utterances"] = [utterance("정말 좋은 생각이에요", 500, 1000)]
    assert not prepare_speech(rows)
    a, b = "교수님 윤현의 시절에 즐거쯤을", "교수님 윤희아이징 때문에"
    assert text_match(a, b) == text_match(b, a)


def test_badly_decoded_continuation_needs_neighbor_duplicate_and_local_single_voice():
    rows = echo_lanes()
    rows[0]["utterances"] = [utterance("상태에서", 0, 400), utterance("이건 편임되었거든", 400, 1000)]
    rows[1]["utterances"] = [utterance("상태에서", 0, 400), utterance("좀 그러니까 편집될 거다 이렇게 생각하고", 400, 1000)]
    for row in rows:
        row["utterance_diarization"] = {"0": dict(S1=.01, S2=.99), "1": dict(S1=.02, S2=.98)}
    decisions = prepare_speech(rows)
    assert len(decisions) == 2 and any(d["method"] == "single_speaker_continuation" for d in decisions)
    for row in rows:
        row["utterance_diarization"]["1"] = dict(S1=.6, S2=.8)
    assert len(prepare_speech(rows)) == 1


def test_shared_greeting_does_not_remove_unrelated_following_cross_talk():
    rows = echo_lanes()
    rows[0]["utterances"] = [utterance("반갑습니다", 0, 400), utterance("오늘 어디로 갈까요", 400, 1000)]
    rows[1]["utterances"] = [utterance("반갑습니다", 0, 400), utterance("저는 바다에 가고 싶어요", 400, 1000)]
    for row in rows:
        row["utterance_diarization"] = {"0": dict(S1=.01, S2=.99), "1": dict(S1=.5, S2=.5)}
    assert len(prepare_speech(rows)) == 1
    assert all(1 not in row["duplicate_utterance_indices"] for row in rows)
