import json
import subprocess
from pathlib import Path
import numpy as np
from ..config import settings
from ..errors import ServiceError


def decode(source: Path, output: Path, max_seconds=600):
    if source.stat().st_size > settings.max_audio_bytes:
        raise ServiceError("AUDIO_TOO_LARGE", "80MB 이하 파일만 분석할 수 있습니다.", 413)
    try:
        probe = subprocess.run([settings.ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
                                "-select_streams", "a:0", "-show_entries", "stream=codec_type,codec_name,duration:format=duration",
                                "-of", "json", str(source)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30, check=True)
        if len(probe.stdout) > 256_000:
            raise ValueError("Oversized metadata")
        info = json.loads(probe.stdout)
        audio = next(s for s in info["streams"] if s["codec_type"] == "audio")
        if audio["codec_name"] not in {"mp3", "aac", "alac", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_u8", "pcm_s16be"}:
            raise ServiceError("UNSUPPORTED_MEDIA_TYPE", "MP3/WAV/M4A 오디오를 선택해 주세요.", 415)
        duration = float(info.get("format", {}).get("duration", audio.get("duration", 0)))
        if duration > max_seconds + .1:
            raise ServiceError("AUDIO_TOO_LONG", f"{max_seconds // 60}분 이하 파일을 선택해 주세요.")
        subprocess.run([settings.ffmpeg, "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe",
                        "-i", str(source), "-map", "0:a:0", "-vn", "-t", str(max_seconds + .1), "-ac", "1", "-ar", "16000",
                        "-f", "f32le", str(output)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300, check=True)
        pcm = np.fromfile(output, dtype="<f4")
        if len(pcm) > max_seconds * 16000:
            raise ServiceError("AUDIO_TOO_LONG", f"{max_seconds // 60}분 이하 파일을 선택해 주세요.")
        if len(pcm) < 1600 or not np.isfinite(pcm).all():
            raise ValueError("Invalid PCM")
        mime = "audio/mpeg" if audio["codec_name"] == "mp3" else "audio/mp4" if audio["codec_name"] in {"aac", "alac"} else "audio/wav"
        return pcm, round(len(pcm) / 16000 * 1_000_000), mime
    except ServiceError:
        raise
    except (ValueError, KeyError, StopIteration, OSError, subprocess.SubprocessError):
        raise ServiceError("INVALID_AUDIO", "오디오를 읽을 수 없습니다. 파일 형식과 길이를 확인해 주세요.")
