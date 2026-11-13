from __future__ import annotations

from pathlib import Path
import shutil

from PIL import Image, ImageDraw, ImageFont

from .common import log, probe, run, timestamp, write_json

WIDTH, HEIGHT, FPS = 1280, 720, 30


def find_font(custom: str | None = None) -> Path:
    candidates = [Path(custom)] if custom else [Path("C:/Windows/Fonts/malgun.ttf"),
        Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
        Path("/System/Library/Fonts/AppleSDGothicNeo.ttc")]
    for path in candidates:
        if path.is_file():
            return path.resolve()
    raise ValueError("한글 폰트를 찾을 수 없습니다. --font로 TTF/TTC 파일을 지정하세요.")


def encoder_available(encoder: str) -> str:
    if encoder != "auto":
        return encoder
    try:
        # NVENC rejects widths below its minimum even when the encoder is available.
        run(["ffmpeg", "-hide_banner", "-v", "error", "-f", "lavfi", "-i", "color=s=320x180",
            "-frames:v", "1", "-c:v", "h264_nvenc", "-f", "null", "-"])
        return "h264_nvenc"
    except RuntimeError:
        return "libx264"


def make_cues(topic: dict, utterances: list[dict], font) -> list[dict]:
    words = [word for utterance in utterances for word in utterance["words"]
        if word["startMs"] >= topic["startMs"] and word["endMs"] <= topic["endMs"]]

    def wrap(items):
        lines = [""]
        for item in items:
            word = item["text"].strip()
            candidate = (lines[-1] + " " + word).strip()
            if font.getlength(candidate) > 1010 and lines[-1]:
                lines.append(word)
            else:
                lines[-1] = candidate
        return lines

    groups, current = [], []
    for word in words:
        if current and (word["startMs"] - current[-1]["endMs"] > 1200 or
            word["endMs"] - current[0]["startMs"] > 6500 or len(wrap(current + [word])) > 2):
            groups.append(current)
            current = []
        current.append(word)
    if current:
        groups.append(current)
    result = []
    for index, group in enumerate(groups):
        start = group[0]["startMs"] - topic["startMs"]
        end = group[-1]["endMs"] - topic["startMs"]
        next_start = groups[index + 1][0]["startMs"] - topic["startMs"] if index + 1 < len(groups) else topic["durationMs"]
        end = min(next_start, max(end, start + 1100), topic["durationMs"])
        if start < end:
            result.append({"startMs": start, "endMs": end, "lines": wrap(group)})
    return result


def export_audio(source: Path, topics: list[dict], utterances: list[dict], output: Path, font: Path) -> None:
    folder = output / "clips"
    folder.mkdir(exist_ok=True)
    measure_font = ImageFont.truetype(str(font), 40)
    for index, topic in enumerate(topics, 1):
        target = folder / f"clip_{index:02d}.m4a"
        run(["ffmpeg", "-hide_banner", "-v", "error", "-nostdin", "-y", "-ss", str(topic["startMs"] / 1000),
            "-i", str(source), "-t", str(topic["durationMs"] / 1000), "-map", "0:a:0", "-vn", "-c:a", "aac",
            "-b:a", "128k", "-ar", "48000", "-ac", "1", "-map_metadata", "-1", "-metadata", "title=" + topic["title"], str(target)])
        info = probe(target)
        if abs(float(info["format"]["duration"]) * 1000 - topic["durationMs"]) > 150:
            raise RuntimeError("내보낸 오디오의 길이가 선택한 주제 구간과 다릅니다.")
        topic["audioFile"] = str(target.relative_to(output))
        cues = make_cues(topic, utterances, measure_font)
        srt = [f"{i}\n{timestamp(c['startMs'], ',')} --> {timestamp(c['endMs'], ',')}\n" + "\n".join(c["lines"]) + "\n"
            for i, c in enumerate(cues, 1)]
        target.with_suffix(".srt").write_text("\n".join(srt), encoding="utf-8")
        target.with_suffix(".txt").write_text("\n".join(" ".join(c["lines"]) for c in cues), encoding="utf-8")
        log(f"클립 {index}: {timestamp(topic['startMs'])}–{timestamp(topic['endMs'])}")


def short_time(ms: int) -> str:
    seconds = max(0, round(ms / 1000))
    return f"{seconds // 60:02d}:{seconds % 60:02d}"


