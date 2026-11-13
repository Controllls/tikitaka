"""PNG part compositor: Android SceneRenderer / MotionEngine layout and motion.

Pillow and the platform font rasterizer differ from Android Canvas. FFmpeg encodes
H.264/AAC here; measured desktop rendering time is not an Android benchmark.
"""
from functools import lru_cache
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import time
import zipfile

from PIL import Image, ImageChops, ImageDraw, ImageFont

from .adapter import Motion, app_analysis
from .scene import SEATS, character_files
from ..config import settings

SIZE = (540, 960)
FPS = 30
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def read_image(path):
    with Image.open(path) as image:
        return image.convert("RGBA")


class Character:
    def __init__(self, person, ratio):
        self.person = person
        manifest = Path(person["manifest"])
        data = json.loads(manifest.read_text(encoding="utf-8-sig"))
        width, height = data["viewBox"][2:]
        self.size = (max(1, round(width*ratio)), max(1, round(height*ratio)))
        self.root = tuple(v*ratio for v in data["anchors"]["root"])
        self.poses = {}
        for pose, description in data["poses"].items():
            parts = description["partsPng"]
            base = Image.new("RGBA", self.size)
            for name in [*data["bodyPartsPng"], *[parts[k] for k in ("hair_back", "face", "hair_front")], *([parts["nose"]] if parts.get("nose") else [])]:
                base.alpha_composite(read_image(manifest.parent / name).resize(self.size, Image.Resampling.LANCZOS))
            images = {k: read_image(manifest.parent / v).resize(self.size, Image.Resampling.LANCZOS)
                      for k, v in parts.items() if k.startswith(("eyes_", "mouth_"))}
            bounds = {k: im.getchannel("A").point(lambda a: 255 if a > 24 else 0).getbbox() or (0, 0, *self.size)
                      for k, im in images.items() if k.startswith("mouth_")}
            self.poses[pose] = (base, images, bounds)

    @lru_cache(maxsize=64)
    def sprite(self, pose, eye, mouth, height):
        base, parts, sizes = self.poses[pose]
        image = base.copy()
        image.alpha_composite(parts[eye])
        bounds = sizes[mouth]
        crop = parts[mouth].crop(bounds)
        crop = crop.resize((crop.width, height), Image.Resampling.BICUBIC)
        image.alpha_composite(crop, (bounds[0], round((bounds[1]+bounds[3]-height)/2)))
        return image

    def draw(self, canvas, state, blink, center):
        eye = "eyes_happy" if state["laughing"] else "eyes_blink" if blink else "eyes_open"
        mouth = "mouth_smile" if state["mouth"] > .18 and state["laughing"] else "mouth_talk" if state["mouth"] > .18 else "mouth_closed"
        amount = .4 + .6*state["mouth"] if state["mouth"] > .18 else 1
        base_pose = self.person["pose"]
        pose = state.get("pose", base_pose)
        if pose not in self.poses:
            pose = base_pose
        def draw_pose(key):
            bounds = self.poses[key][2][mouth]
            return self.sprite(key, eye, mouth, max(1, round((bounds[3]-bounds[1])*amount)))
        sprite = draw_pose(pose)
        if pose != base_pose and state.get("pose_mix", 1) < 1:
            sprite = Image.blend(draw_pose(base_pose), sprite, state["pose_mix"])
        self.place(canvas,sprite,state,center)
        return dict(laughing=eye == "eyes_happy", mouth=state["mouth"], pose=pose)

    def place(self,canvas,sprite,state,center):
        # A padded canvas avoids clipping when the character rotates about its root.
        pad = 12
        if state["tilt"]:
            padded = Image.new("RGBA", (sprite.width+2*pad, sprite.height+2*pad))
            padded.alpha_composite(sprite, (pad, pad))
            sprite = padded.rotate(-math.degrees(state["tilt"]), Image.Resampling.BICUBIC,
                                   center=(self.root[0]+pad, self.root[1]+pad))
        else:
            pad = 0
        canvas.alpha_composite(sprite, (round(center[0]+state["dx"]-self.root[0]-pad), round(center[1]+state["dy"]-self.root[1]-pad)))


