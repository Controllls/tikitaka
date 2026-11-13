"""Official CPU YAMNet at 160ms patch hop + Silero v5; v13 dual-view decision policy."""
import math
import sys
import numpy as np
from .features import envelope, speech_band, quantile


def laughter_decisions(frames, *, threshold_scale=1.):
    # The app keeps scale=1. Segment experiments scale absolute score gates while
    # preserving vocal/noise contrast and minimum-duration checks.
    eligible = [f["laughter"] >= .05*threshold_scale and f["laughter"] >= f["otherVocal"]*1.15 and
                f["laughter"] >= f["nonVocal"]*.9 for f in frames]
    values = np.zeros(len(frames))
    reasons = ["score" if f["laughter"] < .05*threshold_scale else
               "other_voice" if f["laughter"] < f["otherVocal"]*1.15 else
               "non_voice" if f["laughter"] < f["nonVocal"]*.9 else "duration" for f in frames]
    def groups(flags):
        start = 0
        while start < len(flags):
            if not flags[start]:
                start += 1
                continue
            end = start+1
            while end < len(flags) and flags[end]:
                end += 1
            yield start, end
            start = end
    for a, b in groups(eligible):
        scores = [f["laughter"] for f in frames[a:b]]
        peak = max(scores)
        if peak >= .24*threshold_scale or (peak >= .12*threshold_scale and b-a >= 2) or sum(x >= .085*threshold_scale for x in scores) >= 3:
            values[a:b] = scores
            reasons[a:b] = ["accepted"] * (b-a)
    quiet = [bool(f.get("band")) and f["laughter"] >= .006*threshold_scale and f["band"]["laughter"] >= .05*threshold_scale and
             f["band"]["laughter"] >= f["laughter"]*1.4 and f["otherVocal"] < .03 and
             f["band"]["otherVocal"] < .03 and f["nonVocal"] < .1 and f["band"]["nonVocal"] < .1 for f in frames]
    for a, b in groups(quiet):
        band = [f["band"]["laughter"] for f in frames[a:b]]
        if b-a >= 3 and max(f["laughter"] for f in frames[a:b]) >= .025*threshold_scale and max(band) >= .08*threshold_scale and np.mean(band) >= .065*threshold_scale:
            for i in range(a, b):
                if band[i-a] > values[i]:
                    reasons[i] = "band"
            values[a:b] = np.maximum(values[a:b], band)
    return values, reasons


def laughter_track(frames, *, threshold_scale=1.):
    return laughter_decisions(frames, threshold_scale=threshold_scale)[0]


def classify(pcm, frames, vad):
    rms, voice = envelope(pcm)
    noise = max(.00012, quantile(rms, .1))
    laughs = laughter_track(frames)
    kinds, scores = [], []
    centers = np.arange(len(frames))*.16 + .4875
    for i, energy in enumerate(rms):
        t = (i+.5)*.02
        weights = np.maximum(0, 1-np.abs(t-centers)/.7)
        speech = np.dot(weights, [f["speech"] for f in frames])/sum(weights) if sum(weights) else 0
        laugh = np.interp(t, centers, laughs)
        vi = math.floor(t/.032)
        probability = max(vad[max(0, vi-1):vi+2], default=0)
        audible = energy > max(.00018, min(.002, noise*.75))
        kind = "silence" if not audible else "laughter" if laugh >= .05 else "speech" if probability >= .45 and speech >= .18 else "environment"
        kinds.append(kind)
        scores.append(float(laugh if kind == "laughter" else speech))
    for i in range(1, len(kinds)-1):
        if kinds[i] == "silence" and kinds[i-1] == kinds[i+1] != "silence":
            kinds[i] = kinds[i-1]
    spans = []
    for i, kind in enumerate(kinds):
        if spans and spans[-1][2] == kind:
            spans[-1][1] = i+1
        else:
            spans.append([i, i+1, kind])
    for i in range(len(spans)-2, 0, -1):
        if (spans[i][1]-spans[i][0])*.02 < .1 and spans[i-1][2] == spans[i+1][2]:
            spans[i-1][1] = spans[i+1][1]
            del spans[i:i+2]
    for a, b, kind in spans:
        kinds[a:b] = [kind]*(b-a)
    return dict(kinds=kinds, scores=scores, energy=voice, rms=rms, vad=vad)


class AcousticEngine:
    def __init__(self, root):
        import tensorflow as tf
        tf.config.set_visible_devices([], "GPU")
        tf.config.threading.set_intra_op_parallelism_threads(4)
        tf.config.threading.set_inter_op_parallelism_threads(2)
        sys.path.insert(0, str((root / "yamnet").resolve()))
        import params
        import yamnet
        self.tf = tf
        self.model = yamnet.yamnet_frames_model(params.Params(patch_hop_seconds=.16))
        self.model.load_weights(str(root / "yamnet/yamnet.h5"))
        import onnxruntime as ort
        self.vad = ort.InferenceSession(str(root / "silero/silero-v5.1.2.onnx"), providers=["CPUExecutionProvider"])

    def analyze(self, pcm, cancel_check=lambda: None, *, vad=None, report=lambda value: None, include_frames=False):
        cancel_check()
        # Full waveform frontend preserves the official 975ms windows at 160ms hops.
        count = max(1, 1+math.ceil((len(pcm)-15600)/2560))
        batches = []
        for first in range(0, count, 10):
            cancel_check()
            report(.6 * first / count)
            size = min(10, count-first)
            sample_count = (size-1)*2560+15600
            chunk = pcm[first*2560:first*2560+sample_count]
            chunk = np.pad(chunk, (0, max(0, sample_count-len(chunk))))
            batches.append(self.model(self.tf.convert_to_tensor(chunk))[0].numpy()[:size])
        scores = np.concatenate(batches)
        frames = [dict(speech=float(max(s[:13])), laughter=float(max(s[13:19])),
                       otherVocal=float(max(s[19:42])), nonVocal=float(max(s[42:]))) for s in scores]
        candidates = [i for i, f in enumerate(frames) if f["laughter"] >= .006 and f["otherVocal"] < .03 and f["nonVocal"] < .1]
        if candidates:
            cancel_check()
            filtered = speech_band(pcm)
            for index, i in enumerate(candidates):
                cancel_check()
                report(.6 + .25 * index / len(candidates))
                chunk = filtered[i*2560:i*2560+15600]
                chunk = np.pad(chunk, (0, max(0,15600-len(chunk))))
                s = self.model(self.tf.convert_to_tensor(chunk))[0].numpy()[0]
                frames[i]["band"] = dict(laughter=float(max(s[13:19])), otherVocal=float(max(s[19:42])), nonVocal=float(max(s[42:])))
        state = np.zeros((2, 1, 128), np.float32)
        context = np.zeros(64, np.float32)
        if vad is None:
            vad = []
            for start in range(0, len(pcm), 512):
                cancel_check()
                chunk = np.pad(pcm[start:start+512], (0, max(0, 512-len(pcm[start:start+512]))))
                inputs = np.concatenate((context, chunk))[None]
                result, state = self.vad.run(None, {"input": inputs, "state": state, "sr": np.array(16000, np.int64)})
                vad.append(float(result.reshape(-1)[0]))
                context = inputs[0, -64:]
        result = classify(pcm, frames, vad)
        if include_frames:
            result["frames"] = frames
        report(1)
        return result