def background(topic: dict, index: int, total: int, folder: Path, font_path: Path) -> None:
    import numpy as np
    yy, xx = np.mgrid[0:720, 0:1280]
    glow = np.exp(-((xx - 1110) / 520) ** 2 - ((yy - 160) / 490) ** 2)
    pixels = np.stack([base + glow * gain for base, gain in zip([11, 16, 24], [15, 7, 2])], axis=-1).astype("uint8")
    image = Image.fromarray(pixels).resize((2560, 1440), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(image)

    def rect(box, fill, radius=0, outline=None):
        box = tuple(round(x * 2) for x in box)
        draw.rounded_rectangle(box, radius=radius * 2, fill=fill, outline=outline, width=2)

    def text(x, y, value, size, color):
        draw.text((x * 2, y * 2), value, font=ImageFont.truetype(str(font_path), size * 2), fill=color)

    for i, height in enumerate([10, 21, 29, 17]):
        rect((74 + i * 6, 62 - height / 2, 77 + i * 6, 62 + height / 2), "#FFAD58", 1)
    text(113, 43, "이동 기록", 23, "#F4F5F7")
    text(232, 49, "대화 하이라이트", 17, "#A0ADBE")
    rect((1093, 42, 1208, 82), "#2C221D", 20, "#62462F")
    text(1118, 49, f"{index:02d} / {total:02d}", 19, "#FFAD58")
    rect((72, 102, 1208, 103), "#293442")
    text(74, 126, f"이야기 {index:02d}", 19, "#FFAD58")
    size = 44
    while size > 20 and ImageFont.truetype(str(font_path), size).getlength(topic["title"]) > 1120:
        size -= 1
    text(70, 163, topic["title"], size, "#F4F5F7")
    text(74, 225, f"원본 {short_time(topic['startMs'])}–{short_time(topic['endMs'])}   ·   {short_time(topic['durationMs'])}", 19, "#A0ADBE")
    rect((72, 281, 1208, 431), "#141D29", 22, "#293442")
    text(96, 300, "대화의 순간", 17, "#A0ADBE")
    rect((100, 371, 1180, 372), "#293442")
    rect((72, 457, 1208, 622), "#192431", 22, "#293442")
    text(96, 474, "대화 자막", 16, "#758396")
    rect((88, 654, 1192, 660), "#293442", 3)
    for event in topic["laughterEvents"]:
        x = 88 + 1104 * ((event["startMs"] + event["endMs"]) / 2 - topic["startMs"]) / topic["durationMs"]
        rect((x - 3, 649, x + 3, 665), "#FFAD58", 3)
    text(554, 681, "자동 전사 자막", 14, "#758396")
    text(1100, 679, "웃음 후보", 15, "#A0ADBE")
    rect((1082, 687, 1088, 693), "#FFAD58", 3)
    image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).save(folder / f"background_{index:02d}.png")


def write_ass(topic: dict, index: int, utterances: list[dict], folder: Path, font_path: Path) -> None:
    font = ImageFont.truetype(str(font_path), 40)
    family = font.getname()[0].replace(",", " ")
    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 720
ScaledBorderAndShadow: yes
WrapStyle: 2
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Caption,{family},40,&H00F7F5F4,&H00F7F5F4,&H00291D14,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,96,96,0,1
Style: Timer,{family},16,&H00BEADA0,&H00BEADA0,&H00291D14,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
Style: Accent,{family},18,&H0058ADFF,&H0058ADFF,&H00291D14,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,9,0,0,0,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    lines = []

    def ass_time(ms):
        centiseconds = max(0, round(ms / 10))
        seconds, fraction = divmod(centiseconds, 100)
        minutes, seconds = divmod(seconds, 60)
        hours, minutes = divmod(minutes, 60)
        return f"{hours}:{minutes:02d}:{seconds:02d}.{fraction:02d}"

    def event(layer, start, end, style, text):
        if start < end:
            lines.append(f"Dialogue: {layer},{ass_time(start)},{ass_time(end)},{style},,0,0,0,,{text}")

    duration = topic["durationMs"]
    event(0, 0, duration, "Accent", r"{\an7\pos(0,0)\p1\c&H58ADFF&\clip(88,654,88,660)" +
        f"\\t(0,{duration},\\clip(88,654,1192,660))" + "}m 88 654 l 1192 654 1192 660 88 660")
    for ms in range(0, duration, 1000):
        event(1, ms, min(ms + 1000, duration), "Timer", r"{\an7\pos(88,679)}" + f"{short_time(ms)} / {short_time(duration)}")
    for cue in make_cues(topic, utterances, font):
        clean = [line.replace("\\", "＼").replace("{", "(").replace("}", ")") for line in cue["lines"]]
        event(2, cue["startMs"], cue["endMs"], "Caption", r"{\pos(640,553)\fad(80,80)}" + r"\N".join(clean))
    for candidate in topic["laughterEvents"]:
        start = candidate["startMs"] - topic["startMs"]
        end = min(duration, max(candidate["endMs"] - topic["startMs"], start + 1500))
        event(2, start, end, "Accent", r"{\pos(1180,302)\fad(100,150)}웃음 후보")
    (folder / f"captions_{index:02d}.ass").write_text(header + "\n".join(lines) + "\n", encoding="utf-8-sig")


