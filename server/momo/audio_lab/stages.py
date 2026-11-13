"""Real model adapters. All times remain on the uploaded audio's original clock."""
import io
import json
import math
from pathlib import Path
import subprocess
import shutil
import wave

import httpx
import numpy as np

from ..config import settings
from ..highlights.selection import utterances_from_segments
from .models import prepare
from .limits import DISK_RESERVE, duration_limit_message
from .execution import stt_execution


def decode(source: Path, directory: Path, metadata=None):
    """Decode to disk, then read bounded slices through a memory map."""
    pcm_file = directory / "audio.f32"
    try:
        probe = subprocess.run([settings.ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
                                "-select_streams", "a:0", "-show_entries", "stream=index,codec_name,duration:format=duration",
                                "-of", "json", str(source)], check=True, timeout=60,
                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        info = json.loads(probe.stdout)
        if not info.get("streams"):
            raise ValueError("소리가 포함된 원본 파일을 선택해 주세요.")
        if metadata is not None:
            codec = info["streams"][0].get("codec_name", "")
            metadata["original_mime"] = "audio/mpeg" if codec == "mp3" else "audio/mp4" if codec in {"aac", "alac"} else "audio/wav" if codec.startswith("pcm_") else "application/octet-stream"
        duration = info.get("format", {}).get("duration", info["streams"][0].get("duration"))
        duration = float(duration) if duration is not None else settings.lab_max_seconds
        if not math.isfinite(duration) or duration > settings.lab_max_seconds + .1:
            raise ValueError(duration_limit_message())
        if shutil.disk_usage(directory).free < math.ceil(duration * 64_000) + DISK_RESERVE:
            raise ValueError("음성 분석용 임시 파일을 저장할 공간이 부족합니다. 실험 저장 드라이브를 확인하세요.")
        subprocess.run([settings.ffmpeg, "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe",
                        "-i", str(source), "-map", "0:a:0", "-vn", "-t", str(settings.lab_max_seconds + .1), "-ac", "1", "-ar", "16000",
                        "-f", "f32le", str(pcm_file)], check=True, timeout=max(180, settings.lab_max_seconds),
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        count = pcm_file.stat().st_size // 4
        if count > settings.lab_max_seconds * 16000:
            raise ValueError(duration_limit_message())
        if count < 1600:
            raise ValueError("유효한 음성이 없습니다.")
        pcm = np.memmap(pcm_file, dtype="<f4", mode="c")
        for start in range(0, count, 16000 * 60):
            if not np.isfinite(pcm[start:start + 16000 * 60]).all():
                pcm._mmap.close()
                raise ValueError("유효한 음성이 없습니다.")
        return pcm
    except (OSError, subprocess.SubprocessError) as exc:
        raise ValueError("음성을 읽을 수 없습니다. MP3·M4A·WAV·WebM 등 오디오가 있는 파일인지 확인해 주세요.") from exc


def wav_bytes(pcm):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(16000)
        out.writeframes((np.clip(pcm, -1, 1) * 32767).astype("<i2").tobytes())
    return buffer.getvalue()


def spans_from_flags(flags, step_ms, duration_ms, *, min_ms=160, gap_ms=240, pad_ms=80):
    spans = []
    for i, flag in enumerate(flags):
        if flag:
            start, end = i * step_ms, min(duration_ms, (i + 1) * step_ms)
            if spans and start - spans[-1]["endMs"] <= gap_ms:
                spans[-1]["endMs"] = end
            else:
                spans.append({"startMs": start, "endMs": end})
    result = []
    for span in spans:
        if span["endMs"] - span["startMs"] < min_ms:
            continue
        start, end = max(0, span["startMs"] - pad_ms), min(duration_ms, span["endMs"] + pad_ms)
        if result and start <= result[-1]["endMs"]:
            result[-1]["endMs"] = max(end, result[-1]["endMs"])
        else:
            result.append({"startMs": start, "endMs": end})
    return result


def vad(pcm, model, root, report=lambda value: None):
    import onnxruntime as ort
    path = root / "silero/silero-v5.1.2.onnx" if model == "silero-v5" else prepare(model, root)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
    state, context = np.zeros((2, 1, 128), np.float32), np.zeros(64, np.float32)
    probabilities = []
    for start in range(0, len(pcm), 512):
        if start % (512 * 128) == 0:
            report(start / len(pcm))
        frame = pcm[start:start + 512]
        frame = np.pad(frame, (0, 512 - len(frame)))
        inputs = np.concatenate((context, frame))[None]
        probability, state = session.run(None, {"input": inputs, "state": state, "sr": np.array(16000, np.int64)})
        probabilities.append(float(probability.reshape(-1)[0]))
        context = inputs[0, -64:]
    duration = round(len(pcm) / 16)
    spans = spans_from_flags([p >= .5 for p in probabilities], 32, duration)
    return {"spans": spans, "speechMs": sum(s["endMs"] - s["startMs"] for s in spans),
            "threshold": .5, "frameMs": 32, "probabilities": probabilities}


def laughter(pcm, model, root, report=lambda value: None):
    names = {"Laughter", "Baby laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle"}
    windows = []
    if model == "yamnet":
        import tensorflow as tf
        interpreter = tf.lite.Interpreter(model_path=str(root / "highlights/yamnet.tflite"), num_threads=4)
        interpreter.allocate_tensors()
        inp, out = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
        labels = (root / "highlights/yamnet_labels.txt").read_text(encoding="utf-8").splitlines()
        indices = [i for i, name in enumerate(labels) if name in names]
        for offset in range(0, max(1, len(pcm) - 15600 + 1), 7800):
            report(offset / len(pcm))
            clip = pcm[offset:offset + 15600]
            frame = np.pad(clip, (0, 15600 - len(clip)))
            interpreter.set_tensor(inp["index"], frame.astype(np.float32))
            interpreter.invoke()
            scores = interpreter.get_tensor(out["index"]).reshape(-1)
            windows.append({"startMs": offset // 16, "endMs": (offset + len(clip)) // 16,
                            "laughter": float(max(scores[indices])), "music": float(scores[labels.index("Music")])})
    else:
        import torch
        from transformers import ASTFeatureExtractor, ASTForAudioClassification
        torch.set_num_threads(4)
        path = prepare("ast", root)
        extractor = ASTFeatureExtractor.from_pretrained(path, local_files_only=True)
        network = ASTForAudioClassification.from_pretrained(path, local_files_only=True, use_safetensors=True).eval()
        labels = network.config.id2label
        indices = [i for i, name in labels.items() if name in names]
        music = next(i for i, name in labels.items() if name == "Music")
        if not indices:
            raise ValueError("AST 모델의 웃음 라벨을 찾을 수 없습니다.")
        for offset in range(0, len(pcm), 80_000):
            report(offset / len(pcm))
            clip = pcm[offset:offset + 80_000]
            with torch.inference_mode():
                # AST's filterbank requires at least one 25 ms frame, even at the file tail.
                frame = np.pad(clip, (0, max(0, 400 - len(clip))))
                inputs = extractor(frame, sampling_rate=16000, return_tensors="pt")
                scores = network(**inputs).logits.sigmoid()[0].numpy()
            windows.append({"startMs": offset // 16, "endMs": (offset + len(clip)) // 16,
                            "laughter": float(max(scores[indices])), "music": float(scores[music])})
    return {"windows": windows, "resolution": "975 ms / 487.5 ms hop" if model == "yamnet" else "5 s",
            "note": "모델별 점수와 시간 해상도가 달라 동일 기준값의 결과를 직접 확률 비교할 수 없습니다."}


def speech_chunks(spans, maximum_ms=28_000):
    merged = []
    for span in spans:
        if merged and span["startMs"] - merged[-1]["endMs"] <= 2000 and span["endMs"] - merged[-1]["startMs"] <= maximum_ms:
            merged[-1]["endMs"] = span["endMs"]
        else:
            merged.append(dict(span))
    result = []
    for span in merged:
        for start in range(span["startMs"], span["endMs"], maximum_ms):
            result.append({"startMs": start, "endMs": min(span["endMs"], start + maximum_ms)})
    return result


def audio_api(pcm, model, credentials, *, diarized=False, client=None):
    from ..topic_lab import headers
    if credentials is None:
        raise ValueError("OpenAI API 키가 필요합니다.")
    data = {"model": model}
    if diarized:
        data.update(response_format="diarized_json", chunking_strategy="auto")
    elif model == "whisper-1":
        data.update(response_format="verbose_json", **{"timestamp_granularities[]": "word"}, language="ko")
    own = client is None
    client = client or httpx.Client(timeout=httpx.Timeout(300, connect=20))
    try:
        response = client.post("https://api.openai.com/v1/audio/transcriptions", headers=headers(credentials),
                               data=data, files={"file": ("audio.wav", wav_bytes(pcm), "audio/wav")})
        if response.is_error:
            errors = {401: "API 키 인증 실패", 403: "모델 접근 권한 없음", 429: "API 잔액·사용 한도 초과",
                      404: "모델을 찾을 수 없음", 400: "모델 또는 요청 형식 미지원"}
            raise ValueError("OpenAI 음성 API: " + errors.get(response.status_code, "서버 오류") + ". 자동 재호출하지 않았습니다.")
        result = response.json()
        if not isinstance(result, dict):
            raise ValueError("OpenAI 음성 API 응답 형식이 올바르지 않습니다.")
        return result
    except httpx.HTTPError as exc:
        raise ValueError("OpenAI 음성 연결 실패 또는 시간 초과입니다. 사용량을 확인한 뒤 다시 실행하세요.") from exc
    finally:
        if own:
            client.close()


def transcribe(pcm, model, root, vad_result, credentials, report=lambda value: None):
    spans = speech_chunks(vad_result["spans"])
    utterances, usage = [], []
    network = None
    execution = {"device": "api" if model.startswith("openai:") else "not-run"}
    if not model.startswith("openai:") and spans:
        execution = stt_execution(root)
        from faster_whisper import WhisperModel
        path = root / "whisper" if model == "whisper-small" else prepare(model, root)
        network = WhisperModel(str(path), device=execution["device"], compute_type=execution["compute_type"], cpu_threads=4,
                               num_workers=1, local_files_only=True)
        if network.model.device != execution["device"]:
            raise ValueError("STT가 요청한 실행 장치로 로드되지 않았습니다.")
    for index, span in enumerate(spans):
        report(index / len(spans))
        offset = span["startMs"] / 1000
        clip = pcm[span["startMs"] * 16:span["endMs"] * 16]
        if network:
            segments, _ = network.transcribe(clip, language="ko", word_timestamps=True, vad_filter=False,
                                             condition_on_previous_text=False, beam_size=5)
            for segment in segments:
                if segment.no_speech_prob > .8 and segment.avg_logprob < -1:
                    continue
                words = [{"start": offset + w.start, "end": offset + w.end, "word": w.word}
                         for w in segment.words or [] if w.end > w.start]
                rows = utterances_from_segments([{"words": words}]) if words else []
                if not rows and segment.text.strip():
                    rows = [{"startMs": round((offset + segment.start) * 1000),
                             "endMs": round((offset + segment.end) * 1000), "text": segment.text.strip()}]
                utterances.extend(rows)
        else:
            response = audio_api(clip, model[7:], credentials)
            usage.append(response.get("usage") or {})
            if model == "openai:whisper-1" and response.get("words"):
                words = [{"start": offset + w["start"], "end": offset + w["end"], "word": " " + w["word"]}
                         for w in response["words"] if w["end"] > w["start"]]
                utterances.extend(utterances_from_segments([{"words": words}]))
            elif str(response.get("text", "")).strip():
                utterances.append({**span, "text": response["text"].strip()})
    # Release STT model weights before the later Qwen process needs GPU memory.
    if network is not None:
        network.model.unload_model()
    duration = round(len(pcm) / 16)
    cleaned = []
    for row in utterances:
        row["startMs"] = max(0, min(duration, row["startMs"]))
        row["endMs"] = max(0, min(duration, row["endMs"]))
        if row["endMs"] > row["startMs"]:
            row["id"] = len(cleaned) + 1
            cleaned.append(row)
    return {"utterances": cleaned, "text": "\n".join(u["text"] for u in cleaned), "api_usage": usage, "execution": execution,
            "api_calls": len(usage), "timing": "vad-chunks" if model == "openai:gpt-transcribe" else "model-word-alignment",
            "note": "GPT Transcribe는 VAD 구간 단위 시각을 사용합니다. 단어별 정밀 시각이 아닙니다." if model == "openai:gpt-transcribe" else "원본 오디오 시간 기준"}


def encoder(model, root):
    from ..inference.voice import VoiceEncoder
    if model == "ecapa":
        return VoiceEncoder(root)
    import torch
    from speechbrain.lobes.features import Fbank
    from speechbrain.processing.features import InputNormalization
    from speechbrain.lobes.models.Xvector import Xvector
    result = VoiceEncoder.__new__(VoiceEncoder)
    torch.set_num_threads(4)
    result.torch = torch
    result.features = Fbank(n_mels=24).eval()
    result.normalize = InputNormalization(norm_type="sentence", std_norm=False).eval()
    result.model = Xvector(in_channels=24, activation=torch.nn.LeakyReLU, tdnn_blocks=5,
                           tdnn_channels=[512, 512, 512, 512, 1500], tdnn_kernel_sizes=[5, 3, 3, 1, 1],
                           tdnn_dilations=[1, 2, 3, 1, 1], lin_neurons=512).eval()
    path = prepare("xvector", root) / "embedding_model.ckpt"
    result.model.load_state_dict(torch.load(path, map_location="cpu", weights_only=True))
    return result


def diarize(pcm, model, root, vad_result, credentials, speaker_count=0, report=lambda value: None):
    duration = round(len(pcm) / 16)
    speech = vad_result["spans"]
    if not speech:
        return {"segments": [], "speaker_count": 0, "note": "VAD가 발화를 찾지 못했습니다."}
    if model.startswith("openai:"):
        response = audio_api(pcm, model[7:], credentials, diarized=True)
        segments = []
        for row in response.get("segments", []):
            start, end = max(0, round(float(row["start"]) * 1000)), min(duration, round(float(row["end"]) * 1000))
            if end > start:
                segments.append({"startMs": start, "endMs": end, "speaker": str(row["speaker"]), "text": row.get("text", "")})
        return {"segments": segments, "speaker_count": len({s["speaker"] for s in segments}),
                "api_usage": response.get("usage", {}), "note": "별도 화자 분리 API의 시각·화자 라벨"}
    if model == "nemotron":
        from ..inference.diarization import DiarizationEngine
        activity = DiarizationEngine(root, "cpu").analyze(pcm, progress=report)
        mask = np.zeros(len(activity), dtype=bool)
        for span in speech:
            mask[span["startMs"] // 20:math.ceil(span["endMs"] / 20)] = True
        segments = []
        for speaker in range(activity.shape[1]):
            spans = spans_from_flags((activity[:, speaker] >= .5) & mask, 20, duration, min_ms=200, gap_ms=120, pad_ms=0)
            segments.extend(dict(span, speaker=f"S{speaker + 1}") for span in spans)
        return {"segments": sorted(segments, key=lambda s: (s["startMs"], s["speaker"])),
                "speaker_count": len({s["speaker"] for s in segments}), "note": "0.5 이상 활성 화자 · 겹친 구간은 중복 표시"}
    from scipy.cluster.hierarchy import linkage, fcluster
    from scipy.spatial.distance import pdist
    network = encoder("ecapa" if model == "ecapa-cluster" else "xvector", root)
    chunks, vectors = [], []
    for span in speech:
        report(span["startMs"] / duration)
        for start in range(span["startMs"], span["endMs"], 2500):
            end = min(span["endMs"], start + 2500)
            if end - start < 500:
                if chunks and chunks[-1]["endMs"] == start:
                    chunks[-1]["endMs"] = end
                continue
            chunks.append({"startMs": start, "endMs": end})
            vectors.append(network.encode(pcm[start * 16:end * 16]))
    if not vectors:
        return {"segments": [], "speaker_count": 0, "note": "군집화에 필요한 0.5초 이상 발화가 없습니다."}
    labels = np.ones(len(vectors), dtype=int)
    if len(vectors) > 1:
        tree = linkage(np.clip(pdist(np.stack(vectors), metric="cosine"), 0, 2), method="average")
        labels = fcluster(tree, min(speaker_count, len(vectors)), criterion="maxclust") if speaker_count else fcluster(tree, .35, criterion="distance")
    mapping, segments = {}, []
    for chunk, label in zip(chunks, labels):
        speaker = mapping.setdefault(int(label), f"S{len(mapping) + 1}")
        if segments and segments[-1]["speaker"] == speaker and segments[-1]["endMs"] == chunk["startMs"]:
            segments[-1]["endMs"] = chunk["endMs"]
        else:
            segments.append(dict(chunk, speaker=speaker))
    return {"segments": segments, "speaker_count": len(mapping),
            "note": "2.5초 창 임베딩 + 평균 연결 군집화. 동시 발화를 분리하지 않으며 라벨은 실험 안에서만 유효합니다."}


def voices(pcm, model, root, diarization, vad_result):
    segments = diarization["segments"]
    if not segments:
        return {"signatures": [], "similarities": [], "note": "화자 구간이 없습니다."}
    network = encoder(model, root)
    signatures = []
    for speaker in sorted({s["speaker"] for s in segments}):
        vectors, used, remaining = [], [], 30_000
        for span in segments:
            if remaining <= 0:
                break
            if span["speaker"] != speaker:
                continue
            for speech in vad_result["spans"]:
                if remaining <= 0:
                    break
                start, end = max(span["startMs"], speech["startMs"]) + 100, min(span["endMs"], speech["endMs"]) - 100
                # Exclude overlapping speakers instead of mixing voice identities.
                if any(o["speaker"] != speaker and start < o["endMs"] and o["startMs"] < end for o in segments):
                    continue
                for a in range(start, max(start, end), 6000):
                    b = min(end, a + 6000, a + remaining)
                    if b - a < 1500:
                        break
                    clip = pcm[a * 16:b * 16]
                    if float(np.sqrt(np.mean(clip ** 2))) < .002:
                        continue
                    vector = network.encode(clip)
                    if np.isfinite(vector).all():
                        vectors.append(vector)
                        used.append({"startMs": a, "endMs": b})
                        remaining -= b - a
        if not vectors or sum(s["endMs"] - s["startMs"] for s in used) < 3000:
            continue
        center = np.average(vectors, axis=0, weights=[s["endMs"] - s["startMs"] for s in used])
        center /= max(float(np.linalg.norm(center)), 1e-9)
        signatures.append({"speaker": speaker, "dimensions": len(center), "embedding": center.tolist(),
                           "segments": used, "speechMs": 30_000 - remaining})
    similarities = [{"a": a["speaker"], "b": b["speaker"], "cosine": float(np.dot(a["embedding"], b["embedding"]))}
                    for i, a in enumerate(signatures) for b in signatures[i + 1:]]
    return {"signatures": signatures, "similarities": similarities,
            "note": "이번 실험의 목소리 벡터입니다. 서로 다른 모델의 벡터는 비교하지 않으며 실제 신원을 판정하지 않습니다."}
