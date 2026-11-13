"""Nemotron FP32 streaming frontend and arrival-order cache. State is scoped to one analyze()."""
import json
import math
import numpy as np
from scipy.special import expit
from .features import FeatureExtractor
from ..errors import ServiceError


def top_k(values, count):
    # Stable ties reproduce tensor.ts: descending score, then lower source index.
    return np.argsort(-values, kind="stable")[:min(count, len(values))]


class SpeakerCache:
    def __init__(self, config, hidden=512):
        self.c = config
        self.embeds = np.zeros((0, hidden), np.float32)
        self.probs = np.zeros((0, config["num_speakers"]), np.float32)
        self.fifo = np.zeros((0, hidden), np.float32)
        self.compressed = False
        budget = config["speaker_cache_length"] // config["num_speakers"] - config["speaker_cache_silence_frames_per_speaker"]
        self.minimum = math.floor(budget * config["min_positive_scores_rate"])
        self.strong = math.floor(budget * config["strong_boost_rate"])
        self.weak = math.floor(budget * config["weak_boost_rate"])

    def cached(self):
        return np.concatenate((self.embeds, self.fifo))

    def update(self, embeds, logits, silence, chunk_frames):
        c = self.c
        factor = c["subsampling_factor"]
        rows = len(logits) // factor
        probs = expit(logits[:rows*factor].astype(np.float64)).reshape(rows, factor, -1).mean(axis=1).astype(np.float32)
        start = len(self.embeds) + len(self.fifo)
        fifo = np.concatenate((self.fifo, embeds[start:start+chunk_frames]))
        if len(fifo) > c["fifo_length"]:
            popped = min(len(fifo), max(c["speaker_cache_update_period"], len(fifo)-c["fifo_length"]))
            fifo_probs = probs[len(self.embeds):len(self.embeds)+len(fifo)]
            stored = self.probs if self.compressed else probs[:len(self.embeds)]
            self.embeds = np.concatenate((self.embeds, fifo[:popped]))
            self.probs = np.concatenate((stored, fifo_probs[:popped]))
            fifo = fifo[popped:]
            if len(self.embeds) > c["speaker_cache_length"]:
                self.compress(silence)
                self.compressed = True
        self.fifo = fifo.copy()

    def compress(self, silence):
        c = self.c
        p = self.probs.astype(np.float64)
        complement = np.log(np.maximum(1-p, c["prediction_score_threshold"]))
        scores = (np.log(np.maximum(p, c["prediction_score_threshold"])) - complement +
                  complement.sum(axis=1, keepdims=True) - math.log(.5)).astype(np.float32)
        scores[p <= .5] = -np.inf
        for speaker in range(p.shape[1]):
            if np.sum(scores[:, speaker] > 0) >= self.minimum:
                scores[scores[:, speaker] <= 0, speaker] = -np.inf
        capacity = c["speaker_cache_length"]
        scores[capacity:] += c["latest_frames_score_boost"]
        for count, boost in [(self.strong, -2*math.log(.5)), (self.weak, -math.log(.5))]:
            for speaker in range(p.shape[1]):
                scores[top_k(scores[:, speaker], count), speaker] += boost
        padded = np.concatenate((scores, np.full((c["speaker_cache_silence_frames_per_speaker"], p.shape[1]), np.inf, np.float32)))
        flat = padded.T.reshape(-1)
        selected = top_k(flat, capacity)
        selected[np.isneginf(flat[selected])] = len(flat)
        selected.sort()
        indices = np.where(selected == len(flat), len(p), np.minimum(selected % len(padded), len(p)))
        self.embeds = np.concatenate((self.embeds, silence.reshape(1, -1)))[indices]
        self.probs = np.concatenate((self.probs, np.zeros((1, p.shape[1]), np.float32)))[indices]


class DiarizationEngine:
    def __init__(self, root, device="cuda"):
        import onnxruntime as ort
        self.model = json.loads((root / "nemotron/config.json").read_text())
        self.processor = json.loads((root / "nemotron/processor_config.json").read_text())
        provider = "CUDAExecutionProvider" if device == "cuda" else "CPUExecutionProvider"
        if device == "cuda":
            ort.preload_dlls()
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        self.session = ort.InferenceSession(str(root / "nemotron/onnx/model.onnx"), sess_options=options,
                                           providers=[provider])
        if self.session.get_providers()[0] != provider:
            raise ServiceError("MODEL_RUNTIME_UNAVAILABLE", "요청한 추론 장치를 사용할 수 없습니다.", 503)
        if {x.name for x in self.session.get_inputs()} != {"input_features", "cached_embeds", "attention_mask"}:
            raise RuntimeError("Unexpected Nemotron input signature")
        if {x.name for x in self.session.get_outputs()} != {"logits", "chunk_embeds", "silence_embeds"}:
            raise RuntimeError("Unexpected Nemotron output signature")
        self.provider = provider

    def analyze(self, pcm, options=None, progress=lambda x: None, cancel_check=lambda: None):
        # low_latency mode matches v13 worker; never force the requested speaker count.
        chunk_length, right = self.processor["streaming_modes"]["low_latency"]
        factor = self.model["audio_config"]["subsampling_factor"]
        mel_count, mel_step = (chunk_length+right)*factor, chunk_length*factor
        extractor = FeatureExtractor()
        cache = SpeakerCache(self.model["streaming_config"], self.model["audio_config"]["hidden_size"])
        cursor, mel_index, first = 0, 0, True
        emitted = []

        def run(samples, centered, lookahead):
            features = extractor.extract(samples, centered)
            if not len(features):
                return
            cached = cache.cached()
            encoder_count = math.ceil(len(features)/factor)
            total = len(cached) + encoder_count
            outputs = dict(zip([o.name for o in self.session.get_outputs()], self.session.run(None, {
                "input_features": features[None], "cached_embeds": cached[None],
                "attention_mask": np.ones((1, total), np.int64)})))
            logits = outputs["logits"].reshape(-1, 8)[:total*factor]
            embeds = outputs["chunk_embeds"].reshape(-1, cached.shape[1])
            count = encoder_count-lookahead
            cache.update(np.concatenate((cached, embeds)), logits, outputs["silence_embeds"], count)
            start = len(cached)*factor
            values = logits[start:start+count*factor][:len(features)]
            emitted.append(expit(values.astype(np.float64)).astype(np.float32))

        while True:
            cancel_check()
            needed = (mel_count-1)*160+200 if first else mel_count*160+400
            if len(pcm)-cursor < needed:
                run(pcm[cursor:], first, 0)
                break
            run(pcm[cursor:cursor+needed], first, right)
            first = False
            mel_index += mel_step
            cursor = mel_index*160-256
            progress(min(1., cursor/max(1, len(pcm))))
        probabilities = np.concatenate(emitted)[:len(pcm)//160] if emitted else np.zeros((0, 8), np.float32)
        if not np.isfinite(probabilities).all():
            raise ValueError("Nonfinite diarization output")
        frames = math.ceil(len(pcm)/320)
        activity = np.zeros((frames, 8), np.float32)
        for i in range(frames):
            pair = probabilities[i*2:i*2+2]
            if len(pair):
                activity[i] = np.round(pair.mean(axis=0), 5)
        return activity
