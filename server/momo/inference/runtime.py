import os
import site
from pathlib import Path
from ..config import settings

_dll_handles = []
_dll_paths = set()


def prepare_cuda(library_root=None):
    """Only process-local DLL search paths; does not change system CUDA installations."""
    if os.name == "nt":
        extra = library_root or settings.cuda_library_root
        roots = site.getsitepackages() + ([str(extra)] if extra else [])
        for root in roots:
            for folder in (Path(root) / "nvidia").glob("*/bin"):
                if str(folder) in _dll_paths:
                    continue
                _dll_handles.append(os.add_dll_directory(str(folder)))
                _dll_paths.add(str(folder))
                os.environ["PATH"] = str(folder) + os.pathsep + os.environ["PATH"]
    import onnxruntime as ort
    ort.preload_dlls()
