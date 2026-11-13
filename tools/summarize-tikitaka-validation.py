from pathlib import Path
import json, re, subprocess, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'build/reports/tikitaka'
artifacts=out/'tikitaka-validation'
adb=Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'
rows=[ET.parse(path).getroot().attrib for path in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
unit={key:sum(int(row.get(key,0)) for row in rows) for key in ['tests','failures','errors','skipped']}
log_bytes=(out/'instrumentation.txt').read_bytes()
log=log_bytes.decode('utf-16' if log_bytes.startswith((b'\xff\xfe',b'\xfe\xff')) else 'utf-8-sig')
codes=[int(code) for code in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)',log,re.M)]
assert 'OK (11 tests)' in log and codes.count(0)==10 and codes.count(-4)==1
assert -2 not in codes and -1 not in codes
layouts=json.loads((artifacts/'layouts.json').read_text(encoding='utf-8'))
lint=(ROOT/'app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt').read_text(encoding='utf-8')
lint_counts=re.search(r'(\d+) errors?, (\d+) warnings?',lint)
launch=subprocess.run([str(adb),'exec-out','run-as','com.example.hd_project','--user','10','cat','files/pleos-home-launch-ms.txt'],check=True,capture_output=True).stdout.decode().strip()
data={
    'date':'2026-11-12','build':'passed','unitTests':unit,
    'instrumentation':{'passed':codes.count(0),'skipped':codes.count(-4),'failed':0},
    'lint':{'errors':int(lint_counts[1]),'warnings':int(lint_counts[2])},
    'staticLayouts':{'presets':12,'screens':len(layouts),'textOrBoundsFailures':sum(len(row['textClips']) for row in layouts),'density':1.0,'actualVirtualDisplayTest':False},
    'virtualDisplay':json.loads((artifacts/'virtual-display.json').read_text(encoding='utf-8')),
    'syntheticVideo':json.loads((artifacts/'export.json').read_text(encoding='utf-8')),
    'pipeline':{'fixtureOnly':True,'completed':json.loads((artifacts/'pipeline.json').read_text(encoding='utf-8'))['state']=='completed','uniqueWorkCount':1},
    'activityScenarioHomeStartMs':int(launch),
    'apkBytes':(ROOT/'app/build/outputs/apk/debug/app-debug.apk').stat().st_size,
    'unverified':['vehicle ignition OFF','phone pairing and sharing','all official FormFactorConsole presets/DPI','real audio end-to-end AI quality','full driving/audio focus policy combinations']
}
assert data['lint']['errors']==0 and data['staticLayouts']['textOrBoundsFailures']==0
(out/'validation-summary.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(data))
