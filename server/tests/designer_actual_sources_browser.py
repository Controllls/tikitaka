"""Read-only integration check against the actual supplied designer source directory."""
import hashlib
import json
from pathlib import Path
import threading
import time
import sys
import zipfile

from playwright.sync_api import sync_playwright,expect
from designer_sources_browser import ROOT,create_app,uvicorn

DEFAULTS="--defaults" in sys.argv
OUTPUT=ROOT/"build/reports/video-lab"/("default-source" if DEFAULTS else "real-source")
OUTPUT.mkdir(parents=True,exist_ok=True)
SOURCE=Path.home()/"Documents"/"현대_공모전"/"source"


def main():
    original={p:hashlib.sha256(p.read_bytes()).hexdigest() for p in SOURCE.rglob("*") if p.is_file()}
    app=create_app()
    server=uvicorn.Server(uvicorn.Config(app,host="127.0.0.1",port=0,log_level="warning"))
    thread=threading.Thread(target=server.run,daemon=True)
    thread.start()
    try:
        deadline=time.monotonic()+30
        while not server.started:
            assert thread.is_alive() and time.monotonic()<deadline
            time.sleep(.05)
        url=f"http://127.0.0.1:{server.servers[0].sockets[0].getsockname()[1]}"
        with sync_playwright() as p:
            browser=p.chromium.launch(channel="msedge",headless=True)
            page=browser.new_page(viewport={"width":1500,"height":1100})
            errors=[]
            page.on("pageerror",lambda e:errors.append(str(e)))
            page.goto(url)
            expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
            if DEFAULTS:
                expect(page.locator("#background-status")).to_contain_text("기본 적용")
                expect(page.locator("#character-status")).to_contain_text("캐릭터 8종")
                expect(page.locator("#vehicles-status")).to_contain_text("차량 6개 레이어")
                expect(page.locator("#scene-background")).to_have_value("source-background-city-road-loop")
                expect(page.locator("#person-0-character")).to_have_value("source-character-01-bear")
                expect(page.locator("#person-1-character")).to_have_value("source-character-02-rabbit")
                page.locator("#person-count").select_option("4")
                expect(page.locator("#person-2-character")).to_have_value("source-character-03-chick")
                expect(page.locator("#person-3-character")).to_have_value("source-character-04-teal")
                page.reload()
                expect(page.locator("#live-scene-preview")).to_be_visible(timeout=30000)
                expect(page.locator("#scene-vehicle")).to_have_value("source-vehicle")
                print("PASS designer defaults without upload; all eight choices; extra people; refresh",flush=True)
            else:
                for category,folder,count in [("background","backgrounds",3),("character","character-sheets",8),("vehicles","vehicles",3)]:
                    page.locator(f"#{category}-folder").set_input_files(SOURCE/folder)
                    expect(page.locator(f"#{category}-status")).to_contain_text(f"추가 완료 · {count}개",timeout=60000)
                    expect(page.locator("#run")).to_be_enabled(timeout=60000)
                    print(f"PASS actual {folder} upload",flush=True)
            assert page.locator("#person-0-pose option").count()==3
            assert page.locator("#layout-car_width").input_value()=="540"
            page.locator("#person-count").select_option("2")
            page.locator("#preview-scene").click()
            expect(page.locator("#run")).to_be_enabled(timeout=30000)
            # Render using the actual uploaded asset paths without repeating costly AI inference.
            scene=page.evaluate("sceneSettings()")
            token=page.evaluate("state.csrf")
            from momo.video_lab.scene import Scene,resolve_scene
            from momo.video_lab.render import Renderer,encode,bundle,preview
            from momo.inference.fusion import fixture
            from momo.audio_lab.models import AudioConfig
            resolved=resolve_scene(Scene.model_validate(scene),app.state.audio_lab.assets,token)
            preview(resolved).save(OUTPUT/"preview.png")
            renderer=Renderer(resolved)
            character=renderer.characters[0]
            from PIL import Image
            states=[]
            for name,mouth,laughing in [("neutral",0,False),("talk",1,False),("happy",1,True)]:
                canvas=Image.new("RGBA",(540,960))
                character.draw(canvas,dict(mouth=mouth,laughing=laughing,dx=0,dy=0,tilt=0),False,(270,500))
                states.append(hashlib.sha256(canvas.tobytes()).hexdigest())
                canvas.save(OUTPUT/f"state-{name}.png")
            assert len(set(states))==3
            audio=ROOT/"app/src/androidTest/assets/auto-highlight-synthetic.m4a"
            analysis,tracks=fixture("designer-source-test",hashlib.sha256(audio.read_bytes()).hexdigest(),3000000)
            encode(resolved,analysis,tracks,audio,OUTPUT/"video_full.mp4",0,3000,lambda *_:None)
            (OUTPUT/"app-result.json").write_text(analysis.model_dump_json(),encoding="utf-8")
            (OUTPUT/"app-tracks.json").write_text(tracks.model_dump_json(),encoding="utf-8")
            bundle(OUTPUT,audio,dict(input=dict(name=audio.name),config=AudioConfig().model_dump()),resolved)
            with zipfile.ZipFile(OUTPUT/"app-bundle.zip") as archive:
                preset=json.loads(archive.read("preset.json"))
                assert len(preset["vehicle_layers"])==4
                for person in preset["people"]:
                    manifest=json.loads(archive.read(person["manifest"]))
                    assert manifest["renderMode"]=="sprite-sheet"
                    assert len(manifest["sprites"])==5
                for item in json.loads(archive.read("index.json")):
                    assert hashlib.sha256(archive.read(item["path"])).hexdigest()==item["sha256"]
            page.locator(".designer-sources").screenshot(path=str(OUTPUT/"uploaded-sources.png"))
            page.set_viewport_size({"width":390,"height":844})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            assert not errors,errors
            browser.close()
            print("PASS actual sheets: neutral/talk/laugh states; MP4; six vehicle layers in ZIP; mobile",flush=True)
    finally:
        server.should_exit=True
        thread.join(timeout=30)
        assert not thread.is_alive()
        assert not app.state.audio_lab.root.exists()
        assert all(hashlib.sha256(p.read_bytes()).hexdigest()==digest for p,digest in original.items())
        print("PASS original designer files unchanged; temporary server and uploads cleaned up",flush=True)


if __name__=="__main__":
    main()