class SheetCharacter(Character):
    def __init__(self,person,ratio):
        self.person=person
        manifest=Path(person["manifest"])
        data=json.loads(manifest.read_text(encoding="utf-8-sig"))
        self.size=tuple(max(1,round(v*ratio)) for v in data["viewBox"][2:])
        self.root=tuple(v*ratio for v in data["anchors"]["root"])
        self.frames={k:read_image(manifest.parent/v).resize(self.size,Image.Resampling.LANCZOS) for k,v in data["sprites"].items()}

    def draw(self,canvas,state,blink,center):
        # The provided side views have closed mouths. Hold front while speaking
        # and use authored side views during listening / the end-of-sentence glance.
        pose=state.get("pose",self.person["pose"])
        if ("pose" in state or state.get("provisional_laughter")) and (state.get("speaking", state["mouth"]>.18) or state["laughing"]):
            pose="front"
        frame=pose if pose in {"left","right"} else "happy" if state["laughing"] else "talk" if state["mouth"]>.18 else "neutral"
        sprite=self.frames[frame]
        if "pose" in state and pose != "front" and state.get("pose_mix",1)<1:
            base=self.person["pose"] if self.person["pose"] in {"left","right"} else "neutral"
            sprite=Image.blend(self.frames[base],sprite,state["pose_mix"])
        self.place(canvas,sprite,state,center)
        return dict(laughing=frame == "happy", mouth=state["mouth"], pose=pose)


