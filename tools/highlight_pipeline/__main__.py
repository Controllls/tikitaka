from __future__ import annotations

import argparse
import os
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description="YAMNet → 한국어 STT → 주제 경계 모델 → M4A/MP4")
    parser.add_argument("--input", required=True, help="MP3/M4A/WAV/MP4 등 FFmpeg가 읽을 수 있는 입력")
    parser.add_argument("--output", help="새 출력 폴더. 같은 설정의 폴더를 지정하면 분석 결과를 재사용")
    parser.add_argument("--threshold", type=float, default=0.1, help="웃음 기준 (기본 0.1)")
    parser.add_argument("--max-clips", type=int, default=3)
    parser.add_argument("--stt-model", default="large-v3-turbo")
    parser.add_argument("--language", default="ko")
    parser.add_argument("--device", choices=["auto", "cuda", "cpu"], default="auto")
    parser.add_argument("--encoder", choices=["auto", "h264_nvenc", "libx264"], default="auto")
    parser.add_argument("--font", help="한글 자막용 TTF/TTC 파일")
    parser.add_argument("--model-cache", help="모델 캐시 폴더")
    parser.add_argument("--no-verify", action="store_true", help="최종 영상 전체 디코딩 검증만 생략")
    args = parser.parse_args()
    if not 0 < args.threshold <= 1:
        parser.error("--threshold는 0 초과 1 이하여야 합니다.")
    if not 1 <= args.max_clips <= 20:
        parser.error("--max-clips는 1~20이어야 합니다.")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
    os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
    from .pipeline import execute
    try:
        execute(args)
        return 0
    except KeyboardInterrupt:
        print("중단했습니다. 같은 출력 폴더와 설정으로 다시 실행하면 완료된 분석을 재사용합니다.", file=sys.stderr)
        return 130
    except Exception as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
