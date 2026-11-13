"""Run the app backend's actual inference components for the web's app preset.

No binary segment approximation or active-speaker shortcut is used here.
The independent experiment preset remains available for alternative models.
"""
import math
import mimetypes

import numpy as np

from ..config import settings
from ..contracts import ModelInfo, Warning
from ..inference.fusion import assemble, speaker_order
from ..inference.laughter import recover, vocal_only
from ..prepare_voice_model import MODEL_ID, REVISION
from ..prepare_models import NEMOTRON, WHISPER, TF_MODELS
from . import stages
from .execution import stt_execution

POLICY = "app-backend-v1"


class AppPipeline:
    def __init__(self, pcm, root, config, identity, source):
        self.pcm, self.root, self.config = pcm, root, config
        self.identity, self.source = identity, source
        self.duration_ms = round(len(pcm) / 16)
        self.acoustic = None
        self.primary = self.laughing = None
        self.captions, self.signatures = [], []
        self.models = []

    def run(self, stage, previous, report):
        return getattr(self, stage)(previous, report)

    def vad(self, previous, report):
        result = stages.vad(self.pcm, "silero-v5", self.root, report)
        self.models.append(ModelInfo(name="silero", revision="5.1.2", runtime="onnxruntime-cpu", precision="float32"))
        return result

    def laughter(self, previous, report):
        from ..inference.acoustic import AcousticEngine
        self.acoustic = AcousticEngine(self.root).analyze(self.pcm,
            vad=previous["vad"]["data"]["probabilities"], report=report, include_frames=True)
        self.models.append(ModelInfo(name="yamnet", revision=TF_MODELS, runtime="tensorflow-cpu", precision="float32"))
        windows = [{"startMs": i * 160, "endMs": min(self.duration_ms, i * 160 + 975),
                    "laughter": f["laughter"], "otherVocal": f["otherVocal"], "nonVocal": f["nonVocal"],
                    **({"band": f["band"]} if "band" in f else {})}
                   for i, f in enumerate(self.acoustic.pop("frames"))]
        # Use the app's final 20 ms decisions, including its quiet-laughter checks.
        events = []
        for i, kind in enumerate(self.acoustic["kinds"]):
            if kind != "laughter":
                continue
            start, end = i * 20, min(self.duration_ms, (i + 1) * 20)
            score = self.acoustic["scores"][i]
            window = {"startMs": start, "endMs": end, "laughter": score}
            if events and events[-1]["endMs"] == start:
                event = events[-1]
                event.update(endMs=end, maxScore=max(event["maxScore"], score), windowCount=event["windowCount"] + 1)
                event["windows"].append(window)
            else:
                events.append({"eventId": len(events) + 1, "startMs": start, "endMs": end,
                               "maxScore": score, "windowCount": 1, "windows": [window]})
        return {"windows": windows, "events": events, "policy": POLICY, "resolution": "975 ms / 160 ms hop → 20 ms",
                "note": "앱의 원음·음성 대역 재검사와 연속 구간 판정을 사용합니다. 단일 감도 값으로 판정하지 않습니다."}

    def diarization(self, previous, report):
        from ..inference.diarization import DiarizationEngine
        if settings.lab_device == "gpu":
            stt_execution(self.root)  # Register the same CUDA libraries before ONNX Runtime.
        device = "cuda" if settings.lab_device == "gpu" else "cpu"
        engine = DiarizationEngine(self.root, device)
        has_laughter = "laughter" in self.acoustic["kinds"]
        self.primary = engine.analyze(self.pcm, progress=lambda p: report(p * (.5 if has_laughter else 1)))
        self.laughing = np.zeros_like(self.primary)
        if has_laughter:
            secondary = engine.analyze(vocal_only(self.pcm, self.acoustic["kinds"]), progress=lambda p: report(.5 + p * .5))
            self.laughing = recover(self.primary, secondary, self.acoustic["kinds"])
        self.models.append(ModelInfo(name="nemotron", revision=NEMOTRON, runtime="onnxruntime-" + device, precision="float32"))
        # Release the ONNX session before loading Whisper on the same GPU.
        del engine
        activity = self.primary.copy()
        activity[~np.isin(self.acoustic["kinds"], ["speech", "laughter"])] = 0
        order = speaker_order(activity, self.laughing)
        segments = []
        for index, (_, column) in enumerate(order):
            rows = stages.spans_from_flags((activity[:, column] > .5) | (self.laughing[:, column] >= .5),
                20, self.duration_ms, min_ms=0, gap_ms=0, pad_ms=0)
            segments.extend(dict(row, speaker=f"S{index + 1}") for row in rows)
        recovered = np.any(self.laughing >= .5, axis=1)
        laugh_frames = np.array(self.acoustic["kinds"]) == "laughter"
        report(1)
        return {"segments": sorted(segments, key=lambda row: (row["startMs"], row["speaker"])),
                "speaker_count": len(order), "policy": POLICY,
                "execution": {"device": device, "backend": "ONNX Runtime"},
                "laughter_recheck": {"performed": has_laughter, "assigned_ms": int(np.sum(recovered & laugh_frames)) * 20,
                                     "unknown_ms": int(np.sum(~recovered & laugh_frames)) * 20},
                "note": "앱과 같은 연속 화자 확률·음성만 남긴 재분석·일반 발화 기준 화자 대응을 사용합니다. 불확실한 웃음은 미확정으로 유지합니다."}

    def stt(self, previous, report):
        execution = {"device": "not-run"}
        if "speech" in self.acoustic["kinds"]:
            execution = stt_execution(self.root)
            from ..inference.asr import TranscriptionEngine
            engine = TranscriptionEngine(self.root, execution["device"])
            self.captions = engine.analyze(self.pcm, self.acoustic, progress=report)
            engine.model.model.unload_model()
            del engine
            self.models.append(ModelInfo(name="whisper", revision=WHISPER, runtime="ctranslate2-" + execution["device"],
                                         precision=execution["compute_type"]))
        utterances = [{"id": i, "startMs": c.start_us / 1000, "endMs": c.end_us / 1000, "text": c.text,
                       "words": [{"startMs": w.start_us / 1000, "endMs": w.end_us / 1000, "text": w.text} for w in c.words]}
                      for i, c in enumerate(self.captions, 1)]
        return {"utterances": utterances, "text": "\n".join(c.text for c in self.captions), "api_usage": [],
                "api_calls": 0, "execution": execution, "policy": POLICY, "timing": "app-original-word-alignment",
                "note": "앱과 동일한 발화 구간 병합·100 ms 여유·Whisper 문장 및 단어 시각을 사용합니다."}

    def assembled(self):
        count = math.ceil(len(self.pcm) / 320)
        activity = self.primary if self.primary is not None else np.zeros((count, 8), np.float32)
        laughter = self.laughing if self.laughing is not None else np.zeros_like(activity)
        order = ["nemotron", "whisper", "yamnet", "silero", "ecapa-voxceleb"]
        models = sorted(self.models, key=lambda m: order.index(m.name))
        mime = self.source.get("original_mime") or mimetypes.guess_type(self.source["name"])[0] or "audio/wav"
        result, tracks = assemble(self.identity, self.source["sha256"], mime, round(len(self.pcm) / 16000 * 1e6),
            self.acoustic, activity, laughter, self.captions, models, hint=self.config.speaker_count)
        if "voice" in self.config.stages():
            result.voice_model_id = MODEL_ID
            result.speaker_signatures = self.signatures
            if len(self.signatures) < len(result.speakers):
                result.warnings.append(Warning(code="VOICE_SAMPLE_INSUFFICIENT", message="일부 화자는 겹치지 않는 목소리가 부족하거나 일정하지 않아 기본 캐릭터를 사용합니다."))
        return result, tracks

    def voice(self, previous, report):
        from ..inference.voice import VoiceEncoder
        result, _ = self.assembled()
        self.signatures = VoiceEncoder(self.root).analyze(self.pcm, result, lambda: None)
        self.models.append(ModelInfo(name="ecapa-voxceleb", revision=REVISION, runtime="pytorch-cpu", precision="float32"))
        rows = [{"speaker": s.speaker_id, "dimensions": len(s.embedding), "embedding": s.embedding,
                 "speechMs": s.speech_us / 1000,
                 "segments": [{"startMs": p.start_us / 1000, "endMs": p.end_us / 1000} for p in s.segments]} for s in self.signatures]
        similarities = [{"a": a["speaker"], "b": b["speaker"], "cosine": float(np.dot(a["embedding"], b["embedding"]))}
                        for i, a in enumerate(rows) for b in rows[i + 1:]]
        report(1)
        return {"signatures": rows, "similarities": similarities, "policy": POLICY,
                "note": "앱과 동일하게 겹치지 않는 일반 발화에서 추출하고, 내부 일관성이 낮은 목소리 벡터는 제외합니다."}
