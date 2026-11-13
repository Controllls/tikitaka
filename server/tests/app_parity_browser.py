"""App preset -> real GPU analysis -> video/bundle -> rerender without inference."""
import json
from pathlib import Path
import sys
import threading
import time
import zipfile

from playwright.sync_api import expect, sync_playwright
from designer_sources_browser import ROOT, create_app, uvicorn
from momo.config import settings


def main():
    source = Path(sys.argv[1])
    settings.lab_temp_root = (ROOT / "server/data/models").resolve().parent / "lab-temp"
    output = ROOT / "build/reports/video-lab/app-parity"
    output.mkdir(parents=True, exist_ok=True)
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
        with sync_playwright() as p:
            browser = p.chromium.launch(channel="msedge", headless=True)
            page = browser.new_page(viewport={"width": 1500, "height": 1100})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            expect(page.locator("#pipeline-mode")).to_have_value("segments")
            page.locator("#pipeline-mode").select_option("app")
            expect(page.locator("#model-stt")).to_be_disabled()
            expect(page.locator("#laughter-sensitivity")).to_be_hidden()
            page.locator("#pipeline-mode").select_option("experiment")
            page.locator("#model-stt").select_option("whisper-tiny")
            page.locator("#threshold").fill("0.001")
            page.locator("#pipeline-mode").select_option("app")
            expect(page.locator("#model-stt")).to_have_value("whisper-small")
            expect(page.locator("#threshold")).to_have_value("0.100")
            expect(page.locator("#model-topics")).to_be_enabled()
            page.locator("#audio-file").set_input_files(source)
            page.locator("#make-video").check()
            page.locator("#scene-output").select_option("full")
            page.locator("#run").click()
            expect(page.locator("#cancel")).to_be_visible(timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=600000)
            result = page.evaluate("state.runs[0]")
            (output / "browser-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            assert not result.get("partial"), result.get("error")
            assert result["config"]["pipeline"] == "app" and result["analysis_policy"] == "app-backend-v1"
            assert result["stages"]["diarization"]["data"]["laughter_recheck"]
            assert result["stages"]["stt"]["data"]["execution"]["device"] == "cuda"
            player = page.locator(".video-card video").first
            expect(player).to_be_visible()
            player.evaluate("async v => {v.muted=true; await v.play();v.pause();}")
            assert player.evaluate("v=>v.videoWidth===540 && v.duration>0 && !v.error")
            with page.expect_download() as download:
                page.get_by_text("앱 적용 자료 ZIP ↓", exact=True).click()
            download.value.save_as(output / "app-parity-bundle.zip")
            with zipfile.ZipFile(output / "app-parity-bundle.zip") as archive:
                names = archive.namelist()
                analysis = json.loads(archive.read(next(n for n in names if n.endswith("app-result.json"))))
                tracks = json.loads(archive.read(next(n for n in names if n.endswith("app-tracks.json"))))
                assert analysis["job_id"] == tracks["job_id"] == result["id"]
                assert not any(w["code"] == "LAB_SEGMENT_ADAPTER" for w in analysis["warnings"])
            page.locator(".audio-results").screenshot(path=str(output / "app-result-screen.png"))
            # Existing video-only reuse must preserve native tracks and skip paid/local AI work.
            button = page.get_by_text("현재 소스로 영상만 다시 만들기", exact=True)
            expect(button).to_be_visible()
            button.click()
            expect(page.locator("#run")).to_be_enabled(timeout=600000)
            rerender = page.evaluate("state.runs[0]")
            assert not rerender.get("partial"), rerender.get("error")
            assert rerender["reused_analysis_id"] == result["id"]
            assert rerender["analysis_policy"] == "app-backend-v1"
            assert not errors, errors
            browser.close()
        print(json.dumps({"status": "passed", "native_pipeline": True, "video": True, "bundle": True, "reuse": True}))
    finally:
        server.should_exit = True
        thread.join(timeout=30)
        assert not thread.is_alive()


if __name__ == "__main__":
    main()
