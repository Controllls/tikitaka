"""Local-only experiment with the unchanged bundled TFLite; never uploads input audio."""
from pathlib import Path
import json, subprocess, sys, time
import numpy as np
from scipy.signal import lfilter

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/reports/laughter-methods';OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(ROOT/'server'))
from momo.inference.acoustic import laughter_decisions
import tensorflow as tf

interpreter=tf.lite.Interpreter(model_path=str(ROOT/'app/src/main/assets/yamnet.tflite'),num_threads=2)
interpreter.allocate_tensors()
inp=interpreter.get_input_details()[0];out=interpreter.get_output_details()[0]
labels=(ROOT/'app/src/main/assets/yamnet_labels.txt').read_text().splitlines()

def decode(path,rate=16000):
    return np.frombuffer(subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-ac','1','-ar',str(rate),'-f','f32le','-']),dtype='<f4')

def band(pcm):return lfilter([.70],[1,-.30],lfilter([.91,-.91],[1,-.91],pcm.astype(np.float64))).astype(np.float32)

def infer(pcm):
    interpreter.set_tensor(inp['index'],pcm.astype(np.float32));interpreter.invoke()
    s=interpreter.get_tensor(out['index']).reshape(-1)
    return dict(laughter=float(max(s[13:19])),otherVocal=float(max(s[19:42])),nonVocal=float(max(s[42:])),
                speech=float(max(s[:13])),music=float(s[labels.index('Music')]),cough=float(max(s[42:46])),
                family=float(sum(s[13:19])),support=int((s[13:19]>=.01).sum()),
                top=[(labels[i],float(s[i])) for i in np.argsort(s)[-3:][::-1]])

def run(name,pcm):
    waves={'raw':pcm,'band':band(pcm)};frames=[];started=time.monotonic()
    for offset in range(0,len(pcm),2560):
        row={'startMs':offset/16,'rms':float(np.sqrt(np.mean(pcm[offset:offset+15600].astype(float)**2)))}
        for kind,wave in waves.items():
            x=np.pad(wave[offset:offset+15600],(0,max(0,15600-len(wave[offset:offset+15600]))))
            row[kind]=infer(x)
            rms=np.sqrt(np.mean(x.astype(float)**2));peak=max(abs(x),default=0)
            gain=min(8.,.08/max(rms,1e-8),.95/max(peak,1e-8)) if rms>.0006 else 1.
            row[kind+'Gain']=float(max(1.,gain))
            row[kind+'Normalized']=infer(x*max(1.,gain))
        frames.append(row)
    old=[{**f['raw'],'band':f['band']} for f in frames]
    old_values,_=laughter_decisions(old)
    normalized=[{**f['rawNormalized'],'band':f['bandNormalized']} for f in frames]
    norm_values,_=laughter_decisions(normalized)
    summary={'name':name,'duration':len(pcm)/16000,'oldAcceptedFrames':int((old_values>0).sum()),'normalizedAcceptedFrames':int((norm_values>0).sum()),
             'peaks':{key:max(f[key]['laughter'] for f in frames) for key in ['raw','band','rawNormalized','bandNormalized']},'elapsed':time.monotonic()-started}
    (OUT/(name+'.json')).write_text(json.dumps({'summary':summary,'frames':frames},ensure_ascii=False),encoding='utf-8')
    print(json.dumps(summary),flush=True)

if __name__=='__main__':
    paths=[('user20',ROOT/'build/reports/playback-diagnostics/source.m4a'),('clearLaugh',ROOT/'app/src/androidTest/assets/1-73123-A-26.m4a'),
           ('groupLaugh',ROOT/'app/src/androidTest/assets/laughing.m4a'),('speech',ROOT/'app/src/androidTest/assets/momo-synthetic-korean.m4a')]
    for name,path in paths:run(name,decode(path))
    laugh=decode(paths[1][1]);run('quietLaugh',laugh*.05)
    run('silence',np.zeros(16000*5,dtype=np.float32))
    run('noise',np.random.default_rng(14).normal(0,.015,16000*5).astype(np.float32))
    for row in json.loads((OUT/'controls/attribution.json').read_text()):run(row['category'],decode(OUT/'controls'/row['filename']))
