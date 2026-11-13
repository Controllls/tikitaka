"""Bounded segment workers share models; GPU calls are serialized, CPU work overlaps."""
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import gc
import json
import threading
import time

import numpy as np
from scipy.io import wavfile

from .execution import stt_execution
from .segment_core import independent_tracks, ranges, speaker_matches, trim_utterances


def coarse_tracks(pcm, acoustic, vad, report, threshold=.1):
    """Bound the original-audio classifier's context even for six-hour sources."""
    count = int(np.ceil(len(pcm) / 320))
    speech, laughter, candidates = (np.zeros(count, np.float32) for _ in range(3))
    kinds = ["silence"] * count
    debug = []
    # 64 seconds and 640 ms align with both 20 ms tracks and the 32 ms VAD clock.
    for first in range(0, count, 3200):
        last = min(count, first + 3200)
        left, right = max(0, first - 32), min(count, last + 32)
        wave = pcm[left * 320:min(len(pcm), right * 320)]
        offset = left * 20 // 32
        local = acoustic.analyze(wave, vad=vad[offset:offset + int(np.ceil(len(wave) / 512))] if vad is not None else None, include_frames=True)
        from .laughter_debug import record
        debug.append(record(local, left*20, first*20, min(len(pcm)/16, last*20), threshold))
        local_speech, local_laughter, local_candidates, _ = independent_tracks(local, len(wave) / 16, threshold)
        core = slice(first - left, last - left)
        speech[first:last], laughter[first:last], candidates[first:last] = local_speech[core], local_laughter[core], local_candidates[core]
        kinds[first:last] = local["kinds"][core]
        report(last / max(1, count))
    if threshold != .1:
        kinds = ["laughter" if laughter[i] >= threshold*.5 else "speech" if kind == "laughter" and speech[i] >= .35
                 else "environment" if kind == "laughter" else kind for i, kind in enumerate(kinds)]
    return {"kinds": kinds, "laughter_debug": debug}, speech, laughter, candidates


def anchors_from_speech(pcm, activity, speech, laughter, mapping, encoder, threshold=.1):
    anchors, records = {}, []
    for column, speaker in mapping.items():
        solo = (activity[:, column] >= .65) & (np.sum(activity >= .35, axis=1) == 1)
        clean = solo & (speech >= .5) & (laughter < min(.025, threshold*.25))
        samples, vectors, total = [], [], 0
        pieces, piece_samples, piece_frames = [], [], 0
        for a, b in sorted(ranges(clean), key=lambda pair: -(pair[1] - pair[0])):
            a, b = a + 3, b - 3
            while b - a >= 20 and total + piece_frames < 1500:
                end = min(b, a + 300, a + 1500 - total)
                if end - a < 20:
                    break
                pieces.append(pcm[a * 320:end * 320])
                piece_samples.append(dict(startMs=a * 20, endMs=end * 20))
                piece_frames += end - a
                if piece_frames >= 150:
                    # Short clean turns can jointly form an enrollment sample. Only
                    # embeddings use this concatenation; ASR always keeps source time.
                    vector = encoder.encode(np.concatenate(pieces))
                    if np.isfinite(vector).all():
                        vectors.append(vector)
                        samples.extend(piece_samples)
                        total += piece_frames
                    pieces, piece_samples, piece_frames = [], [], 0
                a = end
        if total < 150:
            continue
        centroid = np.mean(vectors, axis=0)
        centroid /= max(float(np.linalg.norm(centroid)), 1e-9)
        if any(float(vector @ centroid) < .60 for vector in vectors):
            continue
        anchors[speaker] = centroid
        records.append(dict(speaker=speaker, dimensions=192, embedding=centroid.tolist(), speechMs=total * 20, segments=samples))
    return anchors, records


def link_file(source, destination):
    import shutil
    if destination.exists():
        return
    try:
        destination.hardlink_to(source)
    except OSError:
        shutil.copy2(source, destination)


