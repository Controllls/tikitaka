import json
import math
from pathlib import Path, PurePosixPath
from typing import Literal
import zipfile

from pydantic import BaseModel, ConfigDict, Field, model_validator

ASSETS = Path(__file__).resolve().parents[3] / "app/src/main/assets/momo"
SEATS = {"driver": 1328, "front_passenger": 605, "rear_left": 1120, "rear_right": 800}


class Settings(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Person(Settings):
    person_id: str = Field(pattern=r"^P[1-4]$")
    name: str = Field(min_length=1, max_length=30)
    seat: Literal["driver", "front_passenger", "rear_left", "rear_right"]
    character: str = Field(max_length=80)
    pose: Literal["front", "left", "right", "down"] = "front"
    speaker: str = Field(pattern=r"^S[1-8]$")


class Layout(Settings):
    car_left: float = Field(default=-120, ge=-540, le=540)
    car_top: float = Field(default=220, ge=0, le=960)
    car_width: float = Field(default=780, ge=300, le=1500)
    front_height: float = Field(default=900, ge=100, le=1400)
    rear_height: float = Field(default=620, ge=100, le=1400)
    front_root_y: float = Field(default=1000, ge=0, le=1500)
    rear_root_y: float = Field(default=730, ge=0, le=1500)


class Scene(Settings):
    burn_captions: bool = True
    brand: Literal["MOMO DRIVE", "티키타카"] = "MOMO DRIVE"
    auto_gaze: bool = True
    laughter_debug: bool = False
    title: str = Field(default="함께 달린 오늘의 이야기", min_length=1, max_length=80)
    background: str = "builtin-city"
    vehicle: str = "builtin-car"
    seatbelts: str | None = "builtin-belts"
    steering_wheel: str | None = "builtin-wheel"
    people: list[Person] = Field(default_factory=lambda: [
        Person(person_id="P1", name="첫 번째 목소리", seat="driver", character="builtin-character-1", speaker="S1"),
        Person(person_id="P2", name="두 번째 목소리", seat="front_passenger", character="builtin-character-2", speaker="S2")], min_length=1, max_length=4)
    layout: Layout = Field(default_factory=Layout)
    output: Literal["full", "highlights", "both"] = "full"

    @model_validator(mode="after")
    def unique(self):
        for field in ("person_id", "seat", "speaker"):
            values = [getattr(p, field) for p in self.people]
            if len(set(values)) != len(values):
                raise ValueError("캐릭터별 좌석과 화자를 중복 없이 지정하세요.")
        return self


def builtins():
    rows = [
        ("builtin-city", "background", "도시 도로 · 앱 기본", "resources/backgrounds/city_rearview_loop_10s.mp4"),
        ("builtin-highway", "background", "고속도로", "resources/backgrounds/highway_rearview_loop_10s.mp4"),
        ("builtin-car", "vehicle", "차량 · 앱 기본", "resources/vehicles/car_body_seats.png"),
        ("builtin-belts", "seatbelts", "안전벨트 · 앱 기본", "resources/vehicles/seatbelts_overlay.png"),
        ("builtin-wheel", "steering_wheel", "운전대 · 앱 기본", "resources/vehicles/steering_wheel_optional.png"),
        *[(f"builtin-character-{i}", "character", f"캐릭터 {i} · 앱 기본", f"resources/characters/character_0{i}/manifest.json") for i in (1, 2)]]
    from .default_sources import load
    designer, _ = load()
    return {**designer, **{key: dict(id=key, kind=kind, name=name, path=str(ASSETS / path), owner=None) for key, kind, name, path in rows}}


def image_size(path):
    from PIL import Image
    with Image.open(path) as image:
        if image.format not in {"PNG", "JPEG", "WEBP"} or max(image.size) > 4096 or min(image.size) < 1:
            raise ValueError("4096px 이하 PNG·JPG·WebP 이미지를 사용하세요.")
        size = image.size
        image.verify()
        return size


def character_files(manifest):
    data = json.loads(manifest.read_text(encoding="utf-8-sig"))
    view = data["viewBox"]
    if len(view) != 4 or view[:2] != [0, 0] or not all(isinstance(x, (int, float)) and math.isfinite(x) for x in view):
        raise ValueError("캐릭터 viewBox가 올바르지 않습니다.")
    if not all(1 <= x <= 2048 for x in view[2:]):
        raise ValueError("캐릭터 캔버스는 2048px 이하여야 합니다.")
    root = data["anchors"]["root"]
    if len(root) != 2 or not all(isinstance(v, (int, float)) and math.isfinite(v) and 0 <= v <= view[i+2] for i, v in enumerate(root)):
        raise ValueError("캐릭터 root 위치가 올바르지 않습니다.")
    if data.get("renderMode") == "sprite-sheet":
        required = {"neutral", "happy", "talk", "left", "right"}
        if set(data["sprites"]) != required or set(data["poses"]) != {"front","left","right"}:
            raise ValueError("캐릭터 시트의 표정·방향 정보가 잘못되었습니다.")
        files = []
        for name in [*data["sprites"].values(), data["source"]["file"]]:
            path=(manifest.parent/name).resolve()
            if not path.is_relative_to(manifest.parent.resolve()) or path.suffix.lower()!=".png" or not path.is_file():
                raise ValueError("캐릭터 시트 경로가 잘못되었습니다.")
            size=image_size(path)
            if name in data["sprites"].values() and size!=tuple(view[2:]):
                raise ValueError("캐릭터 표정 캔버스 크기가 다릅니다.")
            files.append(path)
        return data,files
    body = data["bodyPartsPng"]
    if not isinstance(body, list) or not 1 <= len(body) <= 12:
        raise ValueError("캐릭터 bodyPartsPng가 필요합니다.")
    files = set(body)
    required = {"hair_back", "face", "hair_front", "eyes_open", "eyes_blink", "eyes_happy", "mouth_closed", "mouth_talk", "mouth_smile"}
    if "front" not in data["poses"]:
        raise ValueError("캐릭터 front 포즈가 필요합니다.")
    for key, pose in data["poses"].items():
        if key not in {"front", "left", "right", "down"}:
            continue
        parts = pose["partsPng"]
        if not required <= parts.keys():
            raise ValueError("눈·입·얼굴 PNG 파츠가 빠졌습니다.")
        files.update(parts.values())
    result = []
    for name in files:
        path = (manifest.parent / name).resolve()
        if not path.is_relative_to(manifest.parent.resolve()) or path.suffix.lower() != ".png" or not path.is_file():
            raise ValueError("캐릭터 PNG 경로를 확인하세요.")
        if image_size(path) != tuple(view[2:]):
            raise ValueError("모든 캐릭터 PNG는 viewBox와 같은 전체 캔버스 크기여야 합니다.")
        result.append(path)
    return data, result


def unpack_character(source, target):
    with zipfile.ZipFile(source) as archive:
        items = archive.infolist()
        if len(items) > 400 or sum(i.file_size for i in items) > 150_000_000:
            raise ValueError("캐릭터 ZIP은 400개 파일, 압축 해제 후 150 MB까지 지원합니다.")
        for info in items:
            name = PurePosixPath(info.filename.replace("\\", "/"))
            if name.is_absolute() or ".." in name.parts or ":" in str(name) or (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("ZIP에 허용되지 않는 경로가 있습니다.")
            if info.is_dir() or name.suffix.lower() not in {".json", ".png"}:
                continue
            path = target.joinpath(*name.parts)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(info))
    manifests = list(target.rglob("manifest.json"))
    if len(manifests) != 1:
        raise ValueError("ZIP에는 manifest.json이 하나 있어야 합니다. 앱 기본 소스 ZIP을 참고하세요.")
    character_files(manifests[0])
    return manifests[0]


def resolve_scene(scene, assets, owner):
    def get(identity, kind):
        if identity is None:
            return None
        asset = assets.get(identity)
        if not asset or asset["owner"] not in (None, owner) or asset["kind"] != kind:
            raise ValueError("이 세션에서 사용할 수 없는 장면 소스입니다.")
        return asset["path"]
    resolved = {k: get(getattr(scene, k), k) for k in ("background", "vehicle", "seatbelts", "steering_wheel")}
    vehicle = assets[scene.vehicle]
    if vehicle.get("layers"):
        resolved["vehicle_layers"] = vehicle["layers"]
        resolved["seat_centers"] = vehicle["seat_centers"]
    resolved["people"] = []
    for person in scene.people:
        manifest = get(person.character, "character")
        data = json.loads(Path(manifest).read_text(encoding="utf-8-sig"))
        if person.pose not in data["poses"]:
            raise ValueError(f"{person.name} 캐릭터에 {person.pose} 포즈가 없습니다.")
        resolved["people"].append({**person.model_dump(), "manifest": manifest})
    return {"config": scene.model_dump(), "paths": resolved}
