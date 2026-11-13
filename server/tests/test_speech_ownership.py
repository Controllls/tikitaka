import copy
import json

import pytest

from momo.audio_lab.dialogue_motion import prepare_speech, visible_utterances
from momo.audio_lab.segment_core import CharacterMotion, assemble_characters
from momo.audio_lab import worker, segment_worker
from momo.video_lab.characters import load_characters
from test_character_segments import lane, cached_job
from test_dialogue_motion import SPEAKERS, SOURCE


def uncertain_pair():
    rows = [lane("G000001_0", "S1"), lane("G000001_1", "S2")]
    for row, text, score in zip(rows, ["시상하부가 체중을 조절합니다", "개인적인 포인트는"], [.8, .4]):
        row.update(separated=True, candidates=[dict(speaker="S1", score=score), dict(speaker="S2", score=.1)],
                   diarization_evidence=dict(S1=.99, S2=.01))
        row["utterances"][0]["text"] = text
    return rows


@pytest.mark.parametrize("text", ["개인적인 포인트는", "뇌", "ㅎㅎㅎㅎㅎ"])
def test_different_or_short_hallucinated_text_cannot_open_second_mouth(text):
    rows = uncertain_pair()
    rows[1]["utterances"][0]["text"] = text
    raw = copy.deepcopy([r["utterances"] for r in rows])
    result, tracks, captions, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    assert len(captions) == 1 and captions[0]["speakers"] == ["S1"]
    assert result["speech_conflicts"] and not result["duplicate_dialogue"]
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    for now in range(0, 1000000, 20000):
        assert motion.at("P1", now)["speaking"]
        assert not motion.at("P2", now)["speaking"] and motion.at("P2", now)["mouth"] == 0
    assert [r["utterances"] for r in rows] == raw
    derived = copy.deepcopy(rows)
    prepare_speech(rows)
    assert rows == derived


def test_no_voice_evidence_and_three_stems_still_choose_one_playback_owner():
    rows = uncertain_pair()
    rows.append({**copy.deepcopy(rows[1]), "id": "G000001_2", "speaker": "S3"})
    for row in rows:
        row["candidates"] = []
        row["diarization_evidence"] = {}
    prepare_speech(rows)
    assert sum(bool(visible_utterances(r)) for r in rows) == 1
    assert sum(bool(any(r["speech_mask"])) for r in rows) == 1


def test_unique_tail_retains_only_its_words_and_no_padding_reopens_loser():
    rows = uncertain_pair()
    rows[0]["utterances"][0]["endMs"] = 600
    rows[1]["utterances"][0].update(text="잘못된 말 다음 차례", words=[
        dict(startMs=0, endMs=600, text=" 잘못된 말"), dict(startMs=600, endMs=1000, text=" 다음 차례")])
    result, tracks, captions, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    assert [(u["text"], u["startMs"]) for u in captions] == [("시상하부가 체중을 조절합니다", 0), ("다음 차례", 600)]
    motion = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"})
    for now in range(0, 1000000, 10000):
        assert sum(motion.at(p, now)["speaking"] for p in ("P1", "P2")) == 1
    assert result["speech_conflicts"][0]["endMs"] == 600


def test_two_stems_owned_by_same_person_do_not_hide_active_lane():
    rows = uncertain_pair()
    rows[1]["speaker"] = "S1"
    _, tracks, captions, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    assert len(captions) == 1
    for order in (rows, list(reversed(rows))):
        motion = CharacterMotion({**tracks, "lanes": order}, {"S1": "P1", "S2": "P2"})
        assert motion.at("P1", 500000)["mouth"] > 0
        assert motion.event(500000).active_speaker_ids == ["S1"]


def test_verified_crosstalk_manual_unknown_and_laughter_are_respected():
    rows = uncertain_pair()
    rows[1]["candidates"] = [dict(speaker="S2", score=.85), dict(speaker="S1", score=.1)]
    prepare_speech(rows)
    assert all(visible_utterances(r) for r in rows)
    rows[1].update(speaker=None, manually_reviewed=True)
    prepare_speech(rows)
    assert rows[1]["speaker"] is None
    rows = uncertain_pair()
    rows[1]["laughter"] = [.3] * 50
    _, tracks, _, _ = assemble_characters("test", SOURCE, [], rows, SPEAKERS)
    state = CharacterMotion(tracks, {"S1": "P1", "S2": "P2"}).at("P2", 500000)
    assert state["laughing"] and state["mouth"] > 0 and not state["speaking"]


def test_cached_video_rebuild_upgrades_old_forced_assignment_without_audio_or_api(tmp_path, monkeypatch):
    prior, original, _ = cached_job(tmp_path)
    rows = uncertain_pair()
    rows[0].update(acoustic_speaker="S1", assignment_source="acoustic")
    rows[1].update(acoustic_speaker=None, assignment_source="local_provisional")
    original["speaker_review"] = dict(version="claude-speaker-review-v2", mode="local")
    (prior / "result.json").write_text(json.dumps(original), encoding="utf-8")
    (prior / "segment_G000001.json").write_text(json.dumps(rows), encoding="utf-8")
    output = tmp_path / "rebuilt"
    output.mkdir()
    monkeypatch.setattr(segment_worker, "SegmentEngine", lambda *a: pytest.fail("must reuse audio"))
    monkeypatch.setattr(worker, "partition", lambda *a: pytest.fail("must reuse topics"))
    worker.run(dict(id="rebuilt", directory=str(output), source="unused", input=original["input"],
                    config=original["config"], reuse=str(prior / "result.json")))
    data = json.loads((output / "result.json").read_text(encoding="utf-8"))
    assert data["characters"]["animation_policy"] == "dialogue-motion-v3"
    assert data["speaker_review"]["local_assignments_upgraded"]
    assert data["stages"]["stt"]["data"]["processed_groups"] == 0
    assert [s["speaker"] for s in data["characters"]["segments"][:2]] == ["S1", "S1"]
    assert json.loads((prior / "segment_G000001.json").read_text())[1]["speaker"] == "S2"


def test_legacy_preview_upgrades_captions_and_mouths_together(tmp_path):
    rows = uncertain_pair()
    # Construct the old result without applying the new conflict gate.
    prepare_speech(rows, resolve_conflicts=False)
    data = dict(schema_version="2.0", job_id="legacy", source_sha256=SOURCE["sha256"], durationMs=1000,
                speakers=SPEAKERS, groups=[], captions=[{**r["utterances"][0], "speakers": [r["speaker"]],
                "requires_review": True} for r in rows], unresolved_segments=0, failed_segments=0)
    tracks = dict(schema_version="2.0", job_id="legacy", source_sha256=SOURCE["sha256"], durationMs=1000,
                  animation_policy="dialogue-motion-v2", lanes=rows)
    for name, doc in (("character-result.json", data), ("character-tracks.json", tracks)):
        (tmp_path / name).write_text(json.dumps(doc), encoding="utf-8")
    analysis, updated, mapping = load_characters(dict(id="legacy", input=SOURCE), tmp_path)
    assert len(analysis.captions) == 1 and analysis.captions[0].speaker_ids == ["S1"]
    motion = CharacterMotion(updated, mapping)
    assert motion.at("S2", 500000)["mouth"] == 0
