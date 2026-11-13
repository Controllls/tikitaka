import copy
from types import SimpleNamespace

import numpy as np
import pytest

from momo.audio_lab.laughter_debug import backfill, record, validate
from momo.audio_lab.models import AudioConfig
from momo.audio_lab.segment_core import CharacterMotion
from momo.inference.acoustic import laughter_decisions, laughter_track
from momo.video_lab.laughter_debug import Diagnostics, Trace, number
from test_character_segments import lane


def acoustic(score=.3, other=.01, noise=.01, rms=.01):
    return dict(frames=[dict(laughter=score, otherVocal=other, nonVocal=noise)]*10,
                rms=[rms]*100, energy=[.01]*100, vad=[.9]*63, kinds=["speech"]*100)


def test_raw_above_cutoff_can_fail_competing_voice_rule_and_is_not_shown_as_zero_raw():
    data = record(acoustic(.1, .4), 10000, 10000, 12000, .001)
    validate(data)
    result = Trace(data).at(11000)
    assert result["raw"] == pytest.approx(.1) and result["filtered"] == 0
    assert result["reason"] == "other_voice" and result["other"] == pytest.approx(.4)


def test_audibility_and_absolute_clip_time_are_preserved():
    data = record(acoustic(rms=0), 10000, 10000, 12000, .1)
    result = Trace(data).at(11000)
    assert result["raw"] == pytest.approx(.3) and result["filtered"] == 0 and result["reason"] == "quiet"
    data = record(acoustic(), 10000, 10000, 12000, .1)
    assert Trace(data).at(11000)["filtered"] == pytest.approx(.3)
    data["frames"][0] = dict(laughter=float("nan"), otherVocal=0, nonVocal=0)
    with pytest.raises(ValueError):
        validate(data)
    assert record(acoustic(), 0, 0, 2000.25, .1)["endMs"] == 2000


def test_model_explanation_preserves_existing_laughter_values():
    frames = [dict(laughter=.3, otherVocal=.01, nonVocal=.01), dict(laughter=.1, otherVocal=.5, nonVocal=.01),
              dict(laughter=.1, otherVocal=.01, nonVocal=.9), dict(laughter=.01, otherVocal=0, nonVocal=0)]
    scores, reasons = laughter_decisions(frames)
    assert np.array_equal(scores, laughter_track(frames))
    assert reasons == ["accepted", "other_voice", "non_voice", "score"]


def test_unassigned_detection_and_actual_character_mouth_are_separate():
    unknown = lane("G000001_0", None, end=2000, laugh=.3)
    known = lane("G000001_1", "S1", end=2000, laugh=.3)
    for row in (unknown, known):
        row["laughter_debug"] = record(acoustic(), 0, 0, 2000, .1)
    tracks = dict(lanes=[unknown, known], original_laughter_debug=[record(acoustic(), 0, 0, 2000, .1)],
                  unassigned_laughter_events=[dict(startMs=0, endMs=2000)])
    motion = CharacterMotion(tracks, {"S1": "P1"})
    debug = Diagnostics(tracks, motion, [dict(speaker="S1", person_id="P1")]).at(1000000)
    assert debug["unassigned"] and debug["original"]["detected"]
    missing, assigned = debug["rows"]
    assert missing["detected"] and not missing["laughing"] and missing["mouth"] == 0 and missing["reason"] == "unassigned"
    assert assigned["detected"] and assigned["laughing"] and assigned["mouth"] == motion.at("P1", 1000000)["mouth"]


def test_diagnostic_reclassification_never_overrides_saved_animation_decision():
    row = lane("G000001_0", "S1", end=2000, laugh=0)
    row["laughter_debug"] = record(acoustic(), 0, 0, 2000, .1)
    tracks = dict(lanes=[row])
    motion = CharacterMotion(tracks, {"S1": "P1"})
    values = Diagnostics(tracks, motion, [dict(speaker="S1", person_id="P1")]).at(1000000)["rows"][0]
    assert values["raw"] == pytest.approx(.3) and values["filtered"] == 0
    assert not values["laughing"] and not values["detected"]


def test_rendered_side_pose_reports_expression_off_even_when_motion_detects_laughter():
    row = lane("G000001_0", "S1", end=2000, laugh=.3)
    row["laughter_debug"] = record(acoustic(), 0, 0, 2000, .1)
    tracks = dict(lanes=[row])
    motion = CharacterMotion(tracks, {"S1": "P1"})
    debug = Diagnostics(tracks, motion, [dict(speaker="S1", person_id="P1")])
    result = debug.at(1000000, {"S1": dict(laughing=False, pose="left")})["rows"][0]
    assert result["detected"] and not result["laughing"] and result["reason"] == "pose"
    assert number(0.0000021) != number(0.) and number(None) != number(0.)


def test_missing_scores_remain_missing_and_legacy_is_supported():
    row = lane("G000001_0", "S1")
    tracks = dict(lanes=[row])
    motion = CharacterMotion(tracks, {"S1": "P1"})
    values = Diagnostics(tracks, motion, [dict(speaker="S1", person_id="P1")]).at(500000)
    assert values["rows"][0]["raw"] is None
    assert values["rows"][0]["reason"] == "missing"
    values = Diagnostics(SimpleNamespace(), None, [dict(speaker="S1", person_id="P1")]).at(500000)
    assert values["original"] is None and values["rows"][0]["raw"] is None


def test_old_cache_backfills_only_acoustic_evidence_once(tmp_path):
    calls = []
    class Engine:
        def analyze(self, wave, **kwargs):
            calls.append(len(wave))
            return acoustic()
    rows = [lane("G000001_0", "S1", end=2000)]
    saved = copy.deepcopy(rows)
    base = {}
    engine = Engine()
    _, result = backfill(np.zeros(32000, np.float32), tmp_path, rows, base, tmp_path, AudioConfig(pipeline="segments"), engine, lambda _: None)
    assert result == dict(backfilled_lanes=1, backfilled_original=True)
    assert len(calls) == 2 and base["laughter_debug"]
    assert rows[0]["laughter"] == saved[0]["laughter"] and rows[0]["speaker"] == saved[0]["speaker"]
    _, result = backfill(np.zeros(32000, np.float32), tmp_path, rows, base, tmp_path, AudioConfig(pipeline="segments"), engine, lambda _: None)
    assert len(calls) == 2 and not result["backfilled_lanes"]
