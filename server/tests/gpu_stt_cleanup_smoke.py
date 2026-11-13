"""Hardware regression: CUDA sampling must survive model destruction on Windows.

Run in a separate process; a native cleanup crash cannot be caught by pytest.
Usage: python tests/gpu_stt_cleanup_smoke.py path/to/audio.f32
"""
import faulthandler
import gc
from pathlib import Path
import sys
import time

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from momo.audio_lab.execution import stt_execution
from momo.audio_lab.models import model_root

faulthandler.enable()
root = model_root()
execution = stt_execution(root)
import ctranslate2
from faster_whisper import WhisperModel

print(f"CTranslate2 {ctranslate2.__version__}; {execution}", flush=True)
pcm = np.memmap(sys.argv[1], dtype=np.float32, mode="r")
started = time.monotonic()
model = WhisperModel(str(root / "whisper"), device="cuda", compute_type="float16",
                     local_files_only=True, num_workers=1, cpu_threads=4)
# Force the sampling path which long recordings enter through temperature fallback.
segments, _ = model.transcribe(np.array(pcm[:28 * 16000]), language="ko", temperature=1.0,
                              word_timestamps=True, condition_on_previous_text=False)
count = sum(1 for _ in segments)
print(f"transcription complete: {count} segments", flush=True)
model.model.unload_model()
print("weights unloaded", flush=True)
del model
gc.collect()
print(f"model destroyed successfully in {time.monotonic() - started:.2f}s", flush=True)
pcm._mmap.close()
