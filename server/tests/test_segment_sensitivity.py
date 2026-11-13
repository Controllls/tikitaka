import numpy as np
import pytest

from momo.audio_lab.models import AudioConfig
from momo.audio_lab.segment_core import independent_tracks, plan_segments, assemble_characters, CharacterMotion
from momo.inference.acoustic import laughter_track
from test_audio_lab import audio_client as base_audio_client, fake_process, upload
from test_character_segments import lane

sensitivity_client = base_audio_client


def acoustic():
    return dict(vad=[0.] * 63, energy=[.02] * 100, rms=[.01] * 100,
                frames=[dict(laughter=.02, otherVocal=.001, nonVocal=.001)] * 12)


def test_sensitivity_recovers_weak_scores_without_inflating_them_and_drives_captions_motion():
    base_speech, base_laugh, _, _ = independent_tracks(acoustic(), 2000)
    speech, laughs, candidates, energy = independent_tracks(acoustic(), 2000, threshold=.01)
    assert not np.any(base_laugh) and np.max(laughs) == pytest.approx(.02)
    assert not np.any(base_speech) and not np.any(speech)
    activity = np.zeros((100, 8))
    groups = plan_segments(activity, speech, candidates, 2000, threshold=.01)
    assert groups and not plan_segments(activity, speech, candidates, 2000)
    row = lane("G000001_0", "S1", end=2000, speech=0)
    row.update(laughter=laughs.tolist(), energy=energy.tolist(), laughter_threshold=.005, utterances=[])
    result, tracks, _, events = assemble_characters("test", dict(durationMs=2000, sha256="0"*64), groups, [row],
                                                   [dict(speaker_id="S1", first_seen_us=0)])
    assert events and any(c["kind"] == "laughter" for c in result["captions"])
    assert CharacterMotion(tracks, {"S1": "P1"}).at("P1", 1000000)["laughing"]
    row["laughter_threshold"] = .05
    assert not CharacterMotion(tracks, {"S1": "P1"}).at("P1", 1000000)["laughing"]


def test_default_policy_unchanged_and_nonvocal_guard_survives_high_sensitivity():
    frames = [dict(laughter=.15, otherVocal=.001, nonVocal=.001)] * 4
    assert np.array_equal(laughter_track(frames), laughter_track(frames, threshold_scale=1.))
    assert np.any(laughter_track(frames)) and not np.any(laughter_track(frames, threshold_scale=2.5))
    noisy = [dict(laughter=.02, otherVocal=.5, nonVocal=.7)] * 12
    assert not np.any(laughter_track(noisy, threshold_scale=.01))


def test_segment_config_and_api_accept_sensitivity_while_app_comparison_stays_fixed(sensitivity_client, monkeypatch):
    client, _ = sensitivity_client
    captured = []
    fake_process(monkeypatch, captured)
    config = AudioConfig(pipeline="segments", threshold=.03)
    result = client.post("/api/audio/jobs", json={"upload_id": upload(client), "config": config.model_dump()})
    assert result.status_code == 200, result.text
    assert captured[0]["config"]["threshold"] == .03
    with pytest.raises(ValueError):
        AudioConfig(pipeline="app", threshold=.03)
    with pytest.raises(ValueError):
        AudioConfig(pipeline="segments", threshold=.0001)