def render_videos(topics: list[dict], utterances: list[dict], output: Path, font: Path,
    encoder="auto", verify=True) -> dict:
    folder = output / "videos"
    folder.mkdir(exist_ok=True)
    (folder / "fonts").mkdir(exist_ok=True)
    shutil.copy2(font, folder / "fonts" / font.name)
    encoder = encoder_available(encoder)
    log(f"MP4 인코더: {encoder}")
    parts = []
    current_ms = 0
    for index, topic in enumerate(topics, 1):
        background(topic, index, len(topics), folder, font)
        write_ass(topic, index, utterances, folder, font)
        audio = output / topic["audioFile"]
        name = f"clip_{index:02d}.mp4"
        filters = (f"[1:a]volume=4,showwaves=s=1080x76:mode=cline:rate=30:colors=0xFFAD58:scale=cbrt:draw=full,format=rgba[wave];"
            f"[0:v][wave]overlay=100:334:shortest=1,ass=filename=captions_{index:02d}.ass:fontsdir=fonts,format=yuv420p[v]")
        codec = ["-c:v", encoder, "-preset", "p5", "-cq", "23", "-b:v", "0"] if encoder == "h264_nvenc" else ["-c:v", "libx264", "-preset", "fast", "-crf", "22"]
        log(f"영상 생성 {index}/{len(topics)} · {short_time(topic['durationMs'])}")
        run(["ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "warning", "-filter_complex_threads", "2",
            "-loop", "1", "-framerate", str(FPS), "-i", f"background_{index:02d}.png", "-i", str(audio.resolve()),
            "-filter_complex", filters, "-map", "[v]", "-map", "1:a:0", *codec, "-pix_fmt", "yuv420p",
            "-c:a", "copy", "-t", str(topic["durationMs"] / 1000), "-movflags", "+faststart", name],
            cwd=folder, logfile=folder / f"render_{index:02d}.log")
        info = probe(folder / name)
        length = round(float(info["format"]["duration"]) * 1000)
        parts.append({"file": name, "title": topic["title"], "startMs": current_ms, "endMs": current_ms + length})
        current_ms += length
        topic["videoFile"] = str((folder / name).relative_to(output))
    (folder / "concat.txt").write_text("\n".join(f"file '{p['file']}'" for p in parts), encoding="utf-8")
    metadata = ";FFMETADATA1\ntitle=대화 하이라이트\n"
    for part in parts:
        title = part["title"].replace("\\", "\\\\").replace("\n", " ").replace("=", "\\=").replace(";", "\\;").replace("#", "\\#")
        metadata += f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={part['startMs']}\nEND={part['endMs']}\ntitle={title}\n"
    (folder / "chapters.ffmeta").write_text(metadata, encoding="utf-8")
    target = folder / "highlights.mp4"
    # Re-encode only the joined audio to absorb AAC priming/rounding at chapter boundaries.
    run(["ffmpeg", "-hide_banner", "-nostdin", "-y", "-v", "warning", "-f", "concat", "-safe", "0",
        "-i", "concat.txt", "-f", "ffmetadata", "-i", "chapters.ffmeta", "-map", "0:v:0", "-map", "0:a:0",
        "-map_metadata", "1", "-map_chapters", "1", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
        "-af", "aresample=async=1:first_pts=0", "-movflags", "+faststart", target.name], cwd=folder,
        logfile=folder / "concat.log")
    info = probe(target)
    video = next(s for s in info["streams"] if s["codec_type"] == "video")
    audio = next(s for s in info["streams"] if s["codec_type"] == "audio")
    if video["codec_name"] != "h264" or audio["codec_name"] != "aac" or len(info["chapters"]) != len(parts):
        raise RuntimeError("최종 MP4 코덱 또는 챕터 검증 실패")
    if abs(float(video["duration"]) - float(audio["duration"])) > 0.15:
        raise RuntimeError("최종 MP4의 음성과 영상 길이가 일치하지 않습니다.")
    if verify:
        run(["ffmpeg", "-hide_banner", "-v", "error", "-xerror", "-i", str(target),
            "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-"], logfile=folder / "decode-check.log")
    result = {"file": str(target.relative_to(output)), "durationSeconds": float(info["format"]["duration"]),
        "width": video["width"], "height": video["height"], "fps": FPS, "encoder": encoder,
        "chapters": parts, "decodeVerified": verify}
    write_json(output / "video_result.json", result)
    return result
