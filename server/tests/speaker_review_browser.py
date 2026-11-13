"""Review cached real audio through the browser; Claude auth/models use a mock."""
import json
from pathlib import Path
import shutil
import threading
import time

import httpx
from playwright.sync_api import expect, sync_playwright

from character_segments_browser import wait_condition
from designer_sources_browser import ROOT, create_app, uvicorn
from momo.audio_lab.service import Job, Upload


class Completed:
    def poll(self):
        return 0


def main():
    output = ROOT / "build/reports/video-lab/claude-speaker-review"
    output.mkdir(parents=True, exist_ok=True)
    prior = Path("D:/MOMO_Development/lab-temp/character-segments-100sec-final")
    source = Path("D:/MOMO_Development/lab-temp/momo-audio-lab-l6uygxfd/b09eb8f3dc0bf41aac756eaa17009279.media")
    def provider(request):
        assert request.url.host == "api.anthropic.com" and request.url.path == "/v1/models"
        return httpx.Response(200, json={"data": [{"id": "claude-test", "display_name": "Claude test model"}], "has_more": False})
    app = create_app(transport=httpx.MockTransport(provider))
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
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            expect(page.locator("#speaker-review-mode")).to_have_value("claude")
            page.locator("#claude-connection-button").click()
            page.locator("#claude-key").fill("browser-test-anthropic-key-123")
            page.locator("#claude-connect").click()
            expect(page.locator("#claude-dialog")).not_to_be_visible()
            expect(page.locator('#claude-model option[value="claude-test"]')).to_have_count(1)
            page.locator("#claude-model").select_option("claude-test")
            assert page.evaluate("speakerReviewSettings()") == {"mode": "claude", "model": "claude-test"}
            assert not page.locator("#claude-key").input_value()
            page.screenshot(path=str(output / "settings-desktop.png"), full_page=True)
            # Seed a copy of a completed real job into this browser's own session.
            original = json.loads((prior / "result.json").read_text(encoding="utf-8"))
            lab = app.state.audio_lab
            directory = lab.root / original["id"]
            shutil.copytree(prior, directory)
            owner = page.evaluate("state.csrf")
            upload = Upload(owner, source, original["input"]["name"], original["input"]["sha256"])
            lab.jobs[original["id"]] = Job(owner, directory, Completed(), time.time(), upload=upload)
            page.evaluate("r=>{remember(r);state.selected=r.id;selectResultTab('characters');render();}", original)
            page.locator("#speaker-review-mode").select_option("local")
            page.locator(".speaker-review-run").click()
            wait_condition(page, "old=>!state.busy&&state.runs[0]?.id!==old", original["id"], 120000)
            result = page.evaluate("state.runs[0]")
            assert not result.get("partial"), result.get("error")
            assert result["speaker_review"]["unassigned_after"] == 0
            assert result["characters"]["provisional_segments"] > 0
            assert result["stages"]["stt"]["data"]["processed_groups"] == 0
            assert result["stages"]["topics"]["reused"]
            expect(page.locator(".assignment-badge").first).to_be_visible()
            page.locator(".segment-card").first.get_by_text("음성 듣기", exact=True).click()
            wait_condition(page, "()=>document.querySelector('.segment-player').readyState>=2", timeout=30000)
            assert page.locator(".segment-player").evaluate("p=>!p.error&&p.duration>0")
            page.screenshot(path=str(output / "review-desktop.png"), full_page=True)
            page.locator('[data-tab="transcript"]').click()
            expect(page.locator(".subtitle-row .assignment-badge").first).to_be_visible()
            page.set_viewport_size({"width": 390, "height": 844})
            page.locator('[data-tab="characters"]').click()
            assert page.evaluate("document.documentElement.scrollWidth<=innerWidth+1")
            page.screenshot(path=str(output / "review-mobile.png"), full_page=True)
            assert not errors, errors
            (output / "browser-result.json").write_text(json.dumps(dict(
                id=result["id"], elapsed_ms=result["elapsed_ms"], review=result["speaker_review"],
                processed_groups=0, topics_reused=True, js_errors=errors, actual_claude_call=False), indent=2), encoding="utf-8")
            browser.close()
    finally:
        server.should_exit = True
        thread.join(15)
    print("Browser auth/models, cached review, playback, badges and mobile layout passed.")


if __name__ == "__main__":
    main()
