import copy

from momo.audio_lab.laughter_assignment import EventTimeline, assign
from momo.audio_lab.segment_core import CharacterMotion, assemble_characters
from momo.video_lab.laughter_debug import Diagnostics
from test_character_segments import lane


def speaker_ids(*ids):
    return [dict(speaker_id=s, first_seen_us=0) for s in ids]


def event(start=200, end=800, speaker=None, **kwargs):
    return dict(startMs=start, endMs=end, speaker=speaker, maxScore=.3, segment_id=None, **kwargs)


def test_unknown_laugh_avoids_talker_even_with_high_voice_score_without_moving_speech():
    row = lane("G000001_1", None, laugh=.3)
    row.update(candidates=[dict(speaker="S1", score=.9), dict(speaker="S2", score=.1)], manually_reviewed=True)
    talker = lane("G000001_0", "S1")
    saved = copy.deepcopy(row)
    result, tracks, speech, _ = assemble_characters("test", dict(sha256="0"*64, durationMs=1000), [], [talker, row], speaker_ids("S1", "S2"))
    laugh = result["laughter_events"][0]
    assert laugh["speaker"] == "S2" and laugh["original_speaker"] is None and laugh["requires_review"]
    assert laugh["assignment_method"] == "random_listener"
    assert laugh["assignment_activity"]["eligible_speakers"] == ["S2"]
    assert next(u for u in speech if u["segment_id"] == row["id"])["speakers"] == [] and row["speaker"] is None
    assert all(row[k] == saved[k] for k in ("laughter", "speech", "energy", "utterances"))
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    assert motion.at("P2", 500000)["laughing"] and not motion.at("P2", 500000)["speaking"]
    assert not motion.at("P1", 500000)["laughing"] and not motion.at("P2", 1000000)["laughing"]


def test_source_only_laugh_in_gap_avoids_recent_talker_and_animates_only_its_interval():
    row = lane("G000001_0", "S2", start=0, end=1000)
    original = [dict(startMs=1500, endMs=1900, maxScore=.6)]
    result, tracks, _, _ = assemble_characters("test", dict(sha256="0"*64, durationMs=3000), [], [row], speaker_ids("S1", "S2"), source_events=original)
    assert result["laughter_events"][0]["assignment_method"] == "random_listener"
    assert result["laughter_events"][0]["speaker"] == "S1"
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    assert motion.at("P1", 1500000)["laughing"] and motion.at("P1", 1800000)["mouth"] > 0
    assert not motion.at("P1", 1499999)["laughing"] and not motion.at("P1", 1900000)["laughing"]
    assert motion.event(1600000).active_speaker_ids == ["S1"]
    assert not motion.at("P1", 1600000)["speaking"]


def test_no_evidence_randomizes_across_visible_characters_stably():
    events = [event(i*1000, i*1000+800, origin="original") for i in range(64)]
    repeated = copy.deepcopy(events)
    speakers = []
    assign(events, [], speakers, ["S2", "S1"], seed="file-a")
    assign(repeated, [], [], ["S1", "S2"], seed="file-a")
    assert events == repeated and {e["speaker"] for e in events} == {"S1", "S2"}
    assert {s["speaker_id"] for s in speakers} == {"S1", "S2"}
    assert all(e["assignment_method"] == "random_listener" for e in events)
    fallback = [event()]
    assign(fallback, [], [])
    assert fallback[0]["speaker"] == "S1"


def test_existing_manual_or_acoustic_assignment_is_preserved():
    original = [event(speaker="S2", assignment_source="manual", requires_review=False), event(speaker="S1", assignment_source="acoustic")]
    saved = copy.deepcopy(original)
    assert not assign(original, [], speaker_ids("S1", "S2"), ["S1"])
    assert original == saved


def test_visible_character_preference_ignores_unrendered_voice_candidate():
    row = lane("G000001_0", "S1")
    row["candidates"] = [dict(speaker="S3", score=.9), dict(speaker="S1", score=.8)]
    original = [event(origin="original") for _ in range(4)]
    assign(original, [row], speaker_ids("S1", "S2", "S3"), ["S1", "S2"])
    assert all(e["speaker"] == "S2" for e in original)


def test_overlapping_laughs_remain_active_until_last_end_and_do_not_duplicate_speaker():
    timeline = EventTimeline([event(0, 1000), event(200, 400), event(300, 600)])
    assert len(timeline.at(350)) == 3 and len(timeline.at(700)) == 1
    assert timeline.at(-1) == timeline.at(1000) == []
    events = [event(0, 1000, "S1"), event(200, 400, "S1")]
    motion = CharacterMotion(dict(lanes=[], fallback_laughter_events=events), {"S1": "P1"})
    assert motion.event(300000).active_speaker_ids == ["S1"]
    assert motion.at("P1", 700000)["laughing"]


