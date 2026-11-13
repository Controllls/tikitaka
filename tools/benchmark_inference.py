"""Synthetic speech throughput probe; never a diarization/ASR quality evaluation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from types import SimpleNamespace
import psutil

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/"server"))
parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path, help="Consented speech or clearly labeled synthetic speech")
parser.add_argument("--seconds", type=int, choices=[30, 120, 600], required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
source = args.output/f"input-{args.seconds}.wav"
subprocess.run(["ffmpeg", "-v", "error", "-y", "-stream_loop", "-1", "-i", str(args.source),
                "-t", str(args.seconds), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(source)], check=True)
os.environ.setdefault("MOMO_PROFILE", "development")
from momo.inference.pipeline import Pipeline

stop = threading.Event()
process = psutil.Process()
memory = {"process_peak_rss_bytes": 0, "gpu_total_device_peak_used_mib": 0}
def monitor():
    while not stop.is_set():
        memory["process_peak_rss_bytes"] = max(memory["process_peak_rss_bytes"], process.memory_info().rss)
        try:
            used = int(subprocess.check_output(["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
                                               timeout=5).decode().splitlines()[0])
            memory["gpu_total_device_peak_used_mib"] = max(memory["gpu_total_device_peak_used_mib"], used)
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        stop.wait(.5)
thread = threading.Thread(target=monitor, daemon=True)
thread.start()
try:
    begin = time.monotonic()
    pipeline = Pipeline()
    load_seconds = time.monotonic()-begin
    runs = []
    job = SimpleNamespace(id=f"synthetic-benchmark-{args.seconds}",
        audio_sha256=hashlib.sha256(source.read_bytes()).hexdigest(), options={"speaker_count_hint": 0})
    for label in ("cold_first_run", "warm_second_run"):
        started = time.monotonic()
        result, tracks = pipeline.run(job, source, args.output/f"pcm-{args.seconds}.f32", lambda *_: None, lambda: None)
        elapsed = time.monotonic()-started
        row = dict(run=label, analysis_seconds=round(elapsed,3),
            including_model_load_seconds=round(elapsed+load_seconds if label=="cold_first_run" else elapsed,3),
            stage_seconds={k:round(v,3) for k,v in pipeline.metrics.items()},
            result_bytes=len(result.model_dump_json().encode()), tracks_bytes=len(tracks.model_dump_json().encode()),
            speakers=len(result.speakers), captions=len(result.captions))
        runs.append(row)
        print(json.dumps(row),flush=True)
    report = dict(input_kind="Repeated Windows SAPI Korean synthetic speech; not real conversation quality evidence",
        seconds=args.seconds, provider=pipeline.diarization.provider, model_load_seconds=round(load_seconds,3),
        input_bytes=source.stat().st_size, queue_seconds=None, android_export_seconds=None, dropped_frames=None,
        memory_sampling_seconds=.5, memory_scope="RSS of inference process; GPU device total includes emulator/desktop",
        cold_definition="Fresh process/model load; OS disk cache was not flushed", **memory, runs=runs)
    (args.output/f"benchmark-{args.seconds}.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
finally:
    stop.set()
    thread.join(timeout=5)
