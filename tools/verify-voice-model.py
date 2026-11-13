"""Public SpeechBrain samples only; inference smoke test, not real-car accuracy calibration."""
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

import numpy as np

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root/"server"))
from momo.inference.voice import VoiceEncoder  # noqa: E402
from momo.prepare_voice_model import MODEL_ID, REVISION  # noqa: E402

directory = root/"build/voice-model-smoke"
directory.mkdir(parents=True, exist_ok=True)
samples = []
for name in ["example1.wav", "example2.flac"]:
    source = directory/name
    if not source.exists():
        urllib.request.urlretrieve(f"https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/resolve/{REVISION}/{name}", source)
    pcm = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(source), "-ac", "1", "-ar", "16000", "-f", "f32le", "-"])
    samples.append(np.frombuffer(pcm, np.float32))
encoder = VoiceEncoder(root/"server/data/models")
a, b = [encoder.encode(pcm) for pcm in samples]
gain = encoder.encode(samples[0]*.6)
result = dict(model_id=MODEL_ID, dimensions=len(a), source="SpeechBrain model repository public examples",
              same_clip_gain_cosine=float(a @ gain), different_sample_cosine=float(a @ b),
              note="Smoke test only; repeated/gain-adjusted audio does not measure cross-session accuracy.")
assert len(a) == 192 and np.isfinite(a).all() and np.isfinite(b).all()
assert result["same_clip_gain_cosine"] > .9 and result["different_sample_cosine"] < .65
(directory/"result.json").write_text(json.dumps(result, indent=2)+"\n", encoding="utf-8")
print(json.dumps(result))
