import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

import numpy as np
import pytest

from momo.audio_lab.models import AudioConfig
from momo.contracts import AnalysisResult, MotionTracks, validate_pair
from momo.video_lab.adapter import Motion, app_analysis
from momo.video_lab.scene import Scene, builtins, resolve_scene, unpack_character
from test_audio_lab import audio_client, fake_process, upload

ROOT = Path(__file__).resolve().parents[2]


def test_motion_matches_shared_android_javascript_reference():
    fixtures = ROOT / "tests/fixtures"
    result = AnalysisResult.model_validate_json((fixtures/"result-3.json").read_text(encoding="utf-8"))
    tracks = MotionTracks.model_validate_json((fixtures/"tracks-3.json").read_text(encoding="utf-8"))
    motion = Motion(result,tracks,{"S1":"P1","S2":"P2"})
    for expected in json.loads((fixtures/"motion-reference.json").read_text()):
        actual = motion.at(expected["person_id"],expected["time_us"])
        for field in ("mouth","dx","dy","tilt"):
            assert actual[field] == pytest.approx(expected[field],abs=1e-4)
        assert actual["laughing"] == expected["laughing"]


def test_segment_adapter_keeps_unknown_laughter_and_remaps_speakers():
    experiment = dict(id="test",input=dict(sha256="0"*64,name="test.wav"),utterances=[],stages={
        "vad":dict(model="silero-v5",data=dict(spans=[dict(startMs=0,endMs=1000)])),
        "laughter":dict(model="yamnet",data=dict(events=[dict(startMs=500,endMs=1000,maxScore=.7)])),
        "diarization":dict(model="nemotron",data=dict(segments=[dict(speaker="S8",startMs=100,endMs=300),
            dict(speaker="S4",startMs=400,endMs=600),dict(speaker="S8",startMs=500,endMs=600)]))})
    result,tracks,mapping = app_analysis(experiment,np.full(16000,.02,np.float32))
    validate_pair(result,tracks)
    assert mapping == {"S8":"S1","S4":"S2"}
    assert result.events[-1].end_us == 1000000
    assert all(e.requires_review and not e.laughing_speaker_ids for e in result.events if e.kind=="laughter")
    assert all(e.score == .7 for e in result.events if e.kind=="laughter")
    motion = Motion(result,tracks,{"S1":"P1","S2":"P2"})
    assert not motion.at("P1",550000)["laughing"]
    assert motion.at("P1",750000)["mouth"] == 0


def test_progress_publish_survives_windows_reader_lock(tmp_path,monkeypatch):
    from momo.audio_lab.worker import write_json
    replace = Path.replace
    attempts=[]
    def locked_once(path,target):
        attempts.append(target)
        if len(attempts)==1:
            raise PermissionError("concurrent reader")
        return replace(path,target)
    monkeypatch.setattr(Path,"replace",locked_once)
    write_json(tmp_path/"progress.json",dict(state="running",percent=50))
    assert json.loads((tmp_path/"progress.json").read_text())["percent"] == 50


def test_scene_rejects_duplicate_seats_and_cross_session_assets():
    scene = Scene()
    data = scene.model_dump()
    data["people"][1]["seat"] = data["people"][0]["seat"]
    with pytest.raises(ValueError):
        Scene.model_validate(data)
    assets = builtins()
    assets["builtin-city"]["owner"] = "other"
    with pytest.raises(ValueError):
        resolve_scene(scene,assets,"me")


@pytest.mark.parametrize("name",["../outside.png","/outside.png","C:/outside.png","..\\outside.png"])
def test_character_zip_rejects_traversal(tmp_path,name):
    path = tmp_path/"bad.zip"
    with zipfile.ZipFile(path,"w") as archive:
        archive.writestr(name,b"not an image")
    with pytest.raises(ValueError):
        unpack_character(path,tmp_path/"character")
    assert not (tmp_path/"outside.png").exists()


