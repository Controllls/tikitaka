import numpy as np
import pytest
from momo.contracts import Event, SpeakerSignature, Interval, AnalysisResult
from momo.inference.fusion import fixture
from momo.inference.voice import VoiceEncoder, speech_segments


def event(start, end, speakers=("S1",), **kw):
    return Event(event_id=str(start), start_us=start*1_000_000, end_us=end*1_000_000,
                 kind="speech", active_speaker_ids=list(speakers), score=.9, **kw)


def test_overlap_uncertain_laughter_and_short_utterances_are_not_enrolled():
    events = [event(0, 8), event(8, 12, ("S1", "S2")), event(12, 17, requires_review=True),
              event(17, 20, laughing_speaker_ids=["S1"]), event(20, 21), event(21, 25, ("S2",))]
    segments = speech_segments(events, "S1")
    assert sum(x.end_us-x.start_us for x in segments) == 7_800_000
    assert all(100_000 <= x.start_us < x.end_us <= 7_900_000 for x in segments)


def test_extraction_bounded_for_ten_minute_monologue():
    segments = speech_segments([event(0, 600)], "S1")
    assert sum(x.end_us-x.start_us for x in segments) == 30_000_000
    assert len(segments) == 5


def test_silence_and_inconsistent_clusters_do_not_create_identities():
    result, _ = fixture("voice", "a"*64, 10_000_000)
    result.events = [event(0, 10)]
    encoder = object.__new__(VoiceEncoder)
    encoder.encode = lambda _: np.eye(192, dtype=np.float32)[0]
    assert encoder.analyze(np.zeros(160000, np.float32), result, lambda: None) == []
    calls = iter([np.eye(192)[0], -np.eye(192)[0]])
    encoder.encode = lambda _: next(calls)
    assert encoder.analyze(np.full(160000, .1, np.float32), result, lambda: None) == []


@pytest.mark.parametrize("mutation", ["nan", "norm", "duration", "overlap", "speaker", "model", "outside"])
def test_invalid_voice_contract_rejected(mutation):
    result, _ = fixture("voice", "a"*64, 30_000_000)
    result.voice_model_id = "test-encoder"
    result.speaker_signatures = [SpeakerSignature(speaker_id="S1", embedding=[1.]+[0.]*191, speech_us=6_000_000,
        segments=[Interval(start_us=1_000_000, end_us=4_000_000), Interval(start_us=5_000_000, end_us=8_000_000)])]
    data = result.model_dump()
    signature = data["speaker_signatures"][0]
    if mutation == "nan":
        signature["embedding"][0] = float("nan")
    if mutation == "norm":
        signature["embedding"][0] = 10
    if mutation == "duration":
        signature["speech_us"] = 7_000_000
    if mutation == "overlap":
        signature["segments"][1] = dict(start_us=2_000_000, end_us=5_000_000)
    if mutation == "speaker":
        signature["speaker_id"] = "S8"
    if mutation == "model":
        data["voice_model_id"] = None
    if mutation == "outside":
        signature["segments"][1] = dict(start_us=30_000_000, end_us=33_000_000)
    with pytest.raises(ValueError):
        AnalysisResult.model_validate(data)