def test_debug_reports_provisional_event_peak_and_actual_expression_not_fake_lane_score():
    events = [event(origin="original")]
    assign(events, [], speaker_ids("S1"))
    tracks = dict(lanes=[], fallback_laughter_events=events)
    motion = CharacterMotion(tracks, {"S1": "P1"})
    debug = Diagnostics(tracks, motion, [dict(speaker="S1", person_id="P1")])
    snapshot = debug.at(500000, {"S1": dict(laughing=True)})
    row = snapshot["rows"][0]
    assert snapshot["assigned_speakers"] == ["S1"] and not snapshot["unassigned"]
    assert row["fallback"] and row["laughing"] and row["event_peak"] == .3
    assert row["raw"] is None and row["filtered"] is None and row["reason"] == "fallback_original"
    assert debug.at(500000, {"S1": dict(laughing=False)})["rows"][0]["reason"] == "pose"


def test_rerender_assignment_is_idempotent_and_highlight_scores_do_not_change():
    rows = [lane("G000001_0", "S1", laugh=.3)]
    source = dict(sha256="0"*64, durationMs=2000)
    original = [dict(startMs=1200, endMs=1600, maxScore=.4)]
    a = assemble_characters("test", source, [], rows, speaker_ids("S1", "S2"), source_events=original)
    b = assemble_characters("different-render-job", source, [], rows, speaker_ids("S1", "S2"), source_events=original)
    assert a[0]["laughter_events"] == b[0]["laughter_events"] and a[3] == b[3]
    assert len(a[1]["fallback_laughter_events"]) == 1
    assert len(a[3]) == 2 and a[3][1]["maxScore"] == .4


def test_everyone_talking_chooses_smallest_overlap_and_counts_union_not_duplicate_lanes():
    a = lane("G000001_0", "S1")
    b = lane("G000001_1", "S2", start=400, end=600)
    duplicate = copy.deepcopy(b)
    duplicate["id"] = "G000002_0"
    original = [event()]
    assign(original, [a, b, duplicate], speaker_ids("S1", "S2"))
    assert original[0]["speaker"] == "S2" and original[0]["assignment_method"] == "random_least_speech"
    assert original[0]["assignment_activity"]["speaking_ms"] == {"S1": 600, "S2": 200}


def test_word_pause_is_not_treated_as_speech_for_the_whole_subtitle():
    a = lane("G000001_0", "S1")
    a["utterances"][0]["words"] = [dict(startMs=0, endMs=100, text="one"), dict(startMs=900, endMs=1000, text="two")]
    b = lane("G000001_1", "S2", start=300, end=700)
    original = [event(400, 600)]
    assign(original, [a, b], speaker_ids("S1", "S2"))
    assert original[0]["speaker"] == "S1"
    assert original[0]["assignment_activity"]["speaking_ms"] == {"S1": 0, "S2": 200}


def test_random_choice_uses_multiple_listeners_but_never_current_talker():
    talker = lane("G000001_0", "S1", end=10000)
    original = [event(i*200, i*200+160) for i in range(40)]
    assign(original, [talker], speaker_ids("S1", "S2", "S3"), seed="recording")
    assert {e["speaker"] for e in original} == {"S2", "S3"}
    assert all(e["assignment_activity"]["eligible_speakers"] == ["S2", "S3"] for e in original)


def test_duplicate_suppressed_speech_and_laughter_only_vad_do_not_exclude_listener():
    talker = lane("G000001_0", "S1")
    silent = lane("G000001_1", "S2")
    silent["speech_mask"] = [0]*50
    original = [event()]
    assign(original, [talker, silent], speaker_ids("S1", "S2"))
    assert original[0]["speaker"] == "S2"
    del silent["speech_mask"]
    silent["utterances"] = []
    original = [event()]
    assign(original, [talker, silent], speaker_ids("S1", "S2"))
    assert original[0]["speaker"] == "S2"


def test_provisional_laughter_uses_happy_sprite_even_with_static_side_pose():
    from PIL import Image
    from momo.video_lab.render import SheetCharacter
    character = SheetCharacter.__new__(SheetCharacter)
    character.person = dict(pose="left")
    character.frames = {k: Image.new("RGBA", (10, 10), color) for k, color in (("left", "red"), ("happy", "green"))}
    character.place = lambda canvas, sprite, state, center: canvas.alpha_composite(sprite)
    motion = CharacterMotion(dict(lanes=[], fallback_laughter_events=[event(speaker="S1")]), {"S1": "P1"})
    canvas = Image.new("RGBA", (10, 10))
    drawing = character.draw(canvas, motion.at("P1", 500000), False, (0, 0))
    assert drawing["laughing"] and drawing["pose"] == "front"
    assert canvas.getpixel((0, 0)) == character.frames["happy"].getpixel((0, 0))
