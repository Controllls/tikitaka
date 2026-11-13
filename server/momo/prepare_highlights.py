"""Stage existing, pinned PC highlight models into the independently deployable server model root."""
import argparse
import json
from pathlib import Path
import shutil
from .config import settings
from .highlights.common import sha256
from .highlights.models import TOPIC_REVISION


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[2]
    parser.add_argument("--source-cache", type=Path, default=root / "build/tools")
    parser.add_argument("--assets", type=Path, default=root / "app/src/main/assets")
    parser.add_argument("--destination", type=Path, default=settings.model_root / "highlights")
    args = parser.parse_args()
    target = args.destination.resolve()
    target.mkdir(parents=True, exist_ok=True)
    models = list(args.source_cache.glob(f"topic-models/**/snapshots/{TOPIC_REVISION}/Qwen3.5-4B-Q4_K_M.gguf"))
    if len(models) != 1:
        raise RuntimeError("Prepare the pinned Qwen3.5-4B model in --source-cache first")
    runtime = args.source_cache / "llama-b12110"
    binary = next(runtime.rglob("llama-server.exe"))
    shutil.copytree(binary.parent, target / "runtime", dirs_exist_ok=True)
    files = {"yamnet.tflite": args.assets / "yamnet.tflite", "yamnet_labels.txt": args.assets / "yamnet_labels.txt",
             "topic.gguf": models[0]}
    for name, source in files.items():
        shutil.copyfile(source, target / name)
    lock = {"topic_revision": TOPIC_REVISION, "runtime_revision": "b12110",
            "files": {name: sha256(target / name) for name in files}}
    (target / "model-lock.json").write_text(json.dumps(lock, indent=2), encoding="utf-8")
    print("Highlight models prepared in", target)


if __name__ == "__main__":
    main()
