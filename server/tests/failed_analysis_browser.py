"""A failed worker remains visibly failed, including after a page refresh."""
import io
import json
import threading
import time
import wave

from playwright.sync_api import expect, sync_playwright
from designer_sources_browser import ROOT, create_app, uvicorn
from momo.config import settings


def main():
    settings.lab_temp_root = (ROOT / "server/data/models").resolve().parent / "lab-temp"
    app = create_app()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=0, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    output = ROOT / "build/reports/video-lab/gpu-fix"
    output.mkdir(parents=True, exist_ok=True)
    try:
        deadline = time.monotonic() + 30
        while not server.started:
            assert thread.is_alive() and time.monotonic() < deadline
            time.sleep(.05)
        url = f"http://127.0.0.1:{server.servers[0].sockets[0].getsockname()[1]}"
        message = "음성 인식·자막 단계에서 분석 프로세스가 비정상 종료되었습니다 (종료 코드 0xC0000409). 하이라이트 추출이 완료되지 않았습니다."
        status = [{"id": "vad", "label": "음성 구간 감지", "state": "complete", "elapsed_ms": 30},
                  {"id": "stt", "label": "음성 인식·자막", "state": "failed"},
                  {"id": "topics", "label": "주제·하이라이트", "state": "pending"}]
        result = {"id": "native-crash", "created_at": "2026-11-09T12:00:00Z", "partial": True,
                  "input": {"name": "cleanup-test.wav"}, "config": {}, "requested_mode": "topics",
                  "stages": {}, "clips": [], "topics": [], "utterances": [], "artifacts": [],
                  "failed_stage": "stt", "stage_status": status, "error": message, "elapsed_ms": 1000}
        job = {"id": result["id"], "state": "failed", "stage": "stt", "stages": status,
               "error": message, "result": result, "elapsed_ms": 1000}
        submitted = []
        with sync_playwright() as p:
            browser = p.chromium.launch(channel="msedge", headless=True)
            page = browser.new_page(viewport={"width": 1400, "height": 1000})
            errors = []
            page.on("pageerror", lambda e: errors.append(str(e)))

            def jobs(route):
                if route.request.method == "POST":
                    submitted.append(True)
                    data = {"id": "native-crash", "state": "running"}
                else:
                    data = {"jobs": [job] if submitted else []}
                route.fulfill(json=data)

            page.route("**/api/audio/jobs", jobs)
            page.route("**/api/audio/jobs/native-crash", lambda r: r.fulfill(json=job))
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            buffer = io.BytesIO()
            with wave.open(buffer, "wb") as audio:
                audio.setnchannels(1)
                audio.setsampwidth(2)
                audio.setframerate(16000)
                audio.writeframes(b"\0" * 32000)
            page.locator("#audio-file").set_input_files({
                "name": "cleanup-test.wav", "mimeType": "audio/wav", "buffer": buffer.getvalue()})
            page.locator("#extract-highlights").click()
            for refresh in (False, True):
                if refresh:
                    page.reload()
                expect(page.locator("#result-state")).to_have_text("ANALYSIS FAILED", timeout=15000)
                expect(page.locator('.progress-item[data-stage="stt"]')).to_contain_text("실패")
                expect(page.locator('.progress-item[data-stage="topics"]')).to_contain_text("대기")
                expect(page.locator("#result-content")).to_contain_text("생성 중단")
                expect(page.locator("#result-content")).to_contain_text("0xC0000409")
                expect(page.locator("#history")).to_contain_text("생성 안 됨")
                expect(page.locator("#extract-highlights")).to_be_enabled()
            page.locator(".audio-results").screenshot(path=str(output / "native-failure.png"))
            assert not errors, errors
            browser.close()
        print(json.dumps({"status": "passed", "failure_ui": True, "refresh": True}))
    finally:
        server.should_exit = True
        thread.join(timeout=30)
        assert not thread.is_alive()


if __name__ == "__main__":
    main()
