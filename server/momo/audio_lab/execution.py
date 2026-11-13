"""Explicit lab execution policy; unavailable GPUs never silently become CPU jobs."""
import sys

from ..config import settings


def stt_execution(root):
    if settings.lab_device == "cpu":
        return {"device": "cpu", "backend": "CTranslate2", "compute_type": "int8"}
    from ..inference.runtime import prepare_cuda
    library_root = settings.cuda_library_root
    if library_root is None:
        adjacent = root.resolve().parent / "cuda-runtime"
        if adjacent.is_dir():
            library_root = adjacent
    try:
        prepare_cuda(library_root)
        import ctranslate2
        if ctranslate2.get_cuda_device_count() < 1:
            raise ValueError("CUDA GPU를 찾을 수 없습니다.")
        if "float16" not in ctranslate2.get_supported_compute_types("cuda"):
            raise ValueError("이 GPU는 STT의 float16 실행을 지원하지 않습니다.")
    except Exception as exc:
        raise ValueError("STT GPU 준비에 실패했습니다. CUDA/cuDNN 환경을 확인하거나 서버를 -Device cpu로 실행해 주세요.") from exc
    # Older Windows builds abort during model destruction after sampling fallback.
    # This is a native abort, so Python exception handling cannot recover it.
    version = ctranslate2.__version__
    if sys.platform == "win32" and tuple(int(n) for n in version.split(".")[:3]) < (4, 7, 2):
        raise ValueError(f"CTranslate2 {version}에는 Windows GPU 종료 오류가 있습니다. "
                         "server/setup-audio-lab.ps1을 실행해 실험 환경을 업데이트한 뒤 다시 분석해 주세요.")
    return {"device": "cuda", "backend": "CTranslate2", "compute_type": "float16", "runtime_version": version}
