import json
from pathlib import Path
import numpy as np
from momo.inference.features import FeatureExtractor, speech_band, envelope
from momo.inference.diarization import SpeakerCache

REFERENCE = Path(__file__).resolve().parents[2]/"tests/fixtures/numeric-reference.json"


def test_original_js_signal_and_mel():
    data = json.loads(REFERENCE.read_text())
    pcm = np.array(data["pcm"], np.float32)
    assert np.max(np.abs(speech_band(pcm)-data["band"])) < 1e-5
    rms, voice = envelope(pcm)
    assert np.max(np.abs(rms-data["rms"])) < 1e-5
    assert np.max(np.abs(voice-data["voice"])) < 1e-5
    mel = FeatureExtractor().extract(pcm, True).reshape(-1)
    assert np.max(np.abs(mel-data["mel"])) < 1e-5


def test_original_arrival_order_cache():
    data = json.loads(REFERENCE.read_text())["cache"]
    cache = SpeakerCache(data["config"], 3)
    for step in data["steps"]:
        cache.update(np.array(step["embeds"], np.float32).reshape(-1,3),
                     np.array(step["logits"], np.float32).reshape(-1,2),
                     np.array(step["silence"], np.float32),3)
        assert np.max(np.abs(cache.cached().reshape(-1)-step["expected"])) < 1e-5
