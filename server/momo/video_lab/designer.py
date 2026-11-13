"""Adapters for source/character-sheets and the six numbered vehicle layers.

Sheet frames are complete authored sprites, not independent mouth/eye parts.
The adapter preserves their RGBA pixels, registers their feet, and records crops.
"""
import json
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import find_objects, label

from .scene import image_size

NUMBERED_VEHICLE = {
    "01-frame-and-mirror.png": "frame", "02-front-seat-belts.png": "seatbelts",
    "03-rear-seat.png": "rear_seat", "04-bottom-interior-panel.png": "bottom",
    "05-front-seats.png": "front_seats", "06-steering-wheel.png": "steering_wheel",
}
DESIGNER_LAYOUT = dict(car_left=0, car_top=260, car_width=540, front_height=800,
                       rear_height=500, front_root_y=980, rear_root_y=760)
DESIGNER_SEATS = dict(driver=1530, front_passenger=378, rear_left=1242, rear_right=648)


def sheet_character(source, destination):
    width, height = image_size(source)
    if not 1.35 <= width/height <= 1.65:
        raise ValueError(f"{source.name}: 캐릭터 시트는 위 3개·아래 2개가 배치된 가로 PNG여야 합니다.")
    with Image.open(source) as loaded:
        image = loaded.convert("RGBA")
    alpha = np.asarray(image)[:, :, 3]
    labels, _ = label(alpha > 64)
    sizes = np.bincount(labels.ravel())
    boxes = [box for i, box in enumerate(find_objects(labels), 1) if sizes[i] > width*height*.01]
    top = sorted([b for b in boxes if (b[0].start+b[0].stop)/2 < height/2], key=lambda b:b[1].start)
    bottom = sorted([b for b in boxes if (b[0].start+b[0].stop)/2 >= height/2], key=lambda b:b[1].start)
    if len(top) != 3 or len(bottom) != 2:
        raise ValueError(f"{source.name}: 투명 배경의 5개 캐릭터(위: 기본·웃음·말하기 / 아래: 왼쪽·오른쪽)를 찾지 못했습니다.")
    boxes = top+bottom
    # One common canvas and root prevent the figure jumping when expressions change.
    pad = max(3, round(min(width,height)*.008))
    canvas_width = max(b[1].stop-b[1].start for b in boxes)+2*pad
    canvas_height = max(b[0].stop-b[0].start for b in boxes)+2*pad
    destination.mkdir(parents=True, exist_ok=True)
    sprites, crops = {}, {}
    for state, box in zip(("neutral","happy","talk","left","right"), boxes):
        x0,y0,x1,y1 = box[1].start,box[0].start,box[1].stop,box[0].stop
        crop_box = (max(0,x0-pad),max(0,y0-pad),min(width,x1+pad),min(height,y1+pad))
        frame = Image.new("RGBA", (canvas_width,canvas_height))
        crop = image.crop(crop_box)
        frame.alpha_composite(crop,(round(canvas_width/2-(x0+x1)/2+crop_box[0]),canvas_height-pad-y1+crop_box[1]))
        name = f"{state}.png"
        frame.save(destination/name)
        sprites[state] = name
        crops[state] = list(crop_box)
    # Keep the original designer sheet inside exported bundles too.
    (destination/"source.png").write_bytes(source.read_bytes())
    manifest = dict(schemaVersion=3,id=source.stem,label=source.stem,renderMode="sprite-sheet",
        viewBox=[0,0,canvas_width,canvas_height],anchors={"root":[canvas_width/2,canvas_height-pad]},
        poses={"front":{},"left":{},"right":{}},sprites=sprites,
        source={"file":"source.png","layout":"three-plus-two","crops":crops,"alphaThreshold":64},
        animationNote="정면 기본·웃음·말하기 이미지를 전환합니다. 좌우 포즈는 제공된 정지 이미지이며, 별도 눈깜빡임·입 파츠는 없습니다.")
    path=destination/"manifest.json"
    path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
    return dict(kind="character",name=source.stem,path=str(path),format="sprite-sheet",poses=["front","left","right"])


def numbered_vehicle(paths):
    by_name={p.name.lower():p for p in paths}
    if len(by_name)!=len(paths) or set(by_name)!=set(NUMBERED_VEHICLE):
        raise ValueError("번호형 차량 소스는 01-frame-and-mirror.png부터 06-steering-wheel.png까지 한 세트의 PNG 6개를 함께 넣어 주세요.")
    sizes=[image_size(path) for path in paths]
    if len(set(sizes))!=1:
        raise ValueError("차량 6개 레이어는 같은 캔버스 크기여야 합니다.")
    layers={role:str(by_name[name]) for name,role in NUMBERED_VEHICLE.items()}
    return [dict(kind="vehicle",name="차량 · 6레이어 소스",path=layers["frame"],format="layered-vehicle",
                 layers={k:layers[k] for k in ("frame","rear_seat","front_seats","bottom")},
                 suggested_layout=DESIGNER_LAYOUT,seat_centers=DESIGNER_SEATS),
            dict(kind="seatbelts",name="02 · 앞좌석 안전벨트",path=layers["seatbelts"]),
            dict(kind="steering_wheel",name="06 · 운전대",path=layers["steering_wheel"])]
