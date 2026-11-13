"""Live local-model browser smoke test. Start topic-lab.ps1 -Port 8772 first."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build/reports/audio-lab/browser"
OUTPUT.mkdir(parents=True, exist_ok=True)


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge", headless=True,
            args=["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"])
        page = browser.new_page(viewport={"width": 1500, "height": 1100}, accept_downloads=True)
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("console", lambda msg: errors.append(msg.text) if "Content Security Policy" in msg.text else None)
        page.goto("http://127.0.0.1:8772/")
        expect(page.locator(".model-card")).to_have_count(6)
        expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
        page.locator("#make-video").uncheck()
        page.locator('[data-tab="overview"]').click()
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        page.screenshot(path=str(OUTPUT / "desktop-ready.png"), full_page=True)
        page.locator("#audio-file").set_input_files(ROOT / "app/src/androidTest/assets/auto-highlight-synthetic.m4a")
        page.locator("#mode").select_option("vad")
        page.locator("#pipeline-mode").select_option("experiment")
        page.locator("#model-vad").select_option("silero-v6")
        page.locator("#run").click()
        expect(page.locator("#history-count")).to_have_text("1", timeout=60000)
        expect(page.locator("#notice")).to_contain_text("분석 완료")
        print("PASS live upload + Silero 6", flush=True)
        page.locator("#mode").select_option("all")
        page.locator("#model-stt").select_option("whisper-tiny")
        page.locator("#model-diarization").select_option("xvector-cluster")
        page.locator("#model-voice").select_option("xvector")
        page.locator("#run").click()
        expect(page.locator("#history-count")).to_have_text("2", timeout=240000)
        expect(page.locator("#notice")).to_contain_text("분석 완료")
        expect(page.locator(".stage-summary")).to_have_count(6)
        page.locator('[data-tab="transcript"]').click()
        expect(page.locator(".subtitle-row").first).to_be_visible()
        page.locator('[data-tab="clips"]').click()
        expect(page.locator(".clip-card audio").first).to_be_visible()
        audio = page.locator(".clip-card audio").first
        audio.evaluate("async a => { await a.play(); a.pause(); }")
        assert audio.evaluate("a => a.duration > 0 && !a.error")
        with page.expect_download() as info:
            page.get_by_text("M4A 음성 다운로드", exact=True).first.click()
        info.value.save_as(OUTPUT / "downloaded-clip.m4a")
        with page.expect_download() as info:
            page.locator("#export").click()
        info.value.save_as(OUTPUT / "browser-result.json")
        result = json.loads((OUTPUT / "browser-result.json").read_text(encoding="utf-8"))
        assert len(result["stages"]) == 6
        assert result["config"]["vad"] == "silero-v6"
        assert result["config"]["voice"] == "xvector"
        assert result["config"]["qwen_thinking"] is False
        assert result["clips"]
        page.screenshot(path=str(OUTPUT / "desktop-clips.png"), full_page=True)
        page.locator('[data-tab="compare"]').click()
        expect(page.locator("#compare-0")).to_be_visible()
        page.screenshot(path=str(OUTPUT / "desktop-compare.png"), full_page=True)
        page.set_viewport_size({"width": 390, "height": 844})
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        page.screenshot(path=str(OUTPUT / "mobile.png"), full_page=True)
        page.set_viewport_size({"width": 1500, "height": 1100})
        page.locator("#mode").select_option("all")
        page.locator("#run").click()
        expect(page.locator("#cancel")).to_be_visible()
        page.locator("#cancel").click()
        expect(page.locator("#run")).to_be_enabled(timeout=30000)
        expect(page.locator("#notice")).to_contain_text("취소")
        page.locator("#audio-file").set_input_files({"name": "bad.wav", "mimeType": "audio/wav", "buffer": b"not an audio file"})
        page.locator("#mode").select_option("vad")
        page.locator("#run").click()
        expect(page.locator("#run")).to_be_enabled(timeout=30000)
        expect(page.locator("#notice")).to_contain_text("음성을 읽을 수 없습니다")
        # Test key-dialog UI without an external API call.
        page.route("**/api/models", lambda route: route.fulfill(json={"models": ["gpt-ui-test"]}))
        page.locator("#connection-button").click()
        page.locator("#api-key").fill("sk-browser-mock-not-a-real-key")
        page.locator("#connect").click()
        expect(page.locator("#connection-dialog")).not_to_be_visible()
        expect(page.locator("#api-key")).to_have_value("")
        page.locator("#model-topics").select_option("openai:custom")
        page.locator("#gpt-model").select_option("gpt-ui-test")
        page.locator("#connection-button").click()
        page.locator("#disconnect").click()
        page.locator("#record").click()
        expect(page.locator("#record")).to_have_class("secondary recording")
        page.wait_for_timeout(2200)
        page.locator("#record").click()
        expect(page.locator("#source-name")).to_contain_text("recording-")
        page.locator("#mode").select_option("vad")
        page.locator("#run").click()
        expect(page.locator("#run")).to_be_enabled(timeout=30000)
        expect(page.locator("#notice")).to_contain_text("분석 완료")
        assert page.evaluate("localStorage.length + sessionStorage.length") == 0
        assert not errors, errors
        browser.close()
        print("PASS live six-stage pipeline, model routing, audio playback/download, comparison, mobile, cancel, invalid audio, key UI", flush=True)


if __name__ == "__main__":
    main()
