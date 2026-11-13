"""Real CUDA separation/STT, browser review/retry, v2 video and portable export."""

import json
from pathlib import Path
import sys
import threading
import time
import zipfile

from playwright.sync_api import expect, sync_playwright
from designer_sources_browser import ROOT, create_app, uvicorn
from momo.config import settings


def wait_condition(page, expression, arg=None, timeout=600000):
    deadline = time.monotonic() + timeout / 1000
    while time.monotonic() < deadline:
        if page.evaluate(expression, arg):
            return
        page.wait_for_timeout(200)
    raise AssertionError("Browser condition timed out: " + expression)


def main():
    source = Path(sys.argv[1])
    settings.lab_temp_root = (ROOT / "server/data/models").resolve().parent / "lab-temp"
    output = ROOT / "build/reports/video-lab/character-segments"
    output.mkdir(parents=True, exist_ok=True)
    app = create_app()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=0, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 30
        while not server.started:
            assert thread.is_alive() and time.monotonic() < deadline
            time.sleep(0.05)
        url = f"http://127.0.0.1:{server.servers[0].sockets[0].getsockname()[1]}"
        with sync_playwright() as p:
            browser = p.chromium.launch(channel="msedge", headless=True)
            page = browser.new_page(viewport={"width": 1500, "height": 1100})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            expect(page.locator("#pipeline-mode")).to_have_value("segments")
            expect(page.locator("#segment-options")).to_be_visible()
            expect(page.locator("#model-stt")).to_be_disabled()
            page.locator("#speaker-review-mode").select_option("local")
            page.locator("#audio-file").set_input_files(source)
            page.locator("#run").click()
            expect(page.locator("#run")).to_be_enabled(timeout=600000)
            result = page.evaluate("state.runs[0]")
            (output / "latest-result.json").write_text(
                json.dumps(result, ensure_ascii=False), encoding="utf-8"
            )
            if result and result.get("partial"):
                log = app.state.audio_lab.jobs[result["id"]].directory / "worker.log"
                (output / "failed-worker.log").write_bytes(log.read_bytes())
            assert result and not result.get("partial"), result.get("error") if result else "No result"
            assert result["analysis_policy"] == "character-segments-v1" and not result["analysis_incomplete"]
            assert result["stages"]["stt"]["data"]["separation"]["device"] == "cuda"
            player = page.locator(".video-card video").first
            expect(player).to_be_visible()
            player.evaluate("async v=>{v.muted=true;await v.play();v.pause();}")
            assert player.evaluate("v=>v.videoWidth===540&&!v.error")
            with page.expect_download() as download:
                page.get_by_text("앱 적용 자료 ZIP ↓", exact=True).click()
            download.value.save_as(output / "character-video-bundle.zip")
            with zipfile.ZipFile(output / "character-video-bundle.zip") as archive:
                assert (
                    "character-tracks.json" in archive.namelist()
                    and "app-tracks.json" not in archive.namelist()
                )
                assert json.loads(archive.read("character-tracks.json"))["schema_version"] == "2.0"
            page.locator('[data-tab="characters"]').click()
            expect(page.locator(".segment-card").first).to_be_visible()
            page.locator(".segment-card").first.get_by_text("음성 듣기", exact=True).click()
            wait_condition(
                page, "() => document.querySelector('.segment-player').readyState>=2", timeout=30000
            )
            assert page.locator(".segment-player").evaluate("p=>p.duration>0&&!p.error")
            first = result["characters"]["segments"][0]
            card = page.locator(f'[data-segment-id="{first["id"]}"]')
            card.locator("select").select_option("")
            card.locator(".segment-save").click()
            wait_condition(
                page, "old=>!state.busy&&state.runs[0]?.id!==old", arg=result["id"], timeout=120000
            )
            reviewed = page.evaluate("state.runs[0]")
            assert not reviewed.get("partial"), reviewed.get("error")
            assert reviewed["characters"]["segments"][0]["speaker"] is None
            assert reviewed["stages"]["stt"]["data"]["processed_groups"] == 0
            card = page.locator(f'[data-segment-id="{first["id"]}"]')
            card.locator(".segment-retry").click()
            wait_condition(
                page, "old=>!state.busy&&state.runs[0]?.id!==old", arg=reviewed["id"], timeout=600000
            )
            retried = page.evaluate("state.runs[0]")
            assert not retried.get("partial"), retried.get("error")
            assert retried["stages"]["stt"]["data"]["processed_groups"] == 1
            assert (
                retried["stages"]["stt"]["data"]["reused_groups"] == len(result["characters"]["groups"]) - 1
            )
            page.locator(".audio-results").screenshot(path=str(output / "desktop.png"))
            page.set_viewport_size({"width": 390, "height": 844})
            page.screenshot(path=str(output / "mobile.png"), full_page=True)
            assert page.evaluate("document.documentElement.scrollWidth<=innerWidth+1")
            page.set_viewport_size({"width": 1500, "height": 1100})
            page.locator('[data-tab="video"]').click()
            page.locator(".rerender").click()
            wait_condition(
                page, "old=>!state.busy&&state.runs[0]?.id!==old", arg=retried["id"], timeout=600000
            )
            rerender = page.evaluate("state.runs[0]")
            assert not rerender.get("partial"), rerender.get("error")
            assert rerender["stages"]["stt"]["data"]["processed_groups"] == 0
            assert rerender["video"]["items"]
            assert not errors, errors
            (output / "browser-result.json").write_text(
                json.dumps(
                    dict(original=result, reviewed=reviewed, retried=retried, rerender=rerender),
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            browser.close()
        print(
            json.dumps(
                dict(
                    status="passed",
                    separation_gpu=True,
                    video=True,
                    preview=True,
                    review=True,
                    retry=True,
                    reuse=True,
                )
            )
        )
    finally:
        server.should_exit = True
        thread.join(timeout=30)
        assert not thread.is_alive()


if __name__ == "__main__":
    main()
