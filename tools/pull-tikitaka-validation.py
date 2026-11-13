from pathlib import Path
import subprocess, io, tarfile, json
ROOT=Path(__file__).resolve().parents[1]
ADB=Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'
out=ROOT/'build/reports/tikitaka'
out.mkdir(parents=True,exist_ok=True)
data=subprocess.run([str(ADB),'exec-out','run-as','com.example.hd_project','--user','10','tar','-C','files','-cf','-','tikitaka-validation'],check=True,capture_output=True).stdout
with tarfile.open(fileobj=io.BytesIO(data)) as archive:
    archive.extractall(out,filter='data')
rows=json.loads((out/'tikitaka-validation/layouts.json').read_text(encoding='utf-8'))
print(json.dumps({'screens':len(rows),'text_clips':sum(len(x['textClips']) for x in rows),'export':json.loads((out/'tikitaka-validation/export.json').read_text())}))
