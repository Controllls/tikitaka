"""Build a numeric diagnostic video from an existing cached real recording."""
from collections import Counter
import json
from pathlib import Path
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from momo.audio_lab import worker  # noqa: E402
from momo.video_lab.characters import load_characters  # noqa: E402
from momo.video_lab.render import Renderer  # noqa: E402


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    previous = Path(sys.argv[1]).resolve()
    output = previous.parent.parent / f"laughter-debug-validation-{time.strftime('%Y%m%d-%H%M%S')}"
    output.mkdir()
    assets = output / "designer-assets"
    assets.mkdir()
    with zipfile.ZipFile(previous / "app-bundle.zip") as archive:
        for name in archive.namelist():
            if name.startswith(("resources/", "source/")):
                target = (assets / name).resolve()
                assert target.is_relative_to(assets.resolve())
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    preset, config, original = (read(previous / name) for name in ("preset.json", "scene.json", "result.json"))
    config.update(laughter_debug=True, output="full")
    paths = {k: str(assets / preset[k]) if preset.get(k) else None for k in ("background", "vehicle", "seatbelts", "steering_wheel")}
    if preset.get("vehicle_layers"):
        paths["vehicle_layers"] = {k: str(assets / v) for k, v in preset["vehicle_layers"].items()}
    if preset.get("seat_centers"):
        paths["seat_centers"] = preset["seat_centers"]
    paths["people"] = [{**person, "manifest": str(assets / next(p["manifest"] for p in preset["people"] if p["person_id"] == person["person_id"]))}
                       for person in config["people"]]
    scene = dict(config=config, paths=paths)
    source = next((assets / "source").glob("input.*"))
    worker.run(dict(id=output.name, directory=str(output), source=str(source), input=original["input"],
        config=original["config"], reuse=sys.argv[2] if len(sys.argv) > 2 else str(previous / "result.json"), scene=scene))
    result = read(output / "result.json")
    analysis, tracks, _ = load_characters(result, output)
    renderer = Renderer(scene, analysis, tracks)
    assert renderer.diagnostics and tracks["original_laughter_debug"]
    moments = [(f["laughter"], block["offsetMs"] + block["centerMs"] + i*block["hopMs"])
               for block in tracks["original_laughter_debug"] for i, f in enumerate(block["frames"])
               if block["startMs"] <= block["offsetMs"] + block["centerMs"] + i*block["hopMs"] < block["endMs"]]
    peak, now = max(moments)
    snapshot = renderer.diagnostics.at(round(now*1000))
    report = ROOT / "build/reports/video-lab" / (sys.argv[3] if len(sys.argv) > 3 else "laughter-debug") / str(round(result["input"]["durationMs"] / 1000))
    report.mkdir(parents=True, exist_ok=True)
    renderer.frame(round(now*1000)).save(report / "peak-diagnostic.png")
    reasons = Counter(reason for trace in renderer.diagnostics.original for reason in trace.reasons)
    data = dict(directory=str(output), video=str(output / "video_full.mp4"), durationMs=result["input"]["durationMs"],
        sensitivity=result["config"]["threshold"], debug=result["laughter_debug"], raw_peak=peak, snapshot_time_ms=now, snapshot=snapshot,
        classifier_frame_reasons=dict(reasons), laugh_events=len(result["characters"]["laughter_events"]),
        unassigned=result["characters"]["unresolved_laughter_events"], elapsed_ms=result["elapsed_ms"],
        provisional=result["characters"].get("provisional_laughter_events", 0),
        audio_groups_reprocessed=result["stages"]["stt"]["data"]["processed_groups"], actual_claude_call=False)
    (report / "validation.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(data, ensure_ascii=False))


if __name__ == "__main__":
    main()
