"""Live six-model -> MP4 browser check. Start topic-lab.ps1 -Port 8772 first."""
import hashlib
import json
from pathlib import Path
import zipfile

from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build/reports/video-lab/browser"
OUTPUT.mkdir(parents=True,exist_ok=True)


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge",headless=True)
        page = browser.new_page(viewport={"width":1500,"height":1100},accept_downloads=True)
        errors=[]
        page.on("pageerror",lambda e:errors.append(str(e)))
        page.on("console",lambda m:errors.append(m.text) if "Content Security Policy" in m.text else None)
        page.goto("http://127.0.0.1:8772/")
        expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
        assert page.locator("#mode").is_disabled()
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        page.screenshot(path=str(OUTPUT/"desktop-ready.png"),full_page=True)
        template=page.request.get("http://127.0.0.1:8772/api/video/character-template")
        assert template.status==200
        page.locator("#character-file").set_input_files({"name":"my-character.zip","mimeType":"application/zip","buffer":template.body()})
        expect(page.locator("#character-status")).to_contain_text("추가 완료",timeout=30000)
        expect(page.locator("#run")).to_be_enabled(timeout=30000)
        assert page.locator("#person-0-character").input_value().startswith("builtin-") is False
        page.locator("#background-file").set_input_files(ROOT/"app/src/main/assets/momo/resources/backgrounds/highway_rearview_loop_10s.mp4")
        expect(page.locator("#background-status")).to_contain_text("highway",timeout=30000)
        expect(page.locator("#run")).to_be_enabled(timeout=30000)
        page.locator("#preview-scene").click()
        expect(page.locator("#notice")).to_contain_text("갱신",timeout=30000)
        print("PASS source uploads, character ZIP, scene preview",flush=True)
        page.locator("#audio-file").set_input_files(ROOT/"app/src/androidTest/assets/auto-highlight-synthetic.m4a")
        page.locator("#pipeline-mode").select_option("experiment")
        page.locator("#model-vad").select_option("silero-v6")
        page.locator("#model-stt").select_option("whisper-tiny")
        page.locator("#model-diarization").select_option("xvector-cluster")
        page.locator("#model-voice").select_option("xvector")
        page.locator("#scene-output").select_option("both")
        page.locator("#run").click()
        expect(page.locator("#cancel")).to_be_enabled(timeout=30000)
        # Server progress must survive refreshing while work is running.
        page.reload()
        expect(page.locator("#cancel")).to_be_visible(timeout=30000)
        expect(page.locator("#history-count")).to_have_text("1",timeout=300000)
        expect(page.locator(".video-card video").first).to_be_visible()
        player=page.locator(".video-card video").first
        player.evaluate("async v=>{await v.play();v.pause();}")
        assert player.evaluate("v=>v.duration>25&&v.videoWidth===540&&v.videoHeight===960&&!v.error")
        with page.expect_download() as download:
            page.get_by_text("MP4 내려받기 ↓",exact=True).first.click()
        download.value.save_as(OUTPUT/"downloaded-full.mp4")
        with page.expect_download() as download:
            page.get_by_text("앱 적용 자료 ZIP ↓",exact=True).click()
        download.value.save_as(OUTPUT/"app-bundle.zip")
        with zipfile.ZipFile(OUTPUT/"app-bundle.zip") as archive:
            experiment=json.loads(archive.read("experiment.json"))
            assert len(experiment["stages"])==6
            assert experiment["config"]["stt"]=="whisper-tiny"
            assert experiment["config"]["voice"]=="xvector"
            assert len(experiment["video"]["items"])>=2
            for item in json.loads(archive.read("index.json")):
                assert hashlib.sha256(archive.read(item["path"])).hexdigest()==item["sha256"]
            (OUTPUT/"live-result.json").write_text(json.dumps(experiment,ensure_ascii=False,indent=2),encoding="utf-8")
        page.locator(".audio-results").screenshot(path=str(OUTPUT/"completed-video.png"))
        print("PASS live six AI models -> full + highlight MP4, playback, downloads, bundle hashes",flush=True)
        # A source/style change must be independently testable without another AI call.
        page.locator("#scene-background").select_option("builtin-city")
        page.locator("#scene-title").fill("장면만 바꾼 두 번째 영상")
        page.locator("#person-count").select_option("3")
        page.locator("#person-2-pose").select_option("left")
        page.locator("#scene-output").select_option("full")
        page.locator(".rerender").click()
        expect(page.locator("#history-count")).to_have_text("2",timeout=90000)
        with page.expect_download() as download:
            page.locator("#export").click()
        download.value.save_as(OUTPUT/"rerender-result.json")
        rerender=json.loads((OUTPUT/"rerender-result.json").read_text(encoding="utf-8"))
        assert rerender["reused_analysis_id"]==experiment["id"]
        assert rerender["stages"]==experiment["stages"]
        assert len(rerender["video"]["scene"]["people"])==3
        assert len(rerender["video"]["items"])==1
        page.locator('[data-tab="compare"]').click()
        expect(page.locator(".video-compare video")).to_have_count(2)
        page.locator(".audio-results").screenshot(path=str(OUTPUT/"compare.png"))
        page.set_viewport_size({"width":390,"height":844})
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        page.screenshot(path=str(OUTPUT/"mobile.png"),full_page=True)
        assert not errors,errors
        browser.close()
        print("PASS video-only rerender, scene changes, comparison, mobile, no JS/CSP errors",flush=True)


if __name__=="__main__":
    main()
