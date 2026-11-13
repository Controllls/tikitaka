"""Persistent, portable defaults prepared from the supplied designer source tree."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2] / "assets/designer-defaults"


def load(root=ROOT):
    path = root / "catalog.json"
    if not path.is_file():
        return {}, None
    catalog = json.loads(path.read_text(encoding="utf-8"))
    if catalog.get("version") != 1:
        raise ValueError("기본 디자이너 소스 목록의 버전을 확인하세요.")

    def resolve(relative):
        target = (root / relative).resolve()
        if not target.is_relative_to(root.resolve()) or not target.is_file():
            raise ValueError("기본 디자이너 소스 파일을 찾을 수 없습니다.")
        return str(target)

    assets = {}
    for saved in catalog["assets"]:
        row = {**saved, "path": resolve(saved["path"]), "owner": None, "default_source": True}
        if saved.get("layers"):
            row["layers"] = {key: resolve(value) for key, value in saved["layers"].items()}
        assets[row["id"]] = row
    return assets, catalog


def install(source, destination=ROOT):
    """Copy originals and derive sprites; never modify the designer's files."""
    from .designer import NUMBERED_VEHICLE, numbered_vehicle, sheet_character
    from .scene import Scene
    from .service import validate_background

    source, destination = source.resolve(), destination.resolve()
    if destination == source or destination.is_relative_to(source):
        raise ValueError("기본 소스 저장 경로는 원본 폴더 밖이어야 합니다.")
    destination.mkdir(parents=True, exist_ok=True)
    backgrounds = sorted((source / "backgrounds").glob("*.mp4"))
    characters = sorted((source / "character-sheets").glob("*.png"))
    vehicles = [source / "vehicles" / name for name in NUMBERED_VEHICLE]
    if not backgrounds or not characters or not all(p.is_file() for p in vehicles):
        raise ValueError("backgrounds·character-sheets·vehicles 폴더의 기본 소스를 확인하세요.")
    originals = {p.relative_to(source).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in [*backgrounds, *characters, *vehicles]}
    rows = []
    for path in backgrounds:
        validate_background(path)
        target = destination / "backgrounds" / path.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        rows.append(dict(id=f"source-background-{path.stem}", kind="background", name=f"{path.stem} · 공모전 기본", path=str(target)))
    for path in characters:
        rows.append(dict(sheet_character(path, destination / "characters" / path.stem),
                         id=f"source-character-{path.stem}", name=f"{path.stem} · 공모전 기본"))
    copied = []
    for path in vehicles:
        target = destination / "vehicles" / path.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        copied.append(target)
    vehicle_rows = numbered_vehicle(copied)
    for row in vehicle_rows:
        rows.append(dict(row, id=f"source-{row['kind']}", name=f"{row['name']} · 공모전 기본"))
    character_ids = [r["id"] for r in rows if r["kind"] == "character"]
    background_ids = [r["id"] for r in rows if r["kind"] == "background"]
    scene = Scene().model_dump()
    scene.update(background=next((v for v in background_ids if v.endswith("city-road-loop")), background_ids[0]),
                 vehicle="source-vehicle", seatbelts="source-seatbelts", steering_wheel="source-steering_wheel",
                 layout=vehicle_rows[0]["suggested_layout"])
    for i, person in enumerate(scene["people"]):
        person.update(character=character_ids[i % len(character_ids)], pose="front")
    Scene.model_validate(scene)
    for row in rows:
        row["path"] = Path(row["path"]).relative_to(destination).as_posix()
        if row.get("layers"):
            row["layers"] = {key: Path(value).relative_to(destination).as_posix() for key, value in row["layers"].items()}
    catalog = dict(version=1, source_label="현대_공모전/source", source_files=originals, assets=rows, defaults=scene,
                   default_characters=character_ids, counts=dict(background=len(backgrounds), character=len(characters), vehicle_layers=6))
    (destination / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
    assert all(hashlib.sha256((source / name).read_bytes()).hexdigest() == digest for name, digest in originals.items())
    return catalog


if __name__ == "__main__":
    import sys
    result = install(Path(sys.argv[1]))
    print(json.dumps(dict(directory=str(ROOT), counts=result["counts"]), ensure_ascii=False))
