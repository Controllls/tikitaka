"""Real browser controls -> local YAMNet worker, with two sensitivity settings.

Optional first argument selects a local audio/video file; the default is the synthetic test recording.
Owns its temporary server and leaves the user's running lab and sessions intact.
"""
import json
from pathlib import Path
import sys
import threading
import time

from playwright.sync_api import sync_playwright, expect
from designer_sources_browser import ROOT, create_app, uvicorn

OUTPUT = ROOT / "build/reports/video-lab/sensitivity"


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "app/src/androidTest/assets/auto-highlight-synthetic.m4a"
    assert source.is_file()
    OUTPUT.mkdir(parents=True, exist_ok=True)
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
            errors, submissions = [], []
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.on("request", lambda r: submissions.append(r.post_data_json)
                    if r.method == "POST" and r.url.endswith("/api/audio/jobs") else None)
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            expect(page.locator("#sensitivity-value")).to_have_text("기본 · 기준 0.100")
            page.locator("#audio-file").set_input_files(source)
            page.locator("#make-video").uncheck()
            page.locator("#mode").select_option("laughter")
            for value in ("", "0", "1.1"):
                page.locator("#threshold").fill(value)
                page.locator("#run").click()
                expect(page.locator("#notice")).to_contain_text("0.001부터 1")
            assert not submissions
            # Keyboard changes the slider toward higher sensitivity; exact numeric entry stays synchronized.
            page.locator('[data-laughter-threshold="0.1"]').click()
            page.locator("#sensitivity-slider").focus()
            page.keyboard.press("ArrowRight")
            assert float(page.locator("#threshold").input_value()) < .1
            page.locator("#threshold").fill("0.015")
            assert float(page.locator("#sensitivity-slider").input_value()) > 50
            page.locator("#threshold").fill("0.001")
            page.locator("#sensitivity-slider").focus()
            page.keyboard.press("ArrowLeft")
            assert float(page.locator("#sensitivity-slider").input_value()) < 100
            runs = []
            for threshold in (.1, .01):
                page.locator(f'[data-laughter-threshold="{threshold}"]').click()
                expect(page.locator("#threshold")).to_have_value(f"{threshold:.3f}")
                if runs:
                    expect(page.locator(".laughter-result")).to_contain_text("적용 기준 0.100")
                page.locator("#run").click()
                expect(page.locator("#sensitivity-slider")).to_be_disabled(timeout=10000)
                expect(page.locator("#result-state")).to_have_text("ANALYSIS COMPLETE", timeout=120000)
                expect(page.locator("#run")).to_be_enabled()
                expect(page.locator(".laughter-result")).to_contain_text(f"적용 기준 {threshold:.3f}")
                run = page.evaluate("state.runs[0]")
                assert run["config"]["threshold"] == threshold
                assert run["executed_stages"] == ["laughter"]
                runs.append(run)
                (OUTPUT / f"result-{threshold}.json").write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
            assert [r["config"]["threshold"] for r in submissions] == [.1, .01]
            # Scores must be the same; only the decision threshold differs between these runs.
            assert runs[0]["stages"]["laughter"]["data"]["windows"] == runs[1]["stages"]["laughter"]["data"]["windows"]
            accepted = [sum(w["laughter"] >= r["config"]["threshold"] and w["laughter"] > w["music"] and w["music"] < .5
                            for w in r["stages"]["laughter"]["data"]["windows"]) for r in runs]
            assert accepted[1] > accepted[0], accepted
            page.locator('[data-tab="compare"]').click()
            expect(page.locator("#result-content")).to_contain_text("적용 기준 0.100")
            expect(page.locator("#result-content")).to_contain_text("적용 기준 0.010")
            page.locator("#laughter-sensitivity").screenshot(path=str(OUTPUT / "desktop.png"))
            page.set_viewport_size({"width": 390, "height": 844})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            page.locator("#laughter-sensitivity").screenshot(path=str(OUTPUT / "mobile.png"))
            assert not errors, errors
            browser.close()
            print(json.dumps(dict(status="passed", file=source.name, thresholds=[.1, .01], accepted_windows=accepted,
                                  events=[len(r["stages"]["laughter"]["data"]["events"]) for r in runs]), ensure_ascii=True), flush=True)
    finally:
        server.should_exit = True
        thread.join(timeout=30)
        assert not thread.is_alive()
        assert not app.state.audio_lab.root.exists()


if __name__ == "__main__":
    main()
