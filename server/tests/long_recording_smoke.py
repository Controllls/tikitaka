"""Three-hour WAV upload and real highlight analysis, with speech near the end.

Usage: uv run --no-project --python 3.11 --with playwright python tests/long_recording_smoke.py SAMPLE
Uses three hours of silence with the supplied short recording at the end.
This verifies length/size handling and original timestamps, not three hours of speech throughput.
"""
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time

from designer_sources_browser import ROOT, create_app, uvicorn
import httpx
import psutil
from momo.config import settings


def main():
    sample = Path(sys.argv[1])
    scratch = (ROOT / "server/data/models").resolve().parent / "lab-temp"
    scratch.mkdir(parents=True, exist_ok=True)
    settings.lab_temp_root = scratch
    output = ROOT / "build/reports/video-lab/long-recording"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="long-recording-qa-", dir=scratch) as temporary:
        source = Path(temporary) / "three-hours.wav"
        tail = subprocess.run([settings.ffmpeg, "-v", "error", "-i", str(sample), "-t", "20", "-vn",
                               "-ac", "1", "-ar", "16000", "-f", "s16le", "pipe:1"],
                              check=True, capture_output=True, timeout=30).stdout
        length = 10800 * 16000 * 2
        with source.open("wb") as stream:
            stream.write(struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", length + 36, b"WAVE", b"fmt ",
                                     16, 1, 1, 16000, 32000, 2, 16, b"data", length))
            stream.seek(44 + length - len(tail))
            stream.write(tail)
        app = create_app()
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=0, log_level="warning"))
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 30
            while not server.started:
                assert thread.is_alive() and time.monotonic() < deadline
                time.sleep(.05)
            url = f"http://127.0.0.1:{server.servers[0].sockets[0].getsockname()[1]}"
            with httpx.Client(base_url=url, timeout=120) as client:
                client.headers["X-Lab-Token"] = client.get("/api/session").json()["csrf"]
                with source.open("rb") as stream:
                    upload = client.post("/api/audio/uploads?filename=three-hours.wav",
                        content=iter(lambda: stream.read(1024 * 1024), b""), headers={"Content-Type": "application/octet-stream"})
                assert upload.status_code == 200, upload.text
                assert upload.json()["bytes"] == source.stat().st_size > 80_000_000
                job = client.post("/api/audio/jobs", json={"upload_id": upload.json()["upload_id"],
                    "config": {"mode": "topics", "threshold": .01, "max_clips": 1}})
                assert job.status_code == 200, job.text
                identity = job.json()["id"]
                process = psutil.Process(app.state.audio_lab.jobs[identity].process.pid)
                peak_rss, previous = 0, None
                deadline = time.monotonic() + 1800
                while True:
                    status = client.get(f"/api/audio/jobs/{identity}").json()
                    try:
                        peak_rss = max(peak_rss, sum(p.memory_info().rss for p in [process, *process.children(recursive=True)]))
                    except psutil.NoSuchProcess:
                        pass
                    marker = (status.get("stage"), int(status.get("stage_progress", {}).get("percent", 0)) // 10)
                    if marker != previous:
                        print(json.dumps({"stage": marker[0], "percent": status.get("stage_progress", {}).get("percent"),
                                          "elapsed_ms": status.get("elapsed_ms")}), flush=True)
                        previous = marker
                    if status["state"] != "running":
                        break
                    assert time.monotonic() < deadline, "Long recording test timed out"
                    time.sleep(2)
                assert status["state"] == "complete", status.get("error")
                result = status["result"]
                assert result["input"]["durationMs"] == 10800000
                assert result["utterances"] and min(u["startMs"] for u in result["utterances"]) > 10770000
                assert len(result["clips"]) == 1
                clip = result["clips"][0]
                assert 10770000 < clip["startMs"] < clip["endMs"] <= 10800000
                for key in ("audio_file", "subtitle_file"):
                    artifact = client.get(f"/api/audio/jobs/{identity}/files/{clip[key]}")
                    assert artifact.status_code == 200
                    (output / clip[key]).write_bytes(artifact.content)
                report = {"status": "passed", "source_seconds": 10800, "source_bytes": source.stat().st_size,
                          "peak_worker_tree_rss_bytes": peak_rss, "elapsed_ms": result["elapsed_ms"],
                          "clip": clip, "stages": result["executed_stages"],
                          "fixture": "silence plus 20 seconds of speech at the end"}
                (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
                print(json.dumps({k: v for k, v in report.items() if k != "clip"}), flush=True)
        finally:
            server.should_exit = True
            thread.join(timeout=30)
            assert not thread.is_alive()
            assert not app.state.audio_lab.root.exists()


if __name__ == "__main__":
    main()
