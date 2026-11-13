"""Verify diagnostic controls and playback of a real, cached-recording MP4."""
import json
from pathlib import Path
import sys
import threading
import time

from playwright.sync_api import expect, sync_playwright

from character_segments_browser import wait_condition
from designer_sources_browser import ROOT, create_app, uvicorn
from momo.audio_lab.service import Job, Upload
from speaker_review_browser import Completed


def main():
    output = ROOT / "build/reports/video-lab" / (sys.argv[1] if len(sys.argv) > 1 else "laughter-debug") / "100"
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
            toggle = page.locator("#laughter-debug")
            expect(toggle).to_be_checked()
            toggle.uncheck()
            assert page.evaluate("sceneSettings().laughter_debug") is False
            toggle.check()
            assert page.evaluate("sceneSettings().laughter_debug") is True
            owner = page.evaluate("state.csrf")
            source = next((directory / "designer-assets/source").glob("input.*"))
            upload = Upload(owner, source, result["input"]["name"], result["input"]["sha256"])
            app.state.audio_lab.jobs[result["id"]] = Job(owner, directory, Completed(), time.time(), upload=upload)
            page.evaluate("r=>{remember(r);state.selected=r.id;selectResultTab('video');render();}", result)
            expect(page.get_by_text("웃음 진단 수치가 영상에 포함되어 있습니다.", exact=False)).to_be_visible()
            video = page.locator(".video-card video").first
            expect(video).to_be_visible()
            peak_seconds = validation["snapshot_time_ms"] / 1000
            video.evaluate("async (v,t)=>{v.muted=true;await v.play();v.pause();v.currentTime=t;}", peak_seconds)
            wait_condition(page, "()=>{const v=document.querySelector('.video-card video');return !v.seeking&&v.readyState>=2;}", timeout=30000)
            assert video.evaluate("v=>v.videoWidth===540&&v.duration>100&&!v.error")
            assert abs(video.evaluate("v=>v.currentTime") - peak_seconds) < .1
            page.locator(".video-card").first.screenshot(path=str(output / "video-browser.png"))
            if validation.get("provisional"):
                page.locator('[data-tab="characters"]').click()
                details = page.locator(".provisional-laughs")
                expect(details.locator("summary")).to_have_text(f"웃음 추정 배정 {validation['provisional']}건")
                details.locator("summary").click()
                expect(details).to_contain_text("실제 웃은 사람으로 확인된 결과는 아닙니다.")
                details.screenshot(path=str(output / "assignment-browser.png"))
            if result.get("characters", {}).get("duplicate_dialogue"):
                page.locator('[data-tab="characters"]').click()
                details = page.locator(".duplicate-dialogue")
                details.locator("summary").click()
                expect(details).to_contain_text("남긴 대사:")
                expect(details).to_contain_text("중복 제외:")
                details.locator(".stage-summary").first.screenshot(path=str(output / "duplicate-review-browser.png"))
            if result.get("characters", {}).get("speech_conflicts"):
                page.locator('[data-tab="characters"]').click()
                details = page.locator(".speech-conflicts")
                details.locator("summary").click()
                expect(details).to_contain_text("한 화자만 재생")
                details.screenshot(path=str(output / "speech-conflicts-browser.png"))
            page.set_viewport_size({"width": 390, "height": 844})
            assert page.evaluate("document.documentElement.scrollWidth<=innerWidth+1")
            toggle.scroll_into_view_if_needed()
            page.screenshot(path=str(output / "controls-mobile.png"))
            assert not errors, errors
            validation.update(browser="Edge: diagnostic toggle, real MP4 playback/seek, mobile width passed", js_errors=errors)
            (output / "validation.json").write_text(json.dumps(validation, ensure_ascii=False, indent=2), encoding="utf-8")
            browser.close()
    finally:
        server.should_exit = True
        thread.join(15)
    print("Laughter diagnostic video browser checks passed.")


if __name__ == "__main__":
    main()
