from ..config import settings

# Designer packages remain separate from the original recording upload limit.
MAX_ASSET_BYTES = 80_000_000
MAX_VIDEO_SECONDS = 600  # App result/track schema and character renderer contract.
DISK_RESERVE = 512_000_000


def upload_limit_message():
    return f"원본 파일은 {settings.lab_max_audio_bytes / 1_000_000_000:g} GB 이하로 선택해 주세요."


def duration_limit_message():
    return f"원본 파일은 {settings.lab_max_seconds / 3600:g}시간 이하로 선택해 주세요."
