"""Inspect encoded HD_project outputs independently of Android's renderer/test assertions."""
from pathlib import Path
import json
import os
import subprocess
import numpy as np
from scipy.signal import correlate

ROOT=Path(__file__).resolve().parents[1]
adb=Path(os.environ["LOCALAPPDATA"])/"Android/Sdk/platform-tools/adb.exe"
user=subprocess.check_output([str(adb),"shell","am","get-current-user"]).decode().strip()
assert user.isdigit()
output=ROOT/"captures/momo"
output.mkdir(parents=True,exist_ok=True)
report=[]
def pcm(path):
    return np.frombuffer(subprocess.check_output(["ffmpeg","-v","error","-i",str(path),"-vn","-ac","1","-ar","16000","-f","f32le","pipe:1"]),dtype="<f4")

for label in ("3","30","server"):
    name=f"momo-validation-{label}.mp4"
    data=subprocess.check_output([str(adb),"exec-out","run-as","com.example.hd_project","--user",user,"cat",f"files/{name}"])
    assert data[4:8]==b"ftyp"
    file=output/name
    file.write_bytes(data)
    details=json.loads(subprocess.check_output(["ffprobe","-v","error","-show_streams","-show_format","-of","json",str(file)]))
    video=next(s for s in details["streams"] if s["codec_type"]=="video")
    audio=next(s for s in details["streams"] if s["codec_type"]=="audio")
    assert (video["codec_name"],video["width"],video["height"],video["r_frame_rate"])==("h264",540,960,"30/1")
    assert audio["codec_name"]=="aac"
    duration=float(details["format"]["duration"])
    expected=float(label) if label!="server" else float(subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration","-of","default=nw=1:nk=1",str(ROOT/"app/src/androidTest/assets/momo-synthetic-korean.m4a")]))
    assert abs(duration-expected)<=.1
    row=dict(file=name,width=540,height=960,fps=30,video="H.264",audio="AAC",duration_seconds=duration,
        input_kind="Synthetic Korean speech + actual server inference" if label=="server" else "Synthetic contract fixture")
    original=pcm(ROOT/"app/src/androidTest/assets"/("momo-synthetic-korean.m4a" if label=="server" else f"sample-{label}.wav"))
    encoded=pcm(file)
    correlation=correlate(encoded,original,mode="full",method="fft")
    center=len(original)-1
    lag=int(np.argmax(correlation[center-1600:center+1601]))-1600
    left=original[max(0,-lag):]
    right=encoded[max(0,lag):]
    count=min(len(left),len(right))
    similarity=float(np.dot(left[:count],right[:count])/(np.linalg.norm(left[:count])*np.linalg.norm(right[:count])))
    assert abs(lag)<=800 and similarity>.95, (lag,similarity)
    row.update(audio_offset_ms=round(lag/16,3),audio_correlation=round(similarity,6))
    if label!="server":
        timing=json.loads(subprocess.check_output([str(adb),"exec-out","run-as","com.example.hd_project","--user",user,"cat",f"files/momo-validation-{label}.json"]))
        row.update(timing)
    report.append(row)
    subprocess.run(["ffmpeg","-v","error","-y","-ss","1","-i",str(file),"-frames:v","1",str(output/f"frame-{label}.png")],check=True)
(ROOT/"docs/momo-video-validation.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
print(json.dumps(report,indent=2))
