"""Package original Figma artwork and fonts. SVGs are rasterized, never redrawn."""
from pathlib import Path
import sys, json, hashlib, shutil, subprocess
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'build/reports/technology-inventory/deps'))
from playwright.sync_api import sync_playwright
archive = ROOT/'design/figma/2026-11-12'
target = ROOT/'app/src/main/assets/tiki'
target.mkdir(parents=True, exist_ok=True)
manifest = json.loads((archive/'asset-manifest.json').read_text(encoding='utf-8'))
mapping = {}
with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    page = browser.new_page(device_scale_factor=2)
    for item in manifest['assets']:
        source = archive/'assets'/item['file']
        digest = item['sha256'][:24]
        name = digest+'.png' if source.suffix=='.svg' else digest+source.suffix
        out = target/name
        mapping[item['file']] = name
        if out.exists(): continue
        if source.suffix=='.svg':
            page.goto(source.as_uri())
            element = page.locator('svg')
            box = element.bounding_box()
            if not box or box['width']<1 or box['height']<1: raise ValueError(source)
            element.screenshot(path=str(out), omit_background=True)
        else: shutil.copyfile(source,out)
    browser.close()
contexts = {name:{key:mapping[value] for key,value in values.items()} for name,values in manifest['contexts'].items()}
(target/'images.json').write_text(json.dumps(contexts,ensure_ascii=False),encoding='utf-8')
fontdir=target/'fonts';fontdir.mkdir(exist_ok=True)
for folder,font in [('astasans','AstaSans[wght].ttf'),('jua','Jua-Regular.ttf')]:
    for remote,local in [(font,font),('OFL.txt',folder+'-OFL.txt')]:
        dest=fontdir/local
        if not dest.exists():
            url='https://raw.githubusercontent.com/google/fonts/main/ofl/'+folder+'/'+remote.replace('[','%5B').replace(']','%5D')
            subprocess.run(['curl.exe','-f','-sS','-L',url,'-o',str(dest)],check=True)
print(json.dumps({'images':len(set(mapping.values())),'contexts':len(contexts),'bytes':sum(x.stat().st_size for x in target.rglob('*') if x.is_file())}))
