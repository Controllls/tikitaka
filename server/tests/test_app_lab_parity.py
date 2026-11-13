"""App preset contracts and preservation of native inference decisions in the lab."""
import json

import numpy as np
import pytest

from momo.audio_lab.app_pipeline import AppPipeline, POLICY
from momo.audio_lab.models import AudioConfig
from momo.audio_lab import worker, stages
from momo.config import settings
from momo.contracts import validate_pair
from momo.inference.fusion import fixture
from momo.video_lab.adapter import app_analysis


def test_app_is_default_and_alternative_models_require_explicit_experiment():
    assert AudioConfig().pipeline == "app"
    assert AudioConfig().stages() == ["vad", "laughter", "diarization", "stt", "voice", "topics"]
    assert AudioConfig(mode="stt").stages() == ["vad", "laughter", "stt"]
    assert AudioConfig(mode="voice").stages() == ["vad", "laughter", "diarization", "voice"]
    for options in ({"threshold": .001}, {"vad": "silero-v6"}, {"stt": "whisper-tiny"}):
        with pytest.raises(ValueError, match="모델별 실험"):
            AudioConfig(**options)
        assert AudioConfig(pipeline="experiment", **options).pipeline == "experiment"
    assert AudioConfig(topics="openai:test-model").requires_key()


@pytest.mark.parametrize("overlap", [False, True])
def test_recheck_recovers_laugher_outside_vad_without_guessing_current_speaker(monkeypatch, overlap):
    from momo.inference import diarization
    monkeypatch.setattr(settings, "lab_device", "cpu")
    pcm = np.full(48000, .02, np.float32)
    app = AppPipeline(pcm, None, AudioConfig(mode="diarization"), "native", {"sha256": "0" * 64, "name": "test.wav"})
    app.acoustic = {"kinds": ["speech"] * 100 + ["laughter"] * 50,
                    "scores": [.9] * 150, "energy": np.full(150, .02)}
    primary, secondary = np.zeros((150, 8), np.float32), np.zeros((150, 8), np.float32)
    primary[:100, 0] = .78
    primary[100:, 1] = .88  # The active primary speaker is NOT evidence of who laughed.
    if overlap:
        primary[100:, 0] = .8
    secondary[:, 4] = .91  # Remapped from clean speech, independently of raw speaker indexes.
    calls = []

    class Engine:
        def __init__(self, *args): pass
        def analyze(self, signal, **kwargs):
            calls.append(signal)
            return primary.copy() if len(calls) == 1 else secondary.copy()

    monkeypatch.setattr(diarization, "DiarizationEngine", Engine)
    data = app.diarization({"vad": {"data": {"spans": [{"startMs": 0, "endMs": 2000}]}}}, lambda _: None)
    result, tracks = app.assembled()
    validate_pair(result, tracks)
    assert len(calls) == 2 and data["laughter_recheck"]["performed"]
    np.testing.assert_allclose(tracks.speaker_tracks[0].activity[:100], .78)
    if overlap:
        assert not any(e.laughing_speaker_ids for e in result.events)
        assert data["laughter_recheck"]["unknown_ms"] == 1000
    else:
        assert all(e.laughing_speaker_ids == ["S1"] for e in result.events if e.kind == "laughter")
        assert data["laughter_recheck"]["assigned_ms"] == 1000


def test_app_renderer_loads_exact_canonical_tracks_and_captions(tmp_path):
    result, tracks = fixture("native", "0" * 64, 3000000)
    (tmp_path / "app-result.json").write_text(result.model_dump_json(), encoding="utf-8")
    (tmp_path / "app-tracks.json").write_text(tracks.model_dump_json(), encoding="utf-8")
    experiment = {"id": "native", "input": {"sha256": "0" * 64}, "analysis_policy": POLICY}
    loaded, motion, mapping = app_analysis(experiment, np.zeros(48000), tmp_path)
    assert loaded == result and motion == tracks
    assert mapping == {s.speaker_id: s.speaker_id for s in result.speakers}
    experiment["input"]["sha256"] = "f" * 64
    with pytest.raises(ValueError, match="일치"):
        app_analysis(experiment, np.zeros(48000), tmp_path)


def test_app_video_reuse_copies_native_artifacts_and_updates_both_job_ids(monkeypatch, tmp_path):
    original = tmp_path / "original"
    destination = tmp_path / "new"
    original.mkdir()
    destination.mkdir()
    result, tracks = fixture("old", "0" * 64, 3000000)
    (original / "app-result.json").write_text(result.model_dump_json(), encoding="utf-8")
    (original / "app-tracks.json").write_text(tracks.model_dump_json(), encoding="utf-8")
    config = AudioConfig()
    prior = {"id": "old", "stages": {}, "topics": [], "clips": [], "utterances": []}
    (original / "result.json").write_text(json.dumps(prior), encoding="utf-8")
    monkeypatch.setattr(stages, "decode", lambda *_: np.zeros(48000, np.float32))
    monkeypatch.setattr(AppPipeline, "run", lambda *_: pytest.fail("Reuse must not run inference"))
    worker.run({"id": "new", "source": str(tmp_path / "source"), "directory": str(destination),
                "input": {"sha256": "0" * 64, "name": "test.wav"}, "config": config.model_dump(),
                "reuse": str(original / "result.json")})
    experiment = json.loads((destination / "result.json").read_text(encoding="utf-8"))
    loaded, motion, _ = app_analysis(experiment, np.zeros(48000), destination)
    assert loaded.job_id == motion.job_id == "new"
    assert loaded.events == result.events and motion.speaker_tracks == tracks.speaker_tracks


def test_app_asr_preserves_backend_caption_segmentation(monkeypatch, tmp_path):
    from types import SimpleNamespace
    import sys
    calls = []
    class Whisper:
        def __init__(self, *args, **kwargs):
            self.model = SimpleNamespace(unload_model=lambda: calls.append("unload"))
        def transcribe(self, clip, **kwargs):
            calls.append(len(clip))
            word = SimpleNamespace(start=.1, end=.5, word=" hello")
            return iter([SimpleNamespace(start=.1, end=.5, text=" hello", words=[word],
                                         no_speech_prob=0, avg_logprob=0)]), None
    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=Whisper))
    monkeypatch.setattr(settings, "lab_device", "cpu")
    app = AppPipeline(np.zeros(48000, np.float32), tmp_path, AudioConfig(mode="stt"), "test", {"sha256": "0" * 64})
    app.acoustic = {"kinds": ["silence"] * 50 + ["speech"] * 50 + ["silence"] * 50}
    data = app.stt({}, lambda _: None)
    assert calls == [19200, "unload"]  # One second speech plus 100 ms on each side.
    assert data["utterances"][0]["startMs"] == 1000
    assert data["utterances"][0]["endMs"] == 1400
    assert app.captions[0].start_us == 1000000
