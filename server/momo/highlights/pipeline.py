import json
import subprocess
import numpy as np
from ..config import settings
from ..inference.audio_io import decode
from .common import sha256, timestamp
from .contracts import HighlightClip, HighlightResult
from .models import MODEL_CACHE, ensure_models
from .selection import group_events, utterances_from_segments
from .topics import infer_topics


def laughter_windows(pcm, check, progress):
    import tensorflow as tf
    interpreter = tf.lite.Interpreter(model_path=str(MODEL_CACHE / "yamnet.tflite"), num_threads=2)
    interpreter.allocate_tensors()
    input_info, output_info = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    labels = (MODEL_CACHE / "yamnet_labels.txt").read_text(encoding="utf-8").splitlines()
    names = {"Laughter", "Baby laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle"}
    indices = [i for i, name in enumerate(labels) if name in names]
    if len(indices) != 6 or tuple(input_info["shape"]) != (15600,):
        raise ValueError("Invalid YAMNet model")
    music = labels.index("Music")
    windows = []
    for index, offset in enumerate(range(0, max(1, len(pcm)-15600+1), 7800)):
        if index % 20 == 0:
            check()
            progress("highlight_laughter", .05 + .25 * offset / len(pcm))
        frame = np.zeros(15600, np.float32)
        count = min(15600, len(pcm)-offset)
        frame[:count] = pcm[offset:offset+count]
        interpreter.set_tensor(input_info["index"], frame)
        interpreter.invoke()
        scores = interpreter.get_tensor(output_info["index"]).reshape(-1)
        windows.append(dict(startMs=offset//16, endMs=(offset+count)//16,
                            laughter=float(max(scores[indices])), music=float(scores[music])))
    return windows


def run(job, source, temp, engines, progress, check):
    progress("decoding", .02)
    pcm, duration_us, _ = decode(source, temp / "audio.f32", settings.highlight_max_seconds)
    duration_ms = duration_us // 1000
    topics, utterances = [], []
    origin = "fixture" if settings.profile == "fixture" else "inference"
    if origin == "fixture":
        if duration_ms >= 1000:
            topics = [dict(title="테스트 하이라이트", startMs=0, endMs=min(2500, duration_ms), maxLaughter=.8)]
    else:
        ensure_models()
        windows = laughter_windows(pcm, check, progress)
        events = group_events(windows, .1)
        progress("highlight_transcription", .32)
        # Transcribe the entire recording for topic context, independently of animation speech masks.
        segments, _ = engines.asr.model.transcribe(pcm, language="ko", beam_size=5, temperature=0,
            word_timestamps=True, vad_filter=True, vad_parameters={"min_silence_duration_ms": 500})
        speech = []
        for segment in segments:
            check()
            speech.append(dict(words=[dict(start=w.start, end=w.end, word=w.word) for w in segment.words or []]))
            progress("highlight_transcription", min(.69, .32 + .37*segment.end*1000/duration_ms))
        utterances = utterances_from_segments(speech)
        progress("highlight_topics", .72)
        topics = infer_topics(utterances, events, job.audio_sha256, duration_ms, temp,
                              max_clips=3, cache_dir=MODEL_CACHE, device=settings.highlight_topic_device)
    check()
    progress("highlight_export", .9)
    transcript = temp / "transcript.json"
    transcript.write_text(json.dumps({"utterances": utterances}, ensure_ascii=False), encoding="utf-8")
    files = [("transcript.json", transcript)]
    clips = []
    for number, topic in enumerate(topics, 1):
        check()
        start, end = topic["startMs"], topic["endMs"]
        audio = temp / f"clip_{number}.m4a"
        subprocess.run([settings.ffmpeg, "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe",
            "-ss", str(start / 1000), "-i", str(source), "-t", str((end-start)/1000),
            "-map", "0:a:0", "-vn", "-map_metadata", "-1", "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart", str(audio)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            timeout=300, check=True)
        captions = []
        for item in utterances:
            a, b = max(start, item["startMs"]), min(end, item["endMs"])
            if b > a:
                captions.append(f"{len(captions)+1}\n{timestamp(a-start, ',')} --> {timestamp(b-start, ',')}\n{item['text']}\n")
        subtitle = temp / f"clip_{number}.srt"
        subtitle.write_text("\n".join(captions), encoding="utf-8")
        files.extend(((audio.name, audio), (subtitle.name, subtitle)))
        clips.append(HighlightClip(number=number, title=topic["title"], start_ms=start, end_ms=end,
            score=topic["maxLaughter"], sha256=sha256(audio), size=audio.stat().st_size))
    return HighlightResult(job_id=job.id, audio_sha256=job.audio_sha256, duration_ms=duration_ms,
                           data_origin=origin, clips=clips), files