class SegmentEngine:
    def __init__(self, pcm, directory, root, config, acoustic, encoder, anchors, mapping, activity, recovered):
        self.pcm, self.directory, self.root, self.config = pcm, directory, root, config
        self.acoustic, self.encoder, self.anchors, self.mapping = acoustic, encoder, anchors, mapping
        self.activity, self.recovered = activity, recovered
        self.asr_lock = threading.Lock()
        # Windows TF/Keras depthwise kernels can corrupt the heap when the same
        # YAMNet instance is entered concurrently. Pipeline CPU detection with GPU
        # ASR, but never execute two calls on this instance at once.
        self.acoustic_lock = threading.Lock()
        self.encoder_lock = threading.Lock()
        self.asr = None
        self.separation_errors = {}
        self.execution = stt_execution(root)
        self.separation_execution = dict(device="not-run", model="speechbrain/sepformer-whamr16k", sources=2)

    def wave(self, group):
        return self.pcm[round(group["contextStartMs"] * 16):round(group["contextEndMs"] * 16)]

    def separate(self, groups, report):
        from .separation import Separator, REVISION
        self.separation_execution["revision"] = REVISION
        selected = [g for g in groups if g["separate"] and not g["more_than_two"] and self.config.segment_separation]
        if not selected:
            return
        separator = Separator(self.root, self.execution["device"])
        self.separation_execution["device"] = self.execution["device"]
        try:
            for i, group in enumerate(selected):
                report(i / len(selected))
                try:
                    outputs = separator.separate(self.wave(group))
                    for n, output in enumerate(outputs):
                        wavfile.write(self.directory / f"stem_{group['id']}_{n}.wav", 16000, output.astype(np.float32))
                except Exception as exc:
                    self.separation_errors[group["id"]] = f"음원 분리 실패 ({type(exc).__name__})"
                    for n in range(2):
                        (self.directory / f"stem_{group['id']}_{n}.wav").unlink(missing_ok=True)
                    if self.execution["device"] == "cuda":
                        separator.torch.cuda.empty_cache()
        finally:
            separator.close()
            del separator
            gc.collect()

    def transcribe(self, wave, speech):
        if not np.any(speech >= .35):
            return []
        # The shared GPU worker executes one Whisper inference at a time; CPU detection can proceed concurrently.
        with self.asr_lock:
            rows, _ = self.asr.transcribe(wave, language="ko", task="transcribe", word_timestamps=True,
                vad_filter=False, beam_size=5, condition_on_previous_text=False)
            result = []
            for row in rows:
                if row.no_speech_prob > .8 and row.avg_logprob < -1:
                    continue
                result.append(dict(startMs=row.start * 1000, endMs=row.end * 1000, text=row.text.strip(),
                    words=[dict(startMs=w.start * 1000, endMs=w.end * 1000, text=w.word) for w in row.words or []]))
            return result

    def analyze_group(self, group):
        if group["id"] in self.separation_errors:
            raise ValueError(self.separation_errors[group["id"]])
        started = time.perf_counter()
        paths = [self.directory / f"stem_{group['id']}_{i}.wav" for i in range(2)]
        separated = all(path.is_file() for path in paths)
        waves = [wavfile.read(path)[1] for path in paths] if separated else [self.wave(group)]
        lanes, vectors = [], []
        a, b = int(group["startMs"] // 20), int(np.ceil(group["endMs"] / 20))
        left = int((group["startMs"] - group["contextStartMs"]) // 20)
        for n, wave in enumerate(waves):
            with self.acoustic_lock:
                acoustic = self.acoustic.analyze(wave, include_frames=True)
            speech, laughter, _, energy = independent_tracks(acoustic, len(wave) / 16, self.config.threshold)
            clean = (speech >= .5) & (laughter < min(.025, self.config.threshold*.25))
            samples = [wave[x * 320:min(len(wave), y * 320)] for x, y in ranges(clean) if y - x >= 10]
            voice = np.concatenate(samples)[:16000 * 15] if samples else np.array([])
            with self.encoder_lock:
                vector = self.encoder.encode(voice) if len(voice) >= 24000 and self.anchors else None
            vectors.append(vector)
            utterances = trim_utterances(self.transcribe(wave, speech), group["startMs"], group["endMs"], group["contextStartMs"])
            crop = slice(left, left + b - a)
            from .laughter_debug import record
            lanes.append(dict(id=f"{group['id']}_{n}", group_id=group["id"], startMs=group["startMs"], endMs=group["endMs"],
                contextStartMs=group["contextStartMs"], contextEndMs=group["contextEndMs"], state="complete",
                separated=separated, audio_file=paths[n].name if separated else None,
                energy_source="separated_source" if separated else "solo_original" if not group["separate"] else "unresolved_mixture",
                speech=np.round(speech[crop], 5).tolist(), laughter=np.round(laughter[crop], 5).tolist(),
                energy=np.round(energy[crop], 7).tolist(), energy_reference=max(.0002, float(np.quantile(energy[crop], .9))),
                laughter_threshold=self.config.threshold*.5, utterances=utterances, review_reasons=[],
                laughter_debug=record(acoustic, group["contextStartMs"], group["startMs"], group["endMs"], self.config.threshold)))
        matches = speaker_matches(vectors, self.anchors)
        # Near-identical outputs are leakage, not two independently established speakers.
        leakage = separated and abs(float(np.dot(waves[0], waves[1]))) / max(
            float(np.linalg.norm(waves[0]) * np.linalg.norm(waves[1])), 1e-12) > .95
        for lane, match in zip(lanes, matches):
            lane.update(match)
            if separated:
                if leakage:
                    lane["speaker"] = None
                    lane["review_reasons"].append("separation_leakage")
                if not lane["speaker"]:
                    lane["review_reasons"].append("separated_voice_unconfirmed")
            elif group["separate"]:
                lane["speaker"] = None
                lane["review_reasons"].append("more_than_two_sources" if group["more_than_two"] else "separation_disabled")
            else:
                mean = np.mean(self.activity[a:b], axis=0)
                order = np.argsort(-mean)
                clear = mean[order[0]] >= .6 and mean[order[1]] <= .25
                provisional = self.mapping.get(int(order[0])) if clear else None
                has_speech = np.any(np.asarray(lane["speech"]) >= .35)
                if not has_speech:
                    laughter_mean = np.mean(self.recovered[a:b], axis=0)
                    provisional = self.mapping.get(int(np.argmax(laughter_mean))) if max(laughter_mean, default=0) >= .6 else None
                if provisional and (not lane["speaker"] or lane["speaker"] == provisional):
                    # A confidently conflicting embedding must not inherit a nearby speaker.
                    scores = {c["speaker"]: c["score"] for c in match["candidates"]}
                    if provisional not in scores or scores[provisional] >= .55:
                        lane["speaker"] = provisional
                elif provisional and lane["speaker"] != provisional:
                    lane["speaker"] = None
                    lane["review_reasons"].append("speaker_evidence_conflict")
                if not lane["speaker"]:
                    lane["review_reasons"].append("speaker_unconfirmed")
                if len(group.get("columns", [])) > 1:
                    lane["speaker"] = None
                    lane["review_reasons"].append("speaker_turn_boundary")
            lane["elapsed_ms"] = round((time.perf_counter() - started) * 1000)
        return lanes

    def analyze(self, groups, on_result, report):
        from faster_whisper import WhisperModel
        self.asr = WhisperModel(str(self.root / "whisper"), device=self.execution["device"],
            compute_type=self.execution["compute_type"], cpu_threads=4, num_workers=1, local_files_only=True)
        iterator = iter(groups)
        finished = 0
        try:
            with ThreadPoolExecutor(max_workers=2, thread_name_prefix="character-segment") as pool:
                pending = {}
                def submit():
                    group = next(iterator, None)
                    if group is not None:
                        pending[pool.submit(self.analyze_group, group)] = group
                submit()
                submit()
                while pending:
                    done, _ = wait(pending, return_when=FIRST_COMPLETED)
                    for future in done:
                        group = pending.pop(future)
                        try:
                            rows = future.result()
                        except Exception as exc:
                            rows = [dict(id=group["id"] + "_0", group_id=group["id"], startMs=group["startMs"],
                                endMs=group["endMs"], contextStartMs=group["contextStartMs"], contextEndMs=group["contextEndMs"],
                                speaker=None, state="failed", error=str(exc) if isinstance(exc, ValueError) else f"구간 분석 실패 ({type(exc).__name__})",
                                review_reasons=["segment_failed"], utterances=[], speech=[], laughter=[], energy=[])]
                        on_result(group, rows)
                        finished += 1
                        report(finished / max(1, len(groups)))
                        submit()
        finally:
            self.asr.model.unload_model()
            self.asr = None
            gc.collect()


def read_cached(directory, group):
    path = directory / f"segment_{group['id']}.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None
