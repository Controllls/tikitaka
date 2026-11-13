import copy

import numpy as np

from momo.audio_lab.dialogue_motion import interactions, prepare_speech
from momo.audio_lab.segment_core import CharacterMotion, assemble_characters
from momo.video_lab.gaze import Gaze
from momo.video_lab.render import Renderer, SheetCharacter
from momo.video_lab.scene import Scene, builtins, resolve_scene
from test_character_segments import lane
from test_speaker_review import decision, response, run_claude


SPEAKERS = [dict(speaker_id="S1", first_seen_us=0), dict(speaker_id="S2", first_seen_us=0)]
SOURCE = dict(durationMs=1000, sha256="0" * 64)


def echo_lanes():
    rows = [lane("G000001_0", "S1"), lane("G000001_1", "S2")]
    for i, row in enumerate(rows):
        row["separated"] = True
        row["utterances"][0]["text"] = "S1과 S2 두 사람에 대해 이야기하고 있어요."
        row["candidates"] = [dict(speaker="S2", score=.55 + i * .2), dict(speaker="S1", score=.12)]
    rows[0]["acoustic_speaker"] = None
    rows[1]["acoustic_speaker"] = "S2"
    return rows


def test_duplicate_voice_has_one_caption_and_one_mouth_even_with_two_names():
    rows = echo_lanes()
    raw = copy.deepcopy(rows[0]["utterances"])
    result, tracks, captions, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    assert len(captions) == 1 and captions[0]["speakers"] == ["S2"]
    assert len(result["duplicate_dialogue"]) == 1 and not result["interactions"]
    assert rows[0]["utterances"] == raw  # Original STT remains available for review.
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    assert motion.at("P1", 500000)["mouth"] == 0
    assert motion.at("P2", 500000)["mouth"] > 0
    assert motion.event(500000).active_speaker_ids == ["S2"]


def test_real_overlapping_speech_and_laughing_listener_remain_independent():
    rows = echo_lanes()
    rows[0]["utterances"][0]["text"] = "오늘 어디로 갈까요?"
    rows[1]["utterances"][0]["text"] = "저는 바다에 가고 싶어요."
    rows[0]["candidates"] = [dict(speaker="S1", score=.8), dict(speaker="S2", score=.1)]
    rows[1]["candidates"] = [dict(speaker="S2", score=.8), dict(speaker="S1", score=.1)]
    _, tracks, _, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    assert motion.at("P1", 500000)["mouth"] > 0 and motion.at("P2", 500000)["mouth"] > 0
    rows[1]["utterances"] = []
    rows[1]["laughter"] = [.3] * 50
    _, tracks, _, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    assert motion.at("P2", 500000)["laughing"]
    assert not motion.at("P2", 500000)["speaking"]


def test_no_words_no_talking_and_word_gap_closes_mouth():
    rows = [lane("G000001_0", "S1"), lane("G000001_1", "S2")]
    rows[0]["utterances"][0]["words"] = [dict(startMs=0, endMs=200, text="안녕"), dict(startMs=800, endMs=1000, text="하세요")]
    rows[1]["utterances"] = []
    prepare_speech(rows)
    motion = CharacterMotion(dict(lanes=rows), {"S1": "P1", "S2": "P2"})
    assert motion.at("P1", 500000)["mouth"] == 0
    assert motion.at("P1", 100000)["mouth"] > 0
    assert motion.at("P2", 100000)["mouth"] == 0


def test_confirmed_two_people_saying_same_words_are_not_silenced():
    rows = echo_lanes()
    for row in rows:
        row["manually_reviewed"] = True
    assert prepare_speech(rows) == []
    assert all(any(row["speech_mask"]) for row in rows)


def test_questions_address_listener_not_mentioned_people_and_unknown_stays_unknown():
    utterances = [dict(startMs=1000, endMs=2500, text="S1과 S2가 만났대요.", speakers=["S1"]),
                 dict(startMs=3000, endMs=4500, text="오늘 어디 갈까요?", speakers=["S1"])]
    events = interactions(utterances, SPEAKERS)
    assert len(events) == 1 and events[0]["addressee"] == "S2"
    assert not interactions(utterances, [*SPEAKERS, dict(speaker_id="S3")])
    utterances[1]["speakers"] = ["S1", "S2"]
    assert not interactions(utterances, SPEAKERS)


def test_gaze_uses_screen_seat_direction_and_returns_to_rest():
    people = Scene().model_dump()["people"]
    gaze = Gaze(people, {"driver": 100, "front_passenger": 300},
                [dict(startMs=1000, endMs=2000, speaker="S1", addressee="S2", intent="question")])
    assert gaze.at("S1", 1500000)["pose"] == "right"
    assert gaze.at("S2", 1500000)["pose"] == "left"
    assert gaze.at("S1", 3100000) == {} and gaze.at("S2", 500000) == {}
    assert gaze.at("S1", 900000)["pose_mix"] < 1


def test_claude_question_and_null_addressee_and_invalid_self_target():
    rows = [lane("G000001_0", "S1")]
    choice = decision(rows[0]["id"], "S1")
    choice["dialogue"] = [dict(utterance_index=0, intent="question", addressee="S2")]
    result = run_claude(rows, lambda _: response([choice]))
    assert result["state"] == "complete"
    _, _, utterances, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    events = interactions(utterances, SPEAKERS, rows)
    assert events[0]["source"] == "claude" and events[0]["addressee"] == "S2"
    choice["dialogue"][0]["addressee"] = "S1"
    assert run_claude(rows, lambda _: response([choice]))["state"] == "failed"
    choice["dialogue"][0]["addressee"] = None
    assert run_claude(rows, lambda _: response([choice]))["state"] == "complete"
    assert not interactions(utterances, SPEAKERS, rows)


def test_builtin_renderer_changes_real_pose_without_moving_listener_mouth():
    from types import SimpleNamespace
    from momo.contracts import Speaker
    rows = [lane("G000001_0", "S1", end=3000)]
    rows[0]["utterances"][0]["text"] = "함께 갈까요?"
    result, tracks, _, _ = assemble_characters("test", dict(SOURCE, durationMs=3000), [], rows, SPEAKERS)
    analysis = SimpleNamespace(captions=[], speakers=[Speaker(**s) for s in SPEAKERS])
    scene = resolve_scene(Scene(), builtins(), "me")
    moving = Renderer(scene, analysis, tracks)
    fixed = Renderer({**scene, "config": {**scene["config"], "auto_gaze": False}}, analysis, tracks)
    assert result["interactions"]
    assert moving.motion.at("P2", 1500000)["mouth"] == 0
    assert np.any(np.asarray(moving.frame(1500000)) != np.asarray(fixed.frame(1500000)))


def test_sheet_uses_authored_side_image_for_listening_and_front_talk_for_speech():
    from PIL import Image
    sheet = object.__new__(SheetCharacter)
    sheet.person = dict(pose="front")
    sheet.frames = {name: Image.new("RGBA", (2, 2), color) for name, color in
                    {"neutral": "black", "left": "red", "right": "blue", "talk": "green", "happy": "yellow"}.items()}
    seen = []
    sheet.place = lambda canvas, sprite, state, center: seen.append(sprite.getpixel((0, 0)))
    state = dict(mouth=0, laughing=False, speaking=False, pose="left", pose_mix=1)
    sheet.draw(None, state, False, (0, 0))
    sheet.draw(None, {**state, "mouth": .8, "speaking": True}, False, (0, 0))
    assert seen == [(255, 0, 0, 255), (0, 128, 0, 255)]
