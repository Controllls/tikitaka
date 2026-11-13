"""Archive temporary Figma asset URLs supplied by get_design_context using curl -L."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
archive = ROOT / 'design/figma/2026-11-12'
destination = archive / 'assets'
destination.mkdir(exist_ok=True)
entries = {}
contexts = {}
for path in sorted(archive.glob('*-context.txt')):
    mapping = {}
    for name, url in re.findall(r'const (\w+) = "(https://www\.figma\.com/api/mcp/asset/[^"\s]+)";', path.read_text(encoding='utf-8')):
        filename = url.rsplit('/', 1)[-1]
        entries[url] = filename
        mapping[name] = filename
    contexts[path.stem] = mapping

def fetch(item):
    url, name = item
    path = destination / name
    if not path.exists() or not path.stat().st_size:
        subprocess.run(['curl.exe', '-f', '-sS', '-L', '--retry', '2', '--max-time', '90', url, '-o', str(path)], check=True)
    assert path.stat().st_size > 0
    return dict(file=name, url=url, bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())

with ThreadPoolExecutor(max_workers=6) as executor:
    assets = list(executor.map(fetch, entries.items()))
(archive / 'asset-manifest.json').write_text(json.dumps(dict(fileKey='Y5hqYUqZsj6gQu1QyixBYv',
    captured='2026-11-12', contexts=contexts, assets=assets), ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(contexts=len(contexts), assets=len(assets), bytes=sum(a['bytes'] for a in assets))))