class Renderer:
    def __init__(self, scene, analysis=None, tracks=None):
        self.scene = scene
        self.config, paths = scene["config"], scene["paths"]
        self.layout = self.config["layout"]
        self.layered_vehicle = bool(paths.get("vehicle_layers"))
        self.scale = self.layout["car_width"] / 1920
        self.seats = paths.get("seat_centers", SEATS)
        self.mapping = {p["speaker"]: p["person_id"] for p in self.config["people"]}
        if isinstance(tracks, dict) and tracks.get("schema_version") == "2.0":
            from ..audio_lab.segment_core import CharacterMotion
            self.motion = CharacterMotion(tracks, self.mapping)
        else:
            self.motion = Motion(analysis, tracks, self.mapping) if analysis else None
        self.analysis = analysis
        from .laughter_debug import Diagnostics
        self.diagnostics = Diagnostics(tracks, self.motion, self.config["people"]) if self.config.get("laughter_debug") and analysis else None
        from .gaze import Gaze
        from ..audio_lab.dialogue_motion import interactions
        turns = tracks.get("interactions") if isinstance(tracks, dict) else None
        if turns is None and analysis:
            turns = interactions([dict(startMs=c.start_us/1000, endMs=c.end_us/1000, text=c.text, speakers=c.speaker_ids)
                                  for c in analysis.captions], [dict(speaker_id=s.speaker_id) for s in analysis.speakers])
        self.gaze = Gaze(self.config["people"], self.seats, turns or []) if self.config.get("auto_gaze", True) else None
        self.mask = Image.new("L", SIZE)
        ImageDraw.Draw(self.mask).polygon([self.xy(x, y) for x, y in [(170,176),(960,124),(1750,176),(1840,735),(80,735)]], fill=255)
        self.car = self.car_image(paths["vehicle"])
        self.static_background = self.car_image(paths["background"]) if Path(paths["background"]).suffix.lower() in {".png",".jpg",".jpeg",".webp"} else None
        self.front = self.car.copy()
        front_mask = Image.new("L", SIZE)
        draw = ImageDraw.Draw(front_mask)
        for box in [(437,303,774,503),(1158,303,1492,503)]:
            draw.rounded_rectangle((*self.xy(*box[:2]), *self.xy(*box[2:])), radius=64*self.scale, fill=255)
        def curve(start, control, end):
            return [((1-t)**2*start[0]+2*(1-t)*t*control[0]+t*t*end[0], (1-t)**2*start[1]+2*(1-t)*t*control[1]+t*t*end[1]) for t in [i/16 for i in range(1,17)]]
        shapes = [([(480,501),(710,501)], (811,501),(821,601),[(840,1041),(364,1041),(383,601)],(390,501)),
                  ([(1220,501),(1430,501)],(1538,501),(1545,601),[(1566,1041),(1085,1041),(1104,601)],(1111,501))]
        for start, control, end, tail, final in shapes:
            points = start + curve(start[-1], control, end) + tail + curve(tail[-1], final, start[0])
            draw.polygon([self.xy(*p) for p in points], fill=255)
        draw.rectangle((*self.xy(795,958), *self.xy(1125,1080)), fill=255)
        self.front.putalpha(ImageChops.multiply(self.front.getchannel("A"), front_mask))
        self.bottom = None
        if layers := paths.get("vehicle_layers"):
            import numpy as np
            from scipy.ndimage import label
            frame = read_image(layers["frame"])
            regions,_ = label(np.asarray(frame.getchannel("A")) < 128)
            region = regions[frame.height//4,frame.width//2]
            if not region:
                raise ValueError("차량 프레임의 투명 창 영역을 찾을 수 없습니다.")
            window = Image.fromarray(np.where(regions==region,255,0).astype(np.uint8))
            window = window.resize((round(self.layout["car_width"]),round(self.layout["car_width"]*1080/1920)),Image.Resampling.LANCZOS)
            self.mask = Image.new("L",SIZE)
            self.mask.paste(window,(round(self.layout["car_left"]),round(self.layout["car_top"])))
            self.car = self.car_image(layers["frame"])
            self.car.alpha_composite(self.car_image(layers["rear_seat"]))
            self.front = self.car_image(layers["front_seats"])
            self.bottom = self.car_image(layers["bottom"])
        self.overlays = [self.car_image(paths[k]) for k in ("seatbelts", "steering_wheel") if paths[k]]
        self.characters = []
        for person in sorted(paths["people"], key=lambda p: not p["seat"].startswith("rear")):
            data = json.loads(Path(person["manifest"]).read_text(encoding="utf-8-sig"))
            rear = person["seat"].startswith("rear")
            ratio = self.layout["rear_height" if rear else "front_height"] / data["viewBox"][3] * self.scale
            character_class = SheetCharacter if data.get("renderMode")=="sprite-sheet" else Character
            self.characters.append(character_class(person, ratio))
        fonts = [Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts/malgun.ttf", Path("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc")]
        self.font_path = next((p for p in fonts if p.is_file()), None)
        if not self.font_path:
            raise ValueError("한글 폰트가 없습니다. Windows 맑은 고딕 또는 Noto Sans CJK를 설치하세요.")
        self.fonts = {n: ImageFont.truetype(str(self.font_path), n) for n in (12,13,14,16,17,18,24,25)}

    def xy(self, x, y):
        return self.layout["car_left"]+x*self.scale, self.layout["car_top"]+y*self.scale

    def car_image(self, path):
        return self.car_layer(read_image(path))

    def car_layer(self, source):
        image = Image.new("RGBA", SIZE)
        scaled = source.resize((round(self.layout["car_width"]), round(self.layout["car_width"]*1080/1920)), Image.Resampling.LANCZOS)
        image.alpha_composite(scaled, (round(self.layout["car_left"]), round(self.layout["car_top"])))
        return image

    @lru_cache(maxsize=128)
    def wrap(self, text, size, width, lines):
        rows = []
        while text and len(rows) < lines:
            count = 1
            while count < len(text) and self.fonts[size].getlength(text[:count+1]) <= width:
                count += 1
            row, text = text[:count], text[count:].lstrip()
            rows.append(row + ("…" if text and len(rows) == lines-1 else ""))
        return rows

    def frame(self, now=0, background=None):
        image = Image.new("RGBA", SIZE, (249,248,244,255))
        if self.static_background is not None:
            background = self.static_background
        elif background is not None and self.layered_vehicle:
            background = self.car_layer(background.crop((0,0,540,304)).convert("RGBA"))
        if background is not None:
            image.paste(background.convert("RGBA"), (0,0), self.mask)
        image.alpha_composite(self.car)
        front = False
        rendered = {}
        for index, character in enumerate(self.characters):
            person = character.person
            rear = person["seat"].startswith("rear")
            if not rear and not front:
                image.alpha_composite(self.front)
                front = True
            state = self.motion.at(person["person_id"], now) if self.motion else dict(mouth=0,dx=0,dy=0,tilt=0,laughing=False)
            if self.gaze:
                state.update(self.gaze.at(person["speaker"], now))
            rendered[person["speaker"]] = character.draw(image, state, (now/1e6+index*.83)%4.2 < .13,
                           self.xy(self.seats[person["seat"]], self.layout["rear_root_y" if rear else "front_root_y"]))
        if not front:
            image.alpha_composite(self.front)
        if self.bottom is not None:
            image.alpha_composite(self.bottom)
        for overlay in self.overlays:
            image.alpha_composite(overlay)
        draw = ImageDraw.Draw(image)
        def text(value, x, y, size, color=(44,48,43)):
            draw.text((x,y), value, font=self.fonts[size], anchor="ms", fill=color)
        def wrapped(value, y, size, width, lines):
            for i, row in enumerate(self.wrap(value, size, width, lines)):
                text(row, 270, y+i*(size+12), size)
        text(self.config.get("brand", "MOMO DRIVE"),270,74,18,(83,102,82))
        wrapped(self.config["title"],117,25,460,2)
        text("함께 달린 순간, 오래 기억해요",270,190,16)
        event = self.motion.event(now) if self.motion else None
        for person in self.config["people"]:
            active = event and person["speaker"] in event.active_speaker_ids
            text(("● " if active else "")+person["name"][:12],self.xy(self.seats[person["seat"]],0)[0] if self.layered_vehicle else 382 if person["seat"] in {"driver","rear_left"} else 158,
                 720 if person["seat"].startswith("rear") else 690,17,(43,111,66) if active else (64,64,64))
        captions = " ".join(c.text for c in self.analysis.captions if c.start_us <= now < c.end_us) if self.analysis else "음성 분석 후 자막과 입 모양이 함께 움직입니다."
        if self.config.get("burn_captions", True):
            wrapped(captions,784,24,464,3)
        text(f"{now//1000000//60}:{now//1000000%60:02d}  ·  원본 목소리 그대로",270,917,14,(128,128,128))
        if self.diagnostics:
            from .laughter_debug import draw_overlay
            draw_overlay(image, self.diagnostics, now, self.fonts, rendered)
        return image.convert("RGB")


def background_process(scene, frames):
    path = Path(scene["paths"]["background"])
    command = [settings.ffmpeg,"-nostdin","-v","error","-protocol_whitelist","file,pipe"]
    command += ["-loop","1"] if path.suffix.lower() in {".png",".jpg",".jpeg",".webp"} else ["-stream_loop","-1"]
    scaling = "scale=540:304:force_original_aspect_ratio=increase,crop=540:304,pad=540:960:0:0" if scene["paths"].get("vehicle_layers") else "scale=540:960:force_original_aspect_ratio=increase,crop=540:960"
    command += ["-i",str(path),"-an","-vf",scaling+",fps=30",
                "-frames:v",str(frames),"-pix_fmt","rgb24","-f","rawvideo","pipe:1"]
    return subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, creationflags=FLAGS)


def preview(scene):
    renderer = Renderer(scene)
    process = background_process(scene, 1)
    try:
        data = process.stdout.read(540*960*3)
        if len(data) != 540*960*3:
            raise ValueError("배경 파일을 읽을 수 없습니다.")
        return renderer.frame(background=Image.frombytes("RGB",SIZE,data))
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        process.stdout.close()


def encode(scene, analysis, tracks, source, output, start_ms, end_ms, report):
    renderer = Renderer(scene, analysis, tracks)
    duration = (end_ms-start_ms)/1000
    frames = math.ceil(duration*FPS)
    background = background_process(scene, frames)
    encoder = None
    try:
        encoder = subprocess.Popen([settings.ffmpeg,"-nostdin","-v","error","-y","-f","rawvideo","-pix_fmt","rgb24",
            "-s","540x960","-r",str(FPS),"-i","pipe:0","-protocol_whitelist","file,pipe","-ss",str(start_ms/1000),
            "-i",str(source),"-map","0:v:0","-map","1:a:0","-t",str(duration),"-map_metadata","-1",
            "-c:v","libx264","-preset","veryfast","-crf","20","-pix_fmt","yuv420p","-c:a","aac","-b:a","128k",
            "-movflags","+faststart",str(output)], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=FLAGS)
        for frame in range(frames):
            data = background.stdout.read(540*960*3)
            if len(data) != 540*960*3:
                raise ValueError("배경 영상 디코딩이 중단되었습니다.")
            image = renderer.frame(round(start_ms*1000+frame/FPS*1e6), Image.frombytes("RGB",SIZE,data))
            if frame == 0:
                image.save(output.with_suffix(".png"))
            encoder.stdin.write(image.tobytes())
            if frame % 30 == 0 or frame == frames-1:
                report(frame+1, frames)
        encoder.stdin.close()
        if encoder.wait(timeout=60) != 0:
            raise ValueError("MP4 인코딩에 실패했습니다. 원본 오디오와 FFmpeg를 확인하세요.")
    finally:
        for process in (encoder,background):
            if process:
                if process.poll() is None:
                    process.kill()
                process.wait()
                for stream in (process.stdin,process.stdout):
                    if stream and not stream.closed:
                        stream.close()


def save_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, allow_nan=False, indent=2), encoding="utf-8")


