"""Offline metrics for consented, manually annotated audio. No audio upload or model call."""
import argparse
import json
import math
from pathlib import Path
import unicodedata
import numpy as np
from scipy.optimize import linear_sum_assignment


def cer(reference, hypothesis):
    def normalize(text):
        return "".join(unicodedata.normalize("NFC", text).split())
    a, b = normalize(reference), normalize(hypothesis)
    previous = list(range(len(b)+1))
    for i, left in enumerate(a, 1):
        current = [i]
        for j, right in enumerate(b, 1):
            current.append(min(current[-1]+1, previous[j]+1, previous[j-1]+(left != right)))
        previous = current
    return {"reference_characters": len(a), "edits": previous[-1], "cer": previous[-1]/len(a) if a else None}


def score(reference, result, collar_us=250000, include_overlap=True):
    duration = reference["duration_us"]
    if result["duration_us"] != duration:
        raise ValueError("Reference/prediction duration must use the same decoded timeline")
    centers = np.minimum(np.arange(math.ceil(duration/20000))*20000+10000, duration-1)
    ref_ids = sorted({sid for seg in reference["segments"] for sid in seg["speaker_ids"]})
    hyp_ids = [s["speaker_id"] for s in result["speakers"]]
    ref = np.zeros((len(centers),len(ref_ids)),bool)
    hyp = np.zeros((len(centers),len(hyp_ids)),bool)
    keep = np.ones(len(centers),bool)
    for segment in reference["segments"]:
        rows = (centers >= segment["start_us"]) & (centers < segment["end_us"])
        for sid in segment["speaker_ids"]:
            ref[rows,ref_ids.index(sid)] = True
        for edge in (segment["start_us"],segment["end_us"]):
            keep &= np.abs(centers-edge) >= collar_us
    for event in result["events"]:
        rows = (centers >= event["start_us"]) & (centers < event["end_us"])
        for sid in event["active_speaker_ids"]:
            hyp[rows,hyp_ids.index(sid)] = True
    if not include_overlap:
        keep &= ref.sum(axis=1) <= 1
    agreement = ref[keep].astype(np.int64).T @ hyp[keep].astype(np.int64)
    ri, hi = linear_sum_assignment(-agreement)
    mapping = {hyp_ids[h]:ref_ids[r] for r,h in zip(ri,hi)}
    nr, nh = ref[keep].sum(axis=1), hyp[keep].sum(axis=1)
    correct = sum(int(np.count_nonzero(ref[keep,r] & hyp[keep,h])) for r,h in zip(ri,hi))
    missed = int(np.maximum(nr-nh,0).sum())
    false_alarm = int(np.maximum(nh-nr,0).sum())
    confusion = int(np.minimum(nr,nh).sum())-correct
    denominator = int(nr.sum())

    expected = reference.get("laughter",[])
    predicted = [event for event in result["events"] if event["kind"] == "laughter"]
    overlap = np.zeros((len(expected),len(predicted)))
    for i, a in enumerate(expected):
        for j, b in enumerate(predicted):
            intersection = max(0,min(a["end_us"],b["end_us"])-max(a["start_us"],b["start_us"]))
            union = max(a["end_us"],b["end_us"])-min(a["start_us"],b["start_us"])
            overlap[i,j] = intersection/union
    rows,cols = linear_sum_assignment(-((overlap >= .5)*1000+overlap))
    matches = [(i,j) for i,j in zip(rows,cols) if overlap[i,j] >= .5]
    wrong, unknown = 0, 0
    for i,j in matches:
        guessed = predicted[j]["laughing_speaker_ids"]
        unknown += not bool(guessed)
        wrong += bool(guessed) and {mapping.get(s,s) for s in guessed} != set(expected[i]["speaker_ids"])
    review_us = sum(e["end_us"]-e["start_us"] for e in result["events"] if e["requires_review"])
    return dict(grid_us=20000,collar_us=collar_us,include_overlap=include_overlap,speaker_mapping=mapping,
        diarization=dict(der=(missed+false_alarm+confusion)/denominator if denominator else None,
            missed_speaker_seconds=missed*.02,false_alarm_speaker_seconds=false_alarm*.02,
            confusion_speaker_seconds=confusion*.02,reference_speaker_seconds=denominator*.02),
        laughter=dict(iou_threshold=.5,reference_events=len(expected),predicted_events=len(predicted),matched_events=len(matches),
            precision=len(matches)/len(predicted) if predicted else None,recall=len(matches)/len(expected) if expected else None,
            matched_wrong_speaker_events=int(wrong),matched_unknown_speaker_events=int(unknown)),
        transcription=cer(reference["transcript"],"".join(c["text"] for c in result["captions"])),
        review_duration_ratio=review_us/duration)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("reference",type=Path)
    parser.add_argument("result",type=Path)
    parser.add_argument("--collar-us",type=int,default=250000)
    parser.add_argument("--exclude-overlap",action="store_true")
    parser.add_argument("--output",type=Path,required=True)
    args = parser.parse_args()
    metrics = score(json.loads(args.reference.read_text(encoding="utf-8")),
        json.loads(args.result.read_text(encoding="utf-8")),args.collar_us,not args.exclude_overlap)
    args.output.write_text(json.dumps(metrics,ensure_ascii=False,indent=2),encoding="utf-8")
    print(f"Metrics saved to {args.output}; transcript/audio not printed.")
