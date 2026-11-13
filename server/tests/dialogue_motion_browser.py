"""Play the corrected real MP4 and inspect dialogue controls in Edge."""
import json
from pathlib import Path
import threading
import time

from playwright.sync_api import expect, sync_playwright

from character_segments_browser import wait_condition
from designer_sources_browser import ROOT, create_app, uvicorn
from momo.audio_lab.service import Job, Upload
from speaker_review_browser import Completed


def main():
    output = ROOT / "build/reports/video-lab/dialogue-motion"
    validation = json.loads((output / "validation.json").read_text(encoding="utf-8"))
    directory = Path(validation["directory"])
    result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
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
            expect(page.locator("#auto-gaze")).to_be_checked()
            page.locator("#auto-gaze").uncheck()
            assert not page.evaluate("sceneSettings().auto_gaze")
            page.locator("#auto-gaze").check()
            owner = page.evaluate("state.csrf")
            source = next((directory / "designer-assets/source").glob("input.*"))
            upload = Upload(owner, source, result["input"]["name"], result["input"]["sha256"])
            app.state.audio_lab.jobs[result["id"]] = Job(owner, directory, Completed(), time.time(), upload=upload)
            page.evaluate("r=>{remember(r);state.selected=r.id;selectResultTab('video');render();}", result)
            video = page.locator(".video-card video").first
            expect(video).to_be_visible()
            video.evaluate("async v=>{v.muted=true;await v.play();v.pause();v.currentTime=43.6;}")
            wait_condition(page, "()=>document.querySelector('.video-card video').readyState>=2", timeout=30000)
            assert video.evaluate("v=>v.videoWidth===540&&v.duration>100&&!v.error")
            page.locator(".video-card").first.screenshot(path=str(output / "video-browser.png"))
            page.locator('[data-tab="characters"]').click()
            expect(page.get_by_text("질문·말 걸기 판단 보기", exact=True)).to_be_visible()
            page.get_by_text("질문·말 걸기 판단 보기", exact=True).click()
            page.locator("#result-content").screenshot(path=str(output / "dialogue-browser.png"))
            page.set_viewport_size({"width": 390, "height": 844})
            assert page.evaluate("document.documentElement.scrollWidth<=innerWidth+1")
            page.locator("#auto-gaze").scroll_into_view_if_needed()
            page.screenshot(path=str(output / "controls-mobile.png"))
            assert not errors, errors
            validation.update(browser="Edge playback, gaze toggle, interaction review, mobile width passed", js_errors=errors)
            (output / "validation.json").write_text(json.dumps(validation, ensure_ascii=False, indent=2), encoding="utf-8")
            browser.close()
    finally:
        server.should_exit = True
        thread.join(15)
    print("Corrected MP4 and dialogue browser checks passed.")


if __name__ == "__main__":
    main()