def bundle(directory, source, experiment, scene):
    """Portable preset + original input + selected resources; never serializes trusted paths or credentials."""
    preset = dict(preset_id="momo-video-lab-v1", title=scene["config"]["title"], layout=scene["config"]["layout"],
                  people=[], speaker_mapping={p["speaker"]:p["person_id"] for p in scene["config"]["people"]})
    files = {}
    if scene["paths"].get("vehicle_layers"):
        preset["vehicle_layers"] = {}
        preset["seat_centers"] = scene["paths"]["seat_centers"]
        for role,path in scene["paths"]["vehicle_layers"].items():
            name = f"resources/vehicle_layers/{role}.png"
            preset["vehicle_layers"][role] = name
            files[name] = Path(path)
    for kind in ("background","vehicle","seatbelts","steering_wheel"):
        path = scene["paths"][kind]
        name = f"resources/{kind}{Path(path).suffix}" if path else None
        preset[kind] = name
        if path:
            files[name] = Path(path)
    for person in scene["paths"]["people"]:
        manifest = Path(person["manifest"])
        data, parts = character_files(manifest)
        base = f"resources/characters/{person['person_id']}"
        files[f"{base}/manifest.json"] = manifest
        for path in parts:
            files[f"{base}/{path.relative_to(manifest.parent).as_posix()}"] = path
        preset["people"].append({k:person[k] for k in ("person_id","name","seat","pose")} | {"manifest":f"{base}/manifest.json"})
    save_json(directory / "preset.json", preset)
    save_json(directory / "model-config.json", experiment["config"])
    save_json(directory / "scene.json", scene["config"])
    save_json(directory / "experiment.json", experiment)
    extension = Path(experiment["input"]["name"]).suffix.lower()
    extension = extension if extension in {".wav",".mp3",".mp4",".m4a",".webm",".ogg",".flac",".aac"} else ".media"
    files[f"source/input{extension}"] = source
    analysis_files = ("character-result.json", "character-tracks.json") if experiment.get("analysis_policy") == "character-segments-v1" else ("app-result.json", "app-tracks.json")
    for name in (*analysis_files,"preset.json","model-config.json","scene.json","experiment.json"):
        files[name] = directory/name
    with zipfile.ZipFile(directory / "app-bundle.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        index = []
        for name, path in files.items():
            digest = hashlib.sha256()
            size = 0
            with path.open("rb") as source_file, archive.open(name, "w", force_zip64=True) as target:
                for chunk in iter(lambda: source_file.read(1024 * 1024), b""):
                    target.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
            index.append(dict(path=name,sha256=digest.hexdigest(),bytes=size))
        archive.writestr("index.json",json.dumps(index,ensure_ascii=False))
        if experiment.get("analysis_policy") == "character-segments-v1":
            archive.writestr("CHARACTER-V2.txt", "캐릭터별 구간 분석 v2\ncharacter-result.json: 원본 시각의 화자·구간·자막·검토 결과\ncharacter-tracks.json: 구간별 말하기·웃음·음량 20ms 배열\n말하기와 웃음이 동시에 존재하며 캐릭터마다 다른 음량을 사용합니다.\n이 자료는 기존 앱 v1 스키마와 다릅니다. 앱에 v2 재생을 연결한 뒤 사용하세요.\n분리 모델은 두 음원용 SepFormer WHAMR16k이며 세 명 이상 겹침 및 미확정 화자는 검토 대상으로 남깁니다.\n")
        archive.writestr("README.txt", "MOMO 영상 실험 내보내기\n원본 음성, 앱 스키마의 분석/20ms 트랙, 프리셋, PNG 파츠, 모델 선택을 포함합니다.\n앱에 자동 설치되지 않습니다. 앱 적용 시 프리셋 경로와 모델 어댑터를 연결해야 합니다.\n영상: 540x960, 30fps, H.264/AAC. Android SceneRenderer/MotionEngine 규칙을 Python으로 옮겼습니다. 폰트·픽셀 렌더링 및 인코더는 다릅니다.\n구간 기반 실험 트랙은 앱 원래 추론 확률과 다릅니다. 알 수 없는 웃음 화자는 검토 대상으로 남깁니다.\n음성 특징 벡터는 experiment.json에 보관하며 앱 인물 식별에 자동 등록하지 않습니다.\n측정 시간은 이 PC 기준입니다. API 키는 포함하지 않습니다.\n")
        license_path = Path(__file__).resolve().parents[3]/"third_party/momo-v13/LICENSE"
        if license_path.is_file():
            archive.write(license_path,"licenses/momo-v13-LICENSE")


def make_videos(experiment, pcm, source, directory, scene, report):
    started = time.perf_counter()
    if experiment.get("analysis_policy") == "character-segments-v1":
        from .characters import load_characters
        analysis, tracks, mapping = load_characters(experiment, directory)
    else:
        analysis, tracks, mapping = app_analysis(experiment, pcm, directory)
        save_json(directory/"app-result.json",analysis.model_dump())
        save_json(directory/"app-tracks.json",tracks.model_dump())
    scope = scene["config"]["output"]
    ranges = [("video_full",scene["config"]["title"],0,analysis.duration_us/1000)] if scope in {"full","both"} else []
    if scope in {"highlights","both"}:
        ranges += [(f"video_clip_{i}",clip["title"],clip["startMs"],clip["endMs"]) for i,clip in enumerate(experiment["clips"],1)]
    items = []
    for index,(name,title,start,end) in enumerate(ranges):
        report(index,len(ranges),0,1)
        clip_scene = {**scene, "config": {**scene["config"], "title": title}}
        encode(clip_scene,analysis,tracks,source,directory/f"{name}.mp4",start,end,lambda n,total: report(index,len(ranges),n,total))
        items.append(dict(file=f"{name}.mp4",poster=f"{name}.png",title=title,startMs=start,endMs=end,durationMs=end-start))
    result = dict(items=items,render_ms=round((time.perf_counter()-started)*1000),width=540,height=960,fps=30,
                  scene=scene["config"],speaker_mapping=mapping,speakers=[s.model_dump() for s in analysis.speakers],
                  warnings=[w.model_dump() for w in analysis.warnings],bundle="app-bundle.zip",renderer="android-scene-port-v1",
                  note="앱의 배치·입 모양·웃음 움직임 규칙을 사용합니다. 글꼴과 픽셀 렌더링은 Android와 다르며 시간은 이 PC 기준입니다.")
    if experiment.get("analysis_policy") == "character-segments-v1":
        result.update(renderer="character-tracks-v2", note="캐릭터별 말하기·웃음·음량을 독립 재생합니다. 영상에는 원본 음성을 사용합니다. 앱 적용에는 v2 동작 트랙 연결이 필요합니다.")
    if any(json.loads(Path(p["manifest"]).read_text(encoding="utf-8-sig")).get("renderMode")=="sprite-sheet" for p in scene["paths"]["people"]):
        result["note"] = "시트형 캐릭터는 말할 때 정면 말하기 그림, 질문·말 걸기를 듣거나 문장 끝에 좌우 그림을 사용합니다. 옆모습의 말하기 파츠는 제공되지 않았습니다. 웃음 흔들림을 적용하며 처리 시간은 이 PC 기준입니다."
        result["warnings"].append(dict(code="DESIGNER_SHEET_FORMAT",message="이 소스의 앱 적용에는 시트 표정 전환과 차량 레이어 처리를 연결해야 합니다. 원본 시트·변환 프레임·레이어·설정은 ZIP에 함께 보관됩니다."))
    if not ranges:
        result["warnings"].append(dict(code="NO_HIGHLIGHTS",message="하이라이트가 없어 영상이 생성되지 않았습니다. 전체 영상으로 다시 제작할 수 있습니다."))
    missing = [s.speaker_id for s in analysis.speakers if s.speaker_id not in {p["speaker"] for p in scene["config"]["people"]}]
    if missing:
        result["warnings"].append(dict(code="UNMAPPED_SPEAKERS",message=f"캐릭터 미지정 화자: {', '.join(missing)}. 해당 음성과 자막은 유지됩니다."))
    experiment["video"] = result
    return result