def test_asset_template_preview_upload_and_ownership(audio_client):
    pytest.importorskip("PIL")
    client,lab = audio_client
    catalog = client.get("/api/video/assets").json()
    assert len(catalog["assets"]) == len(builtins())
    template = client.get("/api/video/character-template")
    assert template.status_code == 200
    response = client.post("/api/video/assets?kind=character&filename=custom.zip",content=template.content,headers={"Content-Type":"application/octet-stream"})
    assert response.status_code == 200,response.text
    scene = Scene().model_dump()
    scene["people"][0]["character"] = response.json()["id"]
    preview = client.post("/api/video/preview",json=scene)
    assert preview.status_code == 200,preview.text
    assert preview.content.startswith(b"\x89PNG")
    assert client.post("/api/video/assets?kind=vehicle&filename=bad.png",content=b"bad").status_code == 422
    assert client.post("/api/video/assets?kind=vehicle&filename=a.png",content=b"bad",headers={"X-Lab-Token":"wrong"}).status_code == 403
    client.cookies.clear()
    client.headers["X-Lab-Token"] = client.get("/api/session").json()["csrf"]
    assert len(client.get("/api/video/assets").json()["assets"]) == len(builtins())
    assert client.post("/api/video/preview",json=scene).status_code == 422


def test_video_only_job_reuses_analysis_without_openai_key(audio_client,monkeypatch):
    client,lab = audio_client
    captured=[]
    fake_process(monkeypatch,captured)
    identity = client.post("/api/audio/jobs",json={"upload_id":upload(client),"scene":Scene().model_dump()}).json()["id"]
    original = lab.jobs[identity]
    (original.directory/"progress.json").write_text(json.dumps(dict(state="complete")))
    (original.directory/"result.json").write_text(json.dumps(dict(config=AudioConfig(topics="openai:gpt-test").model_dump())))
    response = client.post("/api/audio/jobs",json={"reuse_job_id":identity,"scene":Scene().model_dump()})
    assert response.status_code == 200,response.text
    assert captured[-1]["reuse"] == str(original.directory/"result.json")
    assert "credentials" not in captured[-1]
    assert captured[-1]["source"] == str(original.upload.path)
    assert client.post("/api/audio/jobs",json={"upload_id":upload(client),"scene":Scene().model_dump(),"config":{"mode":"vad"}}).status_code == 422


def test_designer_packages_apply_complete_vehicle_set_and_multiple_characters(audio_client):
    pytest.importorskip("PIL")
    client,lab = audio_client
    scene = Scene().model_dump()
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer,"w") as archive:
        for identity in ("builtin-car","builtin-belts","builtin-wheel"):
            path=Path(lab.assets[identity]["path"])
            archive.write(path,"designer/vehicles/"+path.name)
    response=client.post("/api/video/packages?category=vehicles",content=buffer.getvalue())
    assert response.status_code==200,response.text
    assert {a["kind"] for a in response.json()["assets"]}=={"vehicle","seatbelts","steering_wheel"}
    for asset in response.json()["assets"]:
        scene[asset["kind"]]=asset["id"]
    source=zipfile.ZipFile(io.BytesIO(client.get("/api/video/character-template").content))
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,"w") as archive:
        for index in (1,2):
            for name in source.namelist():
                archive.writestr(f"characters/character_{index}/{name}",source.read(name))
    response=client.post("/api/video/packages?category=character",content=buffer.getvalue())
    assert response.status_code==200,response.text
    assert len(response.json()["assets"])==2
    for person,asset in zip(scene["people"],response.json()["assets"]):
        person["character"]=asset["id"]
    assert client.post("/api/video/preview",json=scene).status_code==200
    client.cookies.clear()
    client.headers["X-Lab-Token"]=client.get("/api/session").json()["csrf"]
    assert client.post("/api/video/preview",json=scene).status_code==422


def test_designer_package_failure_never_partially_registers_assets(audio_client):
    pytest.importorskip("PIL")
    client,lab = audio_client
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,"w") as archive:
        archive.write(lab.assets["builtin-car"]["path"],"vehicles/car_body_seats.png")
        archive.writestr("vehicles/seatbelts_overlay.png",b"broken PNG")
    assert client.post("/api/video/packages?category=vehicles",content=buffer.getvalue()).status_code==422
    assert len(lab.assets)==len(builtins())
    assert not list(lab.root.glob("package-*"))
    assert client.post("/api/video/packages?category=vehicles",content=b"zip",headers={"X-Lab-Token":"bad"}).status_code==403
    assert client.post("/api/video/packages?category=vehicles",content=b"zip",headers={"Content-Length":"80000001"}).status_code==413
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,"w") as archive:
        archive.writestr("../outside.png",b"bad")
    assert client.post("/api/video/packages?category=background",content=buffer.getvalue()).status_code==422
    assert len(lab.assets)==len(builtins())


