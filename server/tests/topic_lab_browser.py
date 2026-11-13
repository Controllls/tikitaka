"""Run manually with: uv run --isolated --no-project --with playwright python tests/topic_lab_browser.py

Uses the real local web/backend and a mock OpenAI transport on port 8771; no billable calls.
Also captures the actual, unconfigured app on 8770 if it is running.
"""
from pathlib import Path
import json
import os
import subprocess
import time
import urllib.request

from playwright.sync_api import sync_playwright, expect

SERVER = Path(__file__).resolve().parents[1]
OUTPUT = SERVER.parent / "build" / "reports" / "topic-lab"
OUTPUT.mkdir(parents=True, exist_ok=True)

MOCK_SERVER = r'''
import json
import httpx
import uvicorn
import momo.topic_lab as lab
lab.environment_credentials = lambda: None
def respond(request):
    if request.url.path == "/v1/models":
        return httpx.Response(200, json={"data": [{"id": "gpt-test-fast"}, {"id": "gpt-test-quality"}]})
    body = json.loads(request.content)
    if body["model"] == "gpt-test-quota":
        return httpx.Response(429, json={"error": {"message": "private provider error"}})
    ids = [u["id"] for u in json.loads(body["input"])["utterances"]]
    boundaries = [ids[0]] + ([7] if 7 in ids else [])
    if body["model"] == "gpt-test-quality" and 10 in ids:
        boundaries.append(10)
    parts = {"topics": [{"start_id": i, "title": "<img src=x onerror=alert(1)> Topic " + str(i), "reason": "Mock browser validation"} for i in boundaries]}
    return httpx.Response(200, json={"id": "resp_browser_test", "status": "completed", "model": body["model"],
        "output": [{"type": "message", "content": [{"type": "output_text", "text": json.dumps(parts)}]}],
        "usage": {"input_tokens": 120, "output_tokens": 60, "total_tokens": 180,
                  "input_tokens_details": {"cached_tokens": 0}, "output_tokens_details": {"reasoning_tokens": 15}}})
uvicorn.run(lab.create_app(transport=httpx.MockTransport(respond)), host="127.0.0.1", port=8771, access_log=False, log_level="warning")
'''


def main():
    process = subprocess.Popen([str(SERVER / ".venv" / "Scripts" / "python.exe"), "-c", MOCK_SERVER],
                               cwd=SERVER, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        for _ in range(60):
            if process.poll() is not None:
                raise RuntimeError(process.stderr.read().decode("utf-8", errors="replace"))
            try:
                urllib.request.urlopen("http://127.0.0.1:8771/", timeout=1)
                break
            except OSError:
                time.sleep(.25)
        else:
            raise RuntimeError("Mock test server did not start")
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel="msedge", headless=True)
            page = browser.new_page(viewport={"width": 1440, "height": 1100}, device_scale_factor=1)
            errors, dialogs = [], []
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.on("console", lambda message: errors.append(message.text) if "Content Security Policy" in message.text else None)
            page.on("dialog", lambda dialog: (dialogs.append(dialog.message), dialog.dismiss()))
            page.goto("http://127.0.0.1:8771/text")
            expect(page.locator("#input-count")).not_to_have_text("0자")
            page.locator("#connection-button").click()
            page.locator("#api-key").fill("sk-browser-test-not-a-real-key")
            page.locator("#connect").click()
            expect(page.locator("#connection-status")).to_have_text("OpenAI 연결됨")
            expect(page.locator("#api-key")).to_have_value("")
            assert page.evaluate("localStorage.length + sessionStorage.length") == 0
            page.locator("#model-select").select_option("gpt-test-fast")
            page.locator("#run").click()
            expect(page.locator("#metric-topics")).to_have_text("2개")
            expect(page.locator("#metric-clips")).to_have_text("2개")
            expect(page.locator(".topic-card")).to_have_count(2)
            assert page.locator("#result-content img").count() == 0
            page.locator("#tab-clips").click()
            expect(page.locator(".clip-card")).to_have_count(2)
            page.locator("#model-select").select_option("gpt-test-quality")
            page.locator("#run").click()
            expect(page.locator("#metric-topics")).to_have_text("3개")
            expect(page.locator("#history-count")).to_have_text("2")
            page.locator("#tab-compare").click()
            expect(page.locator(".compare-column")).to_have_count(2)
            expect(page.locator(".compare-warning")).to_be_hidden()
            expect(page.locator(".compare-column").nth(0).locator(".topic-card")).to_have_count(2)
            expect(page.locator(".compare-column").nth(1).locator(".topic-card")).to_have_count(3)
            with page.expect_download() as info:
                page.locator("#export").click()
            downloaded = info.value
            downloaded.save_as(OUTPUT / "mock-result.json")
            result = json.loads((OUTPUT / "mock-result.json").read_text(encoding="utf-8"))
            assert result["requested_model"] == "gpt-test-quality"
            assert "sk-browser" not in json.dumps(result)
            page.screenshot(path=str(OUTPUT / "comparison-mock.png"), full_page=True)
            page.locator("#model-select").select_option("__custom")
            page.locator("#custom-model").fill("gpt-test-quota")
            page.locator("#run").click()
            expect(page.locator("#notice")).to_contain_text("사용 한도")
            expect(page.locator("#run")).to_be_enabled()
            expect(page.locator("#history-count")).to_have_text("2")
            page.locator("#transcript-file").set_input_files({"name": "plain.txt", "mimeType": "text/plain", "buffer": "안녕\n반가워".encode()})
            expect(page.locator("#windows")).to_have_value("[]")
            page.locator("#model-select").select_option("gpt-test-fast")
            page.locator("#run").click()
            expect(page.locator("#history-count")).to_have_text("3")
            expect(page.locator("#metric-clips")).to_have_text("0개")
            expect(page.locator(".compare-warning")).to_be_visible()
            page.locator("#tab-clips").click()
            expect(page.locator("#result-content")).to_contain_text("시간 정보")
            page.locator("#connection-button").click()
            page.locator("#disconnect").click()
            expect(page.locator("#connection-status")).to_have_text("API 키 연결")
            page.set_viewport_size({"width": 390, "height": 844})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            page.locator("#tab-compare").click()
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            page.screenshot(path=str(OUTPUT / "mobile-mock.png"), full_page=True)
            assert not errors, errors
            assert not dialogs, dialogs
            # Separate browser cookie jar: capture only the real app's initial state.
            try:
                urllib.request.urlopen("http://127.0.0.1:8770/", timeout=2)
            except OSError:
                print("Real app is not running; skipped optional screenshots on port 8770.")
            else:
                real = browser.new_page(viewport={"width": 1440, "height": 1100}, device_scale_factor=1)
                try:
                    real.goto("http://127.0.0.1:8770/text")
                    expect(real.locator("#input-count")).not_to_have_text("0자")
                    real.screenshot(path=str(OUTPUT / "desktop.png"), full_page=True)
                    real.set_viewport_size({"width": 390, "height": 844})
                    assert real.evaluate("document.documentElement.scrollWidth <= innerWidth")
                    real.screenshot(path=str(OUTPUT / "mobile.png"), full_page=True)
                finally:
                    real.close()
            browser.close()
        print("PASS: key setup, model switch, topics/clips, comparison, export, quota error, TXT import, disconnect, XSS, mobile layout")
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


if __name__ == "__main__":
    main()
