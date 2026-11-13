"""Isolated web engines; secrets arrive on stdin, outputs contain no credentials."""
import json
from pathlib import Path
import sys
import threading

from ..audio_lab.worker import parent_guard, run as analyze, write_json


def summary(payload, experiment, directory):
    from ..prepare_voice_model import MODEL_ID
    character = experiment.get("characters")
    if character:
        speakers = character["speakers"]
        if character.get("failed_segments"):
            raise ValueError("분석에 실패한 음성 구간이 있습니다. 원음은 보관되어 있으며 재시도가 필요합니다.")
    else:
        # Non-segment model experiments still use the web adapter and renderer.
        from ..video_lab.adapter import app_analysis
        import numpy as np
        pcm = np.memmap(directory / "audio.f32", dtype="<f4", mode="r")
        try:
            analysis, tracks, mapping = app_analysis(experiment, pcm, directory)
            write_json(directory / "app-result.json", analysis.model_dump())
            write_json(directory / "app-tracks.json", tracks.model_dump())
            speakers = [s.model_dump() for s in analysis.speakers]
        finally:
            pcm._mmap.close()
    signatures = []
    for row in experiment["stages"].get("voice", {}).get("data", {}).get("signatures", []):
        if row.get("dimensions") != 192:
            continue
        speaker = row.get("speaker")
        if not character:
            speaker = mapping.get(speaker)
        if speaker not in {s["speaker_id"] for s in speakers}:
            continue
        signatures.append(dict(speaker_id=speaker, embedding=row["embedding"], speech_us=round(row["speechMs"] * 1000),
            segments=[dict(start_us=round(s["startMs"] * 1000), end_us=round(s["endMs"] * 1000)) for s in row.get("segments", [])]))
    clips = experiment["clips"]
    if any(c["durationMs"] > 600_000 for c in clips):
        raise ValueError("선택된 하이라이트가 10분을 초과했습니다. 주제 구간 설정을 확인해 주세요.")
    return dict(schema_version="1.0", job_id=payload["id"], audio_sha256=payload["input"]["sha256"],
        duration_ms=experiment["input"]["durationMs"], data_origin="real_inference", clips=clips,
        speakers=speakers, voice_model_id=MODEL_ID if signatures else None, speaker_signatures=signatures,
        profile_revision=payload["profile_revision"], config=experiment["config"],
        analysis_policy=experiment["analysis_policy"], speaker_review=experiment.get("speaker_review"),
        warnings=["화자 4명을 초과한 대화는 영상에서 일부 캐릭터가 표시되지 않습니다."] if len(speakers) > 4 else [])


def render(payload, directory):
    from ..video_lab.scene import Scene, resolve_scene
    from ..video_lab.default_sources import load
    from ..video_lab.render import make_videos
    from ..highlights.common import sha256
    assets, catalog = load()
    if catalog is None:
        raise ValueError("디자이너 기본 소스를 준비해 주세요.")
    config = {**catalog["defaults"], "output": "highlights", "burn_captions": False, "brand": "티키타카",
              "laughter_debug": False, "auto_gaze": True}
    seats = ["driver", "front_passenger", "rear_left", "rear_right"]
    config["people"] = [dict(person_id=f"P{i+1}", name=p["name"], speaker=p["speaker"], seat=seats[i],
        character="source-character-" + p["character"]) for i, p in enumerate(payload["cast"]["people"])]
    scene = resolve_scene(Scene.model_validate(config), assets, None)
    experiment = json.loads((directory / "lab-result.json").read_text(encoding="utf-8"))
    def progress(i, count, frame, total):
        write_json(directory / "progress.json", dict(state="running", stage="video",
            video_progress=dict(percent=round((i + frame / max(1, total)) / max(1, count) * 100))))
    # The experiment's immutable original clock/IDs are kept across the render child job.
    pcm = None
    if experiment.get("analysis_policy") != "character-segments-v1":
        # The adapter can read the validated cached pair without rerunning the models.
        experiment = {**experiment, "analysis_policy": "app-backend-v1"}
    video = make_videos(experiment, pcm, Path(payload["source"]), directory, scene, progress)
    raw_captions = json.loads((directory / "character-result.json").read_text(encoding="utf-8"))["captions"] \
        if experiment["analysis_policy"] == "character-segments-v1" else [
            dict(startMs=c["start_us"] / 1000, endMs=c["end_us"] / 1000, text=c["text"], speakers=c["speaker_ids"])
            for c in json.loads((directory / "app-result.json").read_text(encoding="utf-8"))["captions"]]
    labels = {p["speaker"]: p["name"] for p in payload["cast"]["people"]}
    for i, item in enumerate(video["items"], 1):
        subtitles = []
        for caption in raw_captions:
            start, end = max(item["startMs"], caption["startMs"]), min(item["endMs"], caption["endMs"])
            if end <= start:
                continue
            subtitles.append(dict(startMs=round(start - item["startMs"]), endMs=round(end - item["startMs"]),
                text=caption["text"], speakers=caption.get("speakers", []),
                speakerNames=[labels.get(s, "함께한 사람") for s in caption.get("speakers", [])]))
        caption_name = f"captions_{i}.json"
        write_json(directory / caption_name, subtitles)
        for field, filename in (("video", item["file"]), ("poster", item["poster"]), ("captions", caption_name)):
            path = directory / filename
            item[field + "_artifact"] = dict(name=filename, sha256=sha256(path), size=path.stat().st_size)
    return dict(schema_version="1.0", job_id=payload["id"], analysis_job_id=payload["analysis_job_id"],
        audio_sha256=payload["input"]["sha256"], duration_ms=experiment["input"]["durationMs"], items=video["items"],
        renderer=video["renderer"], warnings=[w for w in video.get("warnings", []) if w.get("code") != "DESIGNER_SHEET_FORMAT"], captions_burned=False,
        profile_revision=payload["profile_revision"])


def run(payload):
    directory = Path(payload["directory"])
    if payload["task"] == "episode_analysis":
        analyze(payload)
        experiment = json.loads((directory / "result.json").read_text(encoding="utf-8"))
        write_json(directory / "lab-result.json", experiment)
        result = summary(payload, experiment, directory)
    else:
        result = render(payload, directory)
    write_json(directory / "episode-result.json", result)


if __name__ == "__main__":
    payload = json.loads(sys.stdin.buffer.read())
    threading.Thread(target=parent_guard, args=(payload["parent_pid"], payload["parent_created"]), daemon=True).start()
    try:
        run(payload)
    except Exception as exc:
        from ..errors import ServiceError
        # Provider and native exceptions must not disclose headers or private paths.
        message = exc.message if isinstance(exc, ServiceError) else str(exc)[:500] if isinstance(exc, ValueError) else "웹 영상 엔진 실행에 실패했습니다. 서버 실행 환경을 확인해 주세요."
        write_json(Path(payload["directory"]) / "episode-error.json", {"message": message})
        sys.exit(1)
