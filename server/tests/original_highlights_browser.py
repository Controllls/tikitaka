"""Real local models -> original MP4/M4A/SRT, via the dedicated web action.

Pass a short video with a detectable laughter candidate as the first argument.
This owns an isolated server; it does not replace the user's lab or use API keys.
"""
import hashlib
import json
from pathlib import Path
import sys
import struct
import tempfile
import threading
import time

from playwright.sync_api import expect, sync_playwright
from designer_sources_browser import ROOT, create_app, uvicorn

OUTPUT = ROOT / "build/reports/video-lab/original-highlights"


def main():
    source = Path(sys.argv[1])
    assert source.is_file()
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    scratch = (ROOT / "server/data/models").resolve().parent / "lab-temp"
    scratch.mkdir(parents=True, exist_ok=True)
    from momo.config import settings
    settings.lab_temp_root = scratch
    app = create_app()
    long_input = tempfile.TemporaryDirectory(prefix="long-browser-qa-", dir=scratch)
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=0, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 30
        while not server.started:
            assert thread.is_alive() and time.monotonic() < deadline
            time.sleep(.05)
        url = f"http://127.0.0.1:{server.servers[0].sockets[0].getsockname()[1]}"
        print(json.dumps({"url": url, "job_root": str(app.state.audio_lab.root)}), flush=True)
        with sync_playwright() as p:
            browser = p.chromium.launch(channel="msedge", headless=True)
            page = browser.new_page(viewport={"width": 1500, "height": 1100})
            errors, submissions = [], []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.on("request", lambda r: submissions.append(r.post_data_json)
                    if r.method == "POST" and r.url.endswith("/api/audio/jobs") else None)
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            expect(page.locator("#execution-mode")).to_contain_text(settings.lab_device.upper())
            page.locator("#pipeline-mode").select_option("experiment")
            page.locator("#extract-highlights").click()
            expect(page.locator("#notice")).to_contain_text("파일을 선택")
            assert not submissions
            # Browser metadata switches a real 345 MB / 3 h input to highlight extraction.
            long_file = Path(long_input.name) / "three-hours.wav"
            length = 10800 * 16000 * 2
            with long_file.open("wb") as stream:
                stream.write(struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", length + 36, b"WAVE", b"fmt ",
                                         16, 1, 1, 16000, 32000, 2, 16, b"data", length))
                stream.seek(44 + length - 1)
                stream.write(b"\0")
            page.locator("#audio-file").set_input_files(long_file)
            expect(page.locator("#make-video")).not_to_be_checked(timeout=30000)
            expect(page.locator("#mode")).to_have_value("topics")
            expect(page.locator("#source-size")).to_contain_text("345.60 MB")
            assert page.evaluate("state.fileDuration") == 10800
            assert page.evaluate("state.file.size") > 80_000_000
            expect(page.locator("#source-limits")).to_contain_text("10.00 GB / 6시간")
            page.locator("#audio-file").set_input_files(source)
            for value in ("", "0", "11", "1.5"):
                page.locator("#max-clips").fill(value)
                page.locator("#extract-highlights").click()
                expect(page.locator("#notice")).to_contain_text("1부터 10 사이의 정수")
            assert not submissions
            page.locator("#max-clips").fill("2")
            # The dedicated action must use topics even when the generic mode is VAD.
            page.locator("#make-video").uncheck()
            page.locator("#mode").select_option("vad")
            page.locator("#model-topics").select_option("openai:custom")
            page.locator("#extract-highlights").click()
            expect(page.locator("#notice")).to_contain_text("주제 분석용 OpenAI 모델")
            assert not submissions
            page.locator("#model-topics").select_option("qwen-local")
            # Leave video checked, an invalid scene, and an unused API model selected.
            page.locator("#make-video").check()
            page.locator("#scene-title").fill("")
            unused_api = page.locator('#model-diarization option[value^="openai:"]').first.get_attribute("value")
            page.locator("#model-diarization").select_option(unused_api)
            page.locator("#threshold").fill("0.01")
            page.locator("#extract-highlights").click()
            try:
                expect(page.locator("#extract-highlights")).to_be_disabled()
            except AssertionError:
                print(json.dumps({"notice": page.locator("#notice").inner_text(), "errors": errors,
                                  "submissions": submissions}, ensure_ascii=True), flush=True)
                raise
            expect(page.locator("#run")).to_be_disabled()
            expect(page.locator("#cancel")).to_be_enabled(timeout=15000)
            expect(page.locator('[data-tab="clips"]')).to_have_attribute("aria-selected", "true")
            expect(page.locator("#extract-highlights")).to_be_enabled(timeout=600000)
            result = page.evaluate("state.runs[0]")
            (OUTPUT / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            for log in app.state.audio_lab.root.rglob("*.log"):
                (OUTPUT / log.name).write_bytes(log.read_bytes())
            assert not result.get("partial"), result.get("error")
            actual_stt = result["stages"]["stt"]["data"]["execution"]
            actual_topics = result["stages"]["topics"]["data"]["execution"]
            if settings.lab_device == "gpu":
                assert actual_stt["device"] == "cuda" and actual_stt["compute_type"] == "float16"
                assert actual_topics["device"] == "gpu" and actual_topics["gpu_layers"] > 0
                expect(page.locator("#result-content")).to_contain_text("CUDA")
                expect(page.locator("#result-content")).to_contain_text(actual_topics["backend"])
            else:
                assert actual_stt["device"] == actual_topics["device"] == "cpu"
            assert len(submissions) == 1
            payload = submissions[0]
            assert payload["scene"] is None
            assert payload["config"]["mode"] == "topics"
            assert payload["config"]["threshold"] == .01
            assert payload["config"]["max_clips"] == 2
            assert payload["config"]["diarization"] == unused_api
            assert result["executed_stages"] == ["vad", "laughter", "stt", "topics"]
            assert "video" not in result
            assert 1 <= len(result["clips"]) <= 2, "Use a video with a detectable laughter candidate."
            expect(page.locator("#make-video")).to_be_checked()
            expect(page.locator("#scene-title")).to_have_value("")
            expect(page.locator("#extract-highlights")).to_be_enabled()
            expect(page.locator("#result-state")).to_have_text("ANALYSIS COMPLETE")
            expect(page.locator("#upload-progress")).to_have_js_property("value", 100)
            expect(page.locator("#result-content")).to_contain_text("원본 하이라이트")
            player = page.locator(".clip-card video").first
            expect(player).to_be_visible()
            deadline = time.monotonic() + 30
            while player.evaluate("v => v.readyState") < 1:
                assert time.monotonic() < deadline
                page.wait_for_timeout(100)
            duration = player.evaluate("v => v.duration")
            assert abs(duration - result["clips"][0]["durationMs"] / 1000) < .15
            player.evaluate("v => { v.muted = true; return v.play(); }")
            deadline = time.monotonic() + 30
            while player.evaluate("v => v.currentTime") <= .1:
                assert time.monotonic() < deadline
                page.wait_for_timeout(100)
            player.evaluate("v => v.pause()")
            for suffix in ("mp4", "m4a", "srt"):
                link = page.locator(f'.clip-card a[download$=".{suffix}"]').first
                with page.expect_download() as pending:
                    link.click()
                download = pending.value
                download.save_as(OUTPUT / download.suggested_filename)
                assert (OUTPUT / download.suggested_filename).stat().st_size > 0
            page.locator("#original-highlights").screenshot(path=str(OUTPUT / "controls-desktop.png"))
            page.locator(".audio-results").screenshot(path=str(OUTPUT / "result-desktop.png"))
            page.set_viewport_size({"width": 390, "height": 844})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            page.locator("#original-highlights").screenshot(path=str(OUTPUT / "controls-mobile.png"))
            page.locator("#result-content").screenshot(path=str(OUTPUT / "result-mobile.png"))
            page.reload()
            expect(page.locator('[data-tab="clips"]')).to_have_attribute("aria-selected", "true", timeout=30000)
            expect(page.locator(".clip-card video")).to_have_count(len(result["clips"]))
            page.locator(".use-clip").first.click()
            expect(page.locator("#source-name")).to_have_text(result["clips"][0]["title"] + ".m4a")
            expect(page.locator("#make-video")).to_be_checked()
            expect(page.locator("#run")).to_be_enabled()
            expect(page.locator("#notice")).to_contain_text("추출한 클립")
            assert not errors, errors
            browser.close()
            assert hashlib.sha256(source.read_bytes()).hexdigest() == before
            print(json.dumps({"status": "passed", "clips": len(result["clips"]),
                              "duration": duration, "stages": result["executed_stages"],
                              "elapsed_ms": result["elapsed_ms"], "stt": actual_stt,
                              "topics": actual_topics}), flush=True)
    finally:
        server.should_exit = True
        thread.join(timeout=30)
        assert not thread.is_alive()
        assert not app.state.audio_lab.root.exists()
        long_input.cleanup()


if __name__ == "__main__":
    main()
