"""Exercise actual media trimming, subtitle offsets and audio-only/cover-art inputs."""
import hashlib
import json
import subprocess

import pytest

from momo.audio_lab.worker import export_clips
from momo.config import settings


def ffmpeg(*args):
    subprocess.run([settings.ffmpeg, "-nostdin", "-v", "error", "-y", *map(str, args)],
                   check=True, timeout=30, capture_output=True)


def probe(path):
    result = subprocess.run([settings.ffprobe, "-v", "error", "-show_entries",
                             "stream=codec_name,codec_type,width,height:format=duration", "-of", "json", str(path)],
                            check=True, capture_output=True, timeout=15)
    return json.loads(result.stdout)


@pytest.mark.parametrize("kind", ["video", "audio", "cover"])
def test_original_clips_keep_source_and_trim_media_and_captions(tmp_path, kind):
    source = tmp_path / ("한글 원본.mp4" if kind == "video" else "한글 원본.m4a")
    args = ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=5"]
    if kind == "video":
        args += ["-f", "lavfi", "-i", "color=c=blue:s=320x180:r=25:d=5", "-c:v", "libx264",
                 "-pix_fmt", "yuv420p", "-g", "125"]
    elif kind == "cover":
        from PIL import Image
        cover = tmp_path / "cover.png"
        Image.new("RGB", (80, 80), "red").save(cover)
        args += ["-i", cover, "-map", "0:a", "-map", "1:v", "-c:v", "png", "-disposition:v", "attached_pic"]
    ffmpeg(*args, "-c:a", "aac", source)
    before = hashlib.sha256(source.read_bytes()).digest()
    # Both boundaries lie inside the GOP; stream-copy would include excess footage.
    clips = [{"startMs": 1250, "endMs": 3250, "durationMs": 2000}]
    utterances = [{"startMs": 500, "endMs": 1500, "text": "앞 문장"},
                  {"startMs": 3000, "endMs": 4500, "text": "뒤 문장"},
                  {"startMs": 0, "endMs": 500, "text": "제외"}]
    export_clips(source, tmp_path, clips, utterances)
    clip = clips[0]
    assert hashlib.sha256(source.read_bytes()).digest() == before
    audio = probe(tmp_path / clip["audio_file"])
    assert float(audio["format"]["duration"]) == pytest.approx(2, abs=.1)
    assert [s["codec_type"] for s in audio["streams"]] == ["audio"]
    srt = (tmp_path / clip["subtitle_file"]).read_text(encoding="utf-8")
    assert "00:00:00,000 --> 00:00:00,250\n앞 문장" in srt
    assert "00:00:01,750 --> 00:00:02,000\n뒤 문장" in srt
    assert "제외" not in srt
    if kind == "video":
        video = probe(tmp_path / clip["source_video_file"])
        assert float(video["format"]["duration"]) == pytest.approx(2, abs=.1)
        assert {s["codec_name"] for s in video["streams"]} == {"h264", "aac"}
        frame = next(s for s in video["streams"] if s["codec_type"] == "video")
        assert (frame["width"], frame["height"]) == (320, 180)
    else:
        assert "source_video_file" not in clip
        assert not (tmp_path / "clip_1.mp4").exists()
