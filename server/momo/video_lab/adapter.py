"""Translate experiment outputs to the app's validated 20 ms timeline.

Segment adapters expose binary activity, not Nemotron's original probability tracks.
Overlapping/unknown laughter is deliberately left for review, never guessed.
"""
from bisect import bisect_right
import math
import mimetypes

import numpy as np

from ..contracts import AnalysisResult, MotionTracks, Caption, ModelInfo, Warning, Word, validate_pair
from ..inference.features import envelope
from ..inference.fusion import assemble


def app_analysis(experiment, pcm, directory=None):
    if experiment.get("analysis_policy") == "app-backend-v1":
        if directory is None:
            raise ValueError("앱 분석 결과 파일이 필요합니다. 앱과 동일 모드로 다시 분석해 주세요.")
        result = AnalysisResult.model_validate_json((directory / "app-result.json").read_text(encoding="utf-8"))
        tracks = MotionTracks.model_validate_json((directory / "app-tracks.json").read_text(encoding="utf-8"))
        validate_pair(result, tracks)
        if result.audio.sha256 != experiment["input"]["sha256"] or result.job_id != experiment["id"]:
            raise ValueError("앱 분석 결과와 현재 원본·작업이 일치하지 않습니다.")
        return result, tracks, {s.speaker_id: s.speaker_id for s in result.speakers}
    duration = round(len(pcm) / 16000 * 1e6)
    raw, energy = envelope(pcm)
    count = len(energy)
    stages = experiment["stages"]
    data = lambda key: stages.get(key, {}).get("data", {})
    kinds = np.where(raw > .0005, "environment", "silence").astype(object)
    scores = np.ones(count)
    activity = np.zeros((count, 8), np.float32)
    laughter = np.zeros_like(activity)

    def interval(row):
        return slice(max(0, int(row["startMs"] // 20)), min(count, math.ceil(row["endMs"] / 20)))

    for row in data("vad").get("spans", []):
        kinds[interval(row)] = "speech"
    for row in data("laughter").get("events", []):
        kinds[interval(row)] = "laughter"
        scores[interval(row)] = min(1, max(0, row.get("maxScore", 0)))
    segments = sorted(data("diarization").get("segments", []), key=lambda r: r["startMs"])
    names = list(dict.fromkeys(row["speaker"] for row in segments))[:8]
    for row in segments:
        if row["speaker"] in names:
            activity[interval(row), names.index(row["speaker"])] = 1
    laugh_frames = (kinds == "laughter") & (np.sum(activity > .5, axis=1) == 1)
    laughter[laugh_frames] = activity[laugh_frames]
    captions = []
    for row in experiment.get("utterances", []):
        start, end = max(0, round(row["startMs"] * 1000)), min(duration, round(row["endMs"] * 1000))
        if end <= start:
            continue
        words = []
        for word in row.get("words", []):
            a, b = max(start, round(word["startMs"] * 1000)), min(end, round(word["endMs"] * 1000))
            if b > a:
                words.append(Word(start_us=a, end_us=b, text=word["text"]))
        captions.append(Caption(caption_id=f"C{len(captions)+1}", start_us=start, end_us=end, text=row["text"], words=words))
    models = [ModelInfo(name=key, revision=s["model"], runtime="OpenAI API" if s["model"].startswith("openai:") else "local",
                        precision="adapter-dependent") for key, s in stages.items()]
    result, tracks = assemble(experiment["id"], experiment["input"]["sha256"],
        mimetypes.guess_type(experiment["input"]["name"])[0] or "application/octet-stream", duration,
        dict(kinds=kinds.tolist(), scores=scores, energy=energy), activity, laughter, captions, models)
    # Fusion can drop a speaker occurring only in non-vocal frames. Reconstruct its
    # first-appearance mapping with the identical vocal mask, including simultaneous starts.
    vocal = np.isin(kinds, ["speech", "laughter"])
    first = [(int(indexes[0]), col) for col in range(len(names))
             if len(indexes := np.flatnonzero((activity[:, col] > .5) & vocal))]
    mapping = {names[col]: f"S{i+1}" for i, (_, col) in enumerate(sorted(first))}
    result.warnings.append(Warning(code="LAB_SEGMENT_ADAPTER", message="실험용 구간 출력에서 20ms 활동을 구성했습니다. 앱 원본 추론의 연속 확률 트랙과는 다릅니다."))
    if data("voice").get("signatures"):
        result.warnings.append(Warning(code="LAB_VOICE_EXPORT", message="음성 벡터는 experiment.json에 원래 차원으로 보관합니다. 앱 인물 식별에 자동 등록하지 않습니다."))
    return result, tracks, mapping


class Motion:
    def __init__(self, analysis, tracks, mapping):
        self.events = analysis.events
        self.starts = [e.start_us for e in self.events]
        self.tracks = {s.speaker_id: s for s in tracks.speaker_tracks}
        self.energy = tracks.mixed_voice_energy
        self.mapping = mapping

    def event(self, now):
        index = bisect_right(self.starts, now) - 1
        event = self.events[index] if index >= 0 else None
        return event if event and now < event.end_us else None

    def at(self, person, now):
        rest = dict(mouth=0., dx=0., dy=0., tilt=0., laughing=False)
        event = self.event(now)
        if not event or event.kind not in {"speech", "laughter"}:
            return rest
        speaker = next((s for s in event.active_speaker_ids if self.mapping.get(s) == person), None)
        if speaker not in self.tracks:
            return rest
        index = now // 20000
        track = self.tracks[speaker]
        laugh = event.kind == "laughter" and speaker in event.laughing_speaker_ids
        activity = (track.laughter_activity if laugh else track.activity)[index]
        if activity < .45:
            return rest
        energy = self.energy[index] * min(1, max(0, activity))
        level = min(1, max(0, energy / event.energy_reference))
        local = (now - event.start_us) / 1e6
        phase = (int(speaker[1:]) - 1) * .37 if len(event.active_speaker_ids) > 1 else 0
        pulse = .5 + .5 * math.sin(local * 2 * math.pi * (4.8 if laugh else 4.6) + phase)
        gate = level > .12 and energy > .00008
        mouth = min(1, max(0, level * (.12 + .88 * pulse) ** 1.25)) if gate else 0
        ramp = min(1, max(0, min(local / .12, (event.end_us - now) / 1e6 / .16)))
        edge = ramp * ramp * (3 - 2 * ramp)
        shake = edge * (.25 + .75 * level) if laugh and gate else 0
        return dict(mouth=mouth, laughing=laugh and edge > 0, dx=shake * math.sin(local*27+phase)*1.5,
                    dy=shake*(math.sin(local*30+phase)*2.6+math.sin(local*60+phase)*.45), tilt=shake*math.sin(local*25+phase)*.012)
