"""Exercise real folder uploads in Edge; own a temporary server with graceful cleanup."""
from pathlib import Path
import shutil
import site
import sys
import tempfile
import threading
import time

from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[2]
site.addsitedir(str(ROOT/"server/.lab-venv/Lib/site-packages"))
sys.path.insert(0,str(ROOT/"server"))
import uvicorn
from momo.topic_lab import create_app

OUTPUT=ROOT/"build/reports/video-lab/designer-sources"
OUTPUT.mkdir(parents=True,exist_ok=True)


def main():
    app=create_app()
    server=uvicorn.Server(uvicorn.Config(app,host="127.0.0.1",port=0,log_level="warning"))
    thread=threading.Thread(target=server.run,daemon=True)
    thread.start()
    try:
        deadline=time.monotonic()+30
        while not server.started:
            if not thread.is_alive() or time.monotonic()>deadline:
                raise RuntimeError("Temporary preview server did not start")
            time.sleep(.05)
        url=f"http://127.0.0.1:{server.servers[0].sockets[0].getsockname()[1]}"
        with tempfile.TemporaryDirectory(prefix="momo-designer-test-") as folder,sync_playwright() as p:
            source=ROOT/"app/src/main/assets/momo/resources"
            temporary=Path(folder)
            shutil.copytree(source/"characters",temporary/"디자이너 캐릭터")
            browser=p.chromium.launch(channel="msedge",headless=True)
            page=browser.new_page(viewport={"width":1500,"height":1100})
            errors=[]
            page.on("pageerror",lambda e:errors.append(str(e)))
            page.on("console",lambda m:errors.append(m.text) if "Content Security Policy" in m.text else None)
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            expect(page.locator(".designer-source")).to_have_count(3)
            for category in ("background","character","vehicles"):
                assert page.locator(f"#{category}-pick").is_visible()
                assert page.locator(f"#{category}-folder-pick").is_visible()
            page.locator("#background-folder").set_input_files(source/"backgrounds")
            expect(page.locator("#background-status")).to_contain_text("추가 완료",timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            assert page.locator("#scene-background option").count()==4
            assert not page.locator("#scene-background").input_value().startswith("builtin-")
            print("PASS background folder, real media validation, automatic preview",flush=True)
            page.locator("#character-folder").set_input_files(temporary/"디자이너 캐릭터")
            expect(page.locator("#character-status")).to_contain_text("추가 완료 · 2개",timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            assert not page.locator("#person-0-character").input_value().startswith("builtin-")
            assert not page.locator("#person-1-character").input_value().startswith("builtin-")
            assert page.locator("#person-0-character").input_value()!=page.locator("#person-1-character").input_value()
            print("PASS two-character folder, Unicode paths, automatic person assignment",flush=True)
            page.locator("#vehicles-folder").set_input_files(source/"vehicles")
            expect(page.locator("#vehicles-status")).to_contain_text("추가 완료 · 3개",timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            for kind in ("vehicle","seatbelts","steering_wheel"):
                assert not page.locator(f"#scene-{kind}").input_value().startswith("builtin-")
            print("PASS vehicles folder and all three layer assignments",flush=True)
            # A bad upload must preserve the selected working scene and allow a retry.
            previous=page.locator("#scene-vehicle").input_value()
            page.locator("#vehicles-file").set_input_files({"name":"broken.png","mimeType":"image/png","buffer":b"bad"})
            expect(page.locator("#vehicles-status")).to_have_class("source-status error",timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            assert page.locator("#scene-vehicle").input_value()==previous
            page.locator("#vehicles-file").set_input_files(source/"vehicles/car_body_seats.png")
            expect(page.locator("#vehicles-status")).to_contain_text("추가 완료",timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            assert page.locator("#scene-seatbelts").input_value()==""
            assert page.locator("#scene-steering_wheel").input_value()==""
            page.locator("#vehicle-upload-role").select_option("seatbelts")
            page.locator("#vehicles-file").set_input_files(source/"vehicles/seatbelts_overlay.png")
            expect(page.locator("#vehicles-status")).to_contain_text("추가 완료",timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            assert page.locator("#scene-seatbelts").input_value()
            # ZIP follows the same package route as folder input.
            response=page.request.get(url+"/api/video/character-template")
            page.locator("#character-file").set_input_files({"name":"new-character.zip","mimeType":"application/zip","buffer":response.body()})
            expect(page.locator("#character-status")).to_contain_text("추가 완료 · 1개",timeout=30000)
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            page.locator(".designer-sources").screenshot(path=str(OUTPUT/"three-upload-areas.png"))
            page.set_viewport_size({"width":390,"height":844})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            page.locator(".designer-sources").screenshot(path=str(OUTPUT/"mobile.png"))
            assert not errors,errors
            browser.close()
            print("PASS PNG role selection, ZIP, failed-upload recovery, mobile, no JS/CSP errors",flush=True)
    finally:
        server.should_exit=True
        thread.join(timeout=30)
        assert not thread.is_alive(),"Temporary server did not stop"
        assert not app.state.audio_lab.root.exists(),"Temporary sources were not cleaned up"


if __name__=="__main__":
    main()