def test_five_sprite_sheet_upload_without_manifest_preserves_frames_and_original(audio_client):
    pytest.importorskip("PIL")
    from PIL import Image,ImageDraw
    client,lab=audio_client
    sheet=Image.new("RGBA",(600,400))
    draw=ImageDraw.Draw(sheet)
    colors=[(240,0,0,255),(0,240,0,255),(0,0,240,255),(230,230,0,255),(0,230,230,255)]
    boxes=[(30,20,130,160),(250,20,350,160),(470,20,570,160),(140,220,240,360),(360,220,460,360)]
    for box,color in zip(boxes,colors):draw.rectangle(box,fill=color)
    png=io.BytesIO();sheet.save(png,format="PNG")
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,"w") as archive:archive.writestr("character-sheets/01-bear.png",png.getvalue())
    response=client.post("/api/video/packages?category=character",content=buffer.getvalue())
    assert response.status_code==200,response.text
    asset=response.json()["assets"][0]
    assert asset["poses"]==["front","left","right"]
    assert "path" not in asset
    manifest=Path(lab.assets[asset["id"]]["path"])
    data=json.loads(manifest.read_text(encoding="utf-8"))
    assert (manifest.parent/data["source"]["file"]).read_bytes()==png.getvalue()
    for state,color in zip(("neutral","happy","talk","left","right"),colors):
        with Image.open(manifest.parent/data["sprites"][state]) as frame:
            assert frame.size==tuple(data["viewBox"][2:])
            assert frame.getpixel((frame.width//2,frame.height//2))==color
    scene=Scene().model_dump();scene["people"][0]["character"]=asset["id"]
    assert client.post("/api/video/preview",json=scene).status_code==200
    scene["people"][0]["pose"]="down"
    assert client.post("/api/video/preview",json=scene).status_code==422


def test_real_mp4_and_portable_bundle(tmp_path):
    pytest.importorskip("PIL")
    from momo.video_lab.render import encode,bundle
    from momo.inference.fusion import fixture
    from momo.config import settings
    source = ROOT/"app/src/androidTest/assets/auto-highlight-synthetic.m4a"
    result,tracks = fixture("video-test",hashlib.sha256(source.read_bytes()).hexdigest(),3000000)
    scene = resolve_scene(Scene(),builtins(),"owner")
    encode(scene,result,tracks,source,tmp_path/"video_full.mp4",0,1000,lambda *_:None)
    info = json.loads(subprocess.check_output([settings.ffprobe,"-v","error","-show_streams","-show_format","-of","json",str(tmp_path/"video_full.mp4")]))
    video,audio = info["streams"]
    assert (video["width"],video["height"],video["r_frame_rate"],video["codec_name"]) == (540,960,"30/1","h264")
    assert audio["codec_name"] == "aac"
    assert float(info["format"]["duration"]) == pytest.approx(1,abs=.04)
    (tmp_path/"app-result.json").write_text(result.model_dump_json())
    (tmp_path/"app-tracks.json").write_text(tracks.model_dump_json())
    experiment=dict(input=dict(name=source.name),config=AudioConfig().model_dump())
    bundle(tmp_path,source,experiment,scene)
    with zipfile.ZipFile(tmp_path/"app-bundle.zip") as archive:
        for item in json.loads(archive.read("index.json")):
            assert hashlib.sha256(archive.read(item["path"])).hexdigest() == item["sha256"]
        assert hashlib.sha256(archive.read("source/input.m4a")).hexdigest() == result.audio.sha256
        preset = json.loads(archive.read("preset.json"))
        assert all(p["manifest"] in archive.namelist() for p in preset["people"])
        assert str(ROOT) not in archive.read("experiment.json").decode()
