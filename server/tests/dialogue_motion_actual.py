"""Rebuild an existing real experiment with its exported designer sources."""
import json
from pathlib import Path
import sys
import time
import zipfile

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "server"))

from momo.audio_lab import worker  # noqa: E402
from momo.video_lab.characters import load_characters  # noqa: E402
from momo.video_lab.render import Renderer  # noqa: E402


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    previous = Path(sys.argv[1]).resolve()
    output = previous.parent.parent / f"dialogue-motion-validation-{time.strftime('%Y%m%d-%H%M%S')}"
    output.mkdir()
    assets = output / "designer-assets"
    assets.mkdir()
    with zipfile.ZipFile(previous / "app-bundle.zip") as archive:
        for name in archive.namelist():
            if not name.startswith(("resources/", "source/")):
                continue
            target = (assets / name).resolve()
            assert target.is_relative_to(assets.resolve())
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    preset, config, original = (read(previous / name) for name in ("preset.json", "scene.json", "result.json"))
    config["auto_gaze"] = True
    paths = {key: str(assets / preset[key]) if preset.get(key) else None
             for key in ("background", "vehicle", "seatbelts", "steering_wheel")}
    paths["vehicle_layers"] = {k: str(assets / v) for k, v in preset.get("vehicle_layers", {}).items()}
    paths["seat_centers"] = preset["seat_centers"]
    paths["people"] = [{**person, "manifest": str(assets / next(
        p["manifest"] for p in preset["people"] if p["person_id"] == person["person_id"]))} for person in config["people"]]
    scene = dict(config=config, paths=paths)
    source = next((assets / "source").glob("input.*"))
    worker.run(dict(id=output.name, directory=str(output), source=str(source), input=original["input"],
                    config=original["config"], reuse=str(previous / "result.json"), scene=scene))
    result = read(output / "result.json")
    analysis, tracks, _ = load_characters(result, output)
    renderer = Renderer(scene, analysis, tracks)
    fixed = Renderer({**scene, "config": {**config, "auto_gaze": False}}, analysis, tracks)
    turns = result["characters"]["interactions"]
    assert turns and result["characters"]["duplicate_dialogue"]
    # Verify the actual duplicate speech windows of this 100-second sample.
    for event in result["characters"]["duplicate_dialogue"]:
        lane = next(r for r in tracks["lanes"] if r["id"] == event["segment_id"])
        i = int(((event["startMs"] + event["endMs"]) / 2 - lane["startMs"]) // 20)
        assert lane["speech_mask"][i] == 0
    output_report = ROOT / "build/reports/video-lab/dialogue-motion"
    output_report.mkdir(parents=True, exist_ok=True)
    event = next(e for e in turns if e["endMs"] + 500 < result["input"]["durationMs"])
    now = round((event["endMs"] + 400) * 1000)
    moving_frame, fixed_frame = renderer.frame(now), fixed.frame(now)
    assert np.any(np.asarray(moving_frame) != np.asarray(fixed_frame))
    moving_frame.save(output_report / "question-turn.png")
    fixed_frame.save(output_report / "fixed-front.png")
    validation = dict(directory=str(output), video=str(output / "video_full.mp4"),
        durationMs=result["input"]["durationMs"], original_utterances=len(original["utterances"]),
        corrected_utterances=len(result["utterances"]), removed_duplicates=len(result["characters"]["duplicate_dialogue"]),
        gaze_events=turns, processed_groups=result["stages"]["stt"]["data"]["processed_groups"],
        elapsed_ms=result["elapsed_ms"], actual_claude_call=False, caption_ids_valid=all(
            clip["endUtteranceId"] <= len(result["utterances"]) for clip in result["clips"]))
    (output_report / "validation.json").write_text(json.dumps(validation, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(validation, ensure_ascii=False))


if __name__ == "__main__":
    main()
