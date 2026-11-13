"""Import designer folders/ZIPs as one validated, atomic asset batch."""
from pathlib import PurePosixPath
import zipfile

from .scene import character_files, image_size

BACKGROUND_EXTENSIONS = {".mp4", ".webm", ".mov", ".png", ".jpg", ".jpeg", ".webp"}
VEHICLE_NAMES = {"car_body_seats.png": "vehicle", "seatbelts_overlay.png": "seatbelts",
                 "steering_wheel_optional.png": "steering_wheel"}


def extract_package(source, target, category):
    allowed = BACKGROUND_EXTENSIONS if category == "background" else {".png", ".json"}
    with zipfile.ZipFile(source) as archive:
        items = archive.infolist()
        if len(items) > 400 or sum(i.file_size for i in items) > 150_000_000:
            raise ValueError("소스 묶음은 400개 파일, 압축 해제 후 150 MB까지 지원합니다.")
        seen = set()
        for info in items:
            name = PurePosixPath(info.filename.replace("\\", "/"))
            if name.is_absolute() or ".." in name.parts or ":" in str(name) or (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("소스 묶음에 허용되지 않는 경로가 있습니다.")
            if info.is_dir() or name.suffix.lower() not in allowed or "__MACOSX" in name.parts or name.name.startswith("."):
                continue
            key = str(name).casefold()
            if key in seen or any(p.endswith((".", " ")) or p.split(".")[0].upper() in {"CON","PRN","AUX","NUL",*[f"COM{i}" for i in range(1,10)],*[f"LPT{i}" for i in range(1,10)]} for p in name.parts):
                raise ValueError("중복되거나 사용할 수 없는 파일 이름이 있습니다.")
            seen.add(key)
            path = target.joinpath(*name.parts)
            if not path.resolve().is_relative_to(target.resolve()):
                raise ValueError("소스 경로를 확인하세요.")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(info))


def package_assets(source, target, category, validate_background):
    extract_package(source, target, category)
    assets = []
    if category == "character":
        manifests = sorted(target.rglob("manifest.json"))
        for manifest in manifests:
            data, _ = character_files(manifest)
            name = str(data.get("label") or manifest.parent.name)[:100]
            assets.append(dict(kind="character", name=name, path=str(manifest),poses=list(data["poses"]),format=data.get("renderMode","parts")))
        sheets = [p for p in sorted(target.rglob("*")) if p.is_file() and p.suffix.lower()==".png"
                  and not any(p.is_relative_to(m.parent) for m in manifests)]
        if sheets:
            from .designer import sheet_character
            for index,sheet in enumerate(sheets):
                assets.append(sheet_character(sheet,target.parent/"adapted"/f"character-{index}"))
        if not assets:
            raise ValueError("캐릭터 시트 PNG(위 3개·아래 2개) 또는 manifest.json이 있는 파츠 폴더를 넣어 주세요.")
    elif category == "background":
        for path in sorted(target.rglob("*")):
            if path.is_file() and path.suffix.lower() in BACKGROUND_EXTENSIONS:
                validate_background(path)
                assets.append(dict(kind="background", name=path.name, path=str(path)))
        if not assets:
            raise ValueError("배경 폴더에 MP4·WebM·MOV 또는 PNG·JPG·WebP가 없습니다.")
    else:
        paths = [p for p in target.rglob("*") if p.is_file() and p.suffix.lower() == ".png"]
        from .designer import NUMBERED_VEHICLE, numbered_vehicle
        if any(p.name.lower() in NUMBERED_VEHICLE for p in paths):
            return numbered_vehicle(paths)
        unknown = [p.name for p in paths if p.name.lower() not in VEHICLE_NAMES]
        if unknown:
            raise ValueError("차량 묶음 파일명은 car_body_seats.png / seatbelts_overlay.png / steering_wheel_optional.png를 사용하세요. 다른 이름의 PNG는 화면에서 용도를 골라 개별 추가할 수 있습니다.")
        roles = []
        for path in sorted(paths):
            kind = VEHICLE_NAMES[path.name.lower()]
            if kind in roles:
                raise ValueError("차량 폴더는 차량 한 세트씩 선택하세요. 같은 용도의 PNG가 여러 개 있습니다.")
            roles.append(kind)
            image_size(path)
            assets.append(dict(kind=kind, name=path.name, path=str(path)))
        if "vehicle" not in roles:
            raise ValueError("차량 폴더에는 car_body_seats.png가 필요합니다. 안전벨트·운전대는 선택 사항입니다.")
    return assets
