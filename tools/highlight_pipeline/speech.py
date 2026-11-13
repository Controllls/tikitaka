from __future__ import annotations

import gc
import importlib.metadata
from pathlib import Path
import re

from .common import cached, log, save_cache, signature, timestamp
from .models import MODEL_CACHE, prepare_cuda


def utterances_from_segments(segments: list[dict]) -> list[dict]:
    """Turn word alignment into prompt-sized utterances; these are NOT clip boundaries."""
    words = []
    for segment in segments:
        for word in segment.get("words") or []:
            start, end = round(word["start"] * 1000), round(word["end"] * 1000)
            if word["word"].strip() and 0 <= start <= end:
                words.append({"startMs": start, "endMs": end, "text": word["word"]})
    groups, current = [], []
    for word in words:
        if current and (word["startMs"] - current[-1]["endMs"] > 1500 or
            sum(len(w["text"]) for w in current) + len(word["text"]) > 160):
            groups.append(current)
            current = []
        current.append(word)
        if re.search(r"[?!。]$|(?<!\.)\.(?!\.)$", word["text"].strip()):
            groups.append(current)
            current = []
    if current:
        groups.append(current)
    return [{"id": index, "startMs": group[0]["startMs"], "endMs": max(w["endMs"] for w in group),
        "text": "".join(w["text"] for w in group).strip(), "words": group}
        for index, group in enumerate(groups, 1)]


def transcribe(source: Path, source_hash: str, duration_ms: int, output: Path, *,
    model_name="large-v3-turbo", language="ko", device="auto", cache_dir=MODEL_CACHE) -> dict:
    fingerprint = signature({"source": source_hash, "model": model_name, "language": language,
        "word_timestamps": True, "vad_ms": 500, "beam_size": 5, "utterances": 1,
        "faster_whisper": importlib.metadata.version("faster-whisper")})
    cache = output / "transcription.json"
    if (result := cached(cache, fingerprint)) is not None:
        return result
    prepare_cuda()
    import ctranslate2
    from faster_whisper import BatchedInferencePipeline, WhisperModel
    if device == "auto":
        device = "cuda" if ctranslate2.get_cuda_device_count() else "cpu"
    compute = "int8_float16" if device == "cuda" else "int8"
    log(f"한국어 STT: {model_name} · {device}")
    model = WhisperModel(model_name, device=device, compute_type=compute,
        download_root=str(cache_dir / "stt-models"))
    pipeline = BatchedInferencePipeline(model=model)
    generated, info = pipeline.transcribe(str(source), language=language, batch_size=4 if device == "cuda" else 1,
        beam_size=5, word_timestamps=True, temperature=0, vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500})
    segments = []
    previous = -10
    for segment in generated:
        words = [{"start": word.start, "end": word.end, "word": word.word, "probability": word.probability}
            for word in segment.words or []]
        segments.append({"index": len(segments) + 1, "start": segment.start, "end": segment.end,
            "text": segment.text, "words": words, "avg_logprob": segment.avg_logprob,
            "no_speech_prob": segment.no_speech_prob})
        percent = min(100, round(segment.end * 100000 / duration_ms))
        if percent >= previous + 10:
            previous = percent
            log(f"STT {percent}% · {len(segments)}개 전사 구간")
    result = {"model": model_name, "language": info.language, "device": device,
        "segments": segments, "utterances": utterances_from_segments(segments)}
    save_cache(cache, fingerprint, result)
    del generated, pipeline, model
    gc.collect()
    return result


def save_transcripts(speech: dict, output: Path) -> None:
    text, srt = [], []
    for utterance in speech["utterances"]:
        start, end = utterance["startMs"], utterance["endMs"]
        text.append(f"[{utterance['id']:04d}] {timestamp(start)} --> {timestamp(end)}  {utterance['text']}")
        srt.append(f"{utterance['id']}\n{timestamp(start, ',')} --> {timestamp(end, ',')}\n{utterance['text']}\n")
    (output / "transcript.txt").write_text("\n".join(text) + "\n", encoding="utf-8")
    (output / "transcript.srt").write_text("\n".join(srt), encoding="utf-8")
