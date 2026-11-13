from collections import Counter
import json
from pathlib import Path

from momo.video_lab.default_sources import ROOT, load
from momo.video_lab.scene import Scene, resolve_scene
from test_audio_lab import audio_client as base_audio_client

audio_client = base_audio_client


def test_designer_defaults_load_without_upload_and_are_available_to_new_sessions(audio_client):
    client, lab = audio_client
    data = client.get("/api/video/assets").json()
    supplied = [a for a in data["assets"] if a.get("default_source")]
    assert Counter(a["kind"] for a in supplied) == dict(background=3, character=8, vehicle=1, seatbelts=1, steering_wheel=1)
    assert len(data["default_characters"]) == 8
    scene = data["defaults"]
    assert scene["background"] == "source-background-city-road-loop"
    assert [p["character"] for p in scene["people"]] == ["source-character-01-bear", "source-character-02-rabbit"]
    assert scene["layout"]["car_width"] == 540
    resolved = resolve_scene(Scene.model_validate(scene), lab.assets, "any-session")
    assert len(resolved["paths"]["vehicle_layers"]) == 4
    assert resolved["paths"]["seat_centers"]["driver"] == 1530
    assert all(Path(p["manifest"]).is_file() for p in resolved["paths"]["people"])
    assert client.post("/api/video/preview", json=scene).status_code == 200
    client.cookies.clear()
    client.headers["X-Lab-Token"] = client.get("/api/session").json()["csrf"]
    assert client.get("/api/video/assets").json()["defaults"] == scene
    assert client.post("/api/video/preview", json=scene).status_code == 200
    assert all("path" not in row and "owner" not in row for row in data["assets"])


def test_catalog_is_portable_and_default_assets_do_not_consume_upload_quota(audio_client):
    saved = json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))
    assert len(saved["source_files"]) == 17
    for row in saved["assets"]:
        assert not Path(row["path"]).is_absolute()
    assets, _ = load()
    assert all(row["owner"] is None for row in assets.values())
    client, lab = audio_client
    # 39 session uploads plus 21 defaults must still allow one more upload.
    for i in range(39):
        lab.assets[f"test-owned-{i}"] = dict(id=f"test-owned-{i}", owner="test", kind="vehicle")
    data = Path(lab.assets["source-seatbelts"]["path"]).read_bytes()
    assert client.post("/api/video/assets?kind=seatbelts&filename=belt.png", content=data).status_code == 200
    assert client.post("/api/video/assets?kind=seatbelts&filename=belt.png", content=data).status_code == 409
