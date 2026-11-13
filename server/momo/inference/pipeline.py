import time
import numpy as np
from ..config import settings
from ..contracts import ModelInfo, Warning
from ..jobs import model_lock_id
from ..prepare_voice_model import MODEL_ID, REVISION
from ..prepare_models import verify
from .audio_io import decode
from .fusion import assemble, fixture


class Pipeline:
    def __init__(self):
        self.metrics = {}
        self.lock_id = model_lock_id()
        self.loaded = False
        self.load_models()

    def load_models(self):
        if self.loaded:
            return
        if settings.profile != "fixture":
            lock = verify()
            if settings.inference_device == "cuda":
                from .runtime import prepare_cuda
                prepare_cuda()
            from .acoustic import AcousticEngine
            from .diarization import DiarizationEngine
            from .asr import TranscriptionEngine
            from .voice import VoiceEncoder
            self.voice = VoiceEncoder(settings.model_root)
            self.acoustic = AcousticEngine(settings.model_root)
            self.diarization = DiarizationEngine(settings.model_root, settings.inference_device)
            self.asr = TranscriptionEngine(settings.model_root, settings.inference_device)
            self.models = [ModelInfo(name=name, revision=revision,
                runtime=("onnxruntime-"+settings.inference_device if name == "nemotron" else
                         "ctranslate2-"+settings.inference_device if name == "whisper" else "tensorflow-cpu" if name == "yamnet" else "onnxruntime-cpu"),
                precision="float16" if name == "whisper" and settings.inference_device == "cuda" else "int8" if name == "whisper" else "float32")
                for name, revision in lock["revisions"].items()]
            self.models.append(ModelInfo(name="ecapa-voxceleb", revision=REVISION, runtime="pytorch-cpu", precision="float32"))
        self.loaded = True

    def release_models(self):
        # The web worker owns the accelerator during episode production. Do not keep
        # a second Nemotron/Whisper instance resident in the dispatcher process.
        import gc
        for name in ("voice", "acoustic", "diarization", "asr"):
            if hasattr(self, name):
                delattr(self, name)
        self.loaded = False
        gc.collect()

    def run(self, job, source, pcm_path, progress, cancel_check):
        self.load_models()
        self.metrics = {}
        last = time.monotonic()
        previous_stage = None
        def stage(name, value):
            nonlocal last, previous_stage
            now = time.monotonic()
            if previous_stage:
                self.metrics[previous_stage] = now-last
            last = now
            previous_stage = name
            cancel_check()
            progress(name, value)
        stage("decoding", .03)
        pcm, duration, mime = decode(source, pcm_path)
        if settings.profile == "fixture":
            stage("assembling", .9)
            return fixture(job.id, job.audio_sha256, duration, mime)
        stage("acoustic", .12)
        acoustic = self.acoustic.analyze(pcm, cancel_check)
        stage("diarization", .35)
        activity = self.diarization.analyze(pcm, job.options,
                   lambda fraction: progress("diarization", .35+fraction*.23), cancel_check)
        laughter = np.zeros_like(activity)
        if "laughter" in acoustic["kinds"]:
            from .laughter import vocal_only, recover
            stage("laughter_recheck", .6)
            secondary = self.diarization.analyze(vocal_only(pcm, acoustic["kinds"]), job.options,
                         lambda fraction: progress("laughter_recheck", .6+fraction*.13), cancel_check)
            laughter = recover(activity, secondary, acoustic["kinds"])
        stage("transcription", .75)
        captions = self.asr.analyze(pcm, acoustic, cancel_check) if "speech" in acoustic["kinds"] else []
        stage("assembling", .95)
        result, tracks = assemble(job.id, job.audio_sha256, mime, duration, acoustic, activity, laughter, captions,
                          self.models, job.options["speaker_count_hint"])
        stage("speaker_signatures", .97)
        result.voice_model_id = MODEL_ID
        result.speaker_signatures = self.voice.analyze(pcm, result, cancel_check)
        if len(result.speaker_signatures) < len(result.speakers):
            result.warnings.append(Warning(code="VOICE_SAMPLE_INSUFFICIENT", message="일부 화자는 겹치지 않는 목소리가 부족하거나 일정하지 않아 기본 캐릭터를 사용합니다."))
        self.metrics["speaker_signatures"] = time.monotonic()-last
        return result, tracks
