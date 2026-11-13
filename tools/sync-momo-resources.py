"""Bundle supplied PNG/MP4 resources and a fixed preset; no recording or credential is packaged."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--input", type=Path, default=Path(r"D:\MOMO_Development\video-input"))
args = parser.parse_args()
source = args.input.resolve()
output = ROOT/"app/src/main/assets/momo"
output.mkdir(parents=True, exist_ok=True)
files=[]
for path in sorted((source/"resources").rglob("*")):
    if not path.is_file() or path.suffix.lower() not in {".png", ".json", ".mp4"}:
        continue
    relative=path.relative_to(source).as_posix()
    destination=output/relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, destination)
    files.append(dict(path=relative,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=path.stat().st_size))
preset=source/"settings/fixed-scene.json"
if preset.is_file():
    document=json.loads(preset.read_text(encoding="utf-8-sig"))
    (output/"preset.json").write_text(json.dumps(document,ensure_ascii=False,indent=2),encoding="utf-8")
elif not (output/"preset.json").exists():
    raise SystemExit("Add app/src/main/assets/momo/preset.json or settings/fixed-scene.json first")
data=(output/"preset.json").read_bytes()
files.append(dict(path="preset.json",sha256=hashlib.sha256(data).hexdigest(),bytes=len(data)))
index=json.dumps(files,ensure_ascii=False,indent=2)
(output/"index.json").write_text(index,encoding="utf-8")
print(f"Bundled {len(files)} resource files; recordings/settings credentials were not copied.")
