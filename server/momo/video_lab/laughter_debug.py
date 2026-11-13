"""Time-synchronised, burned-in diagnostics for model scores and rendered motion."""
from bisect import bisect_right
from collections import defaultdict

import numpy as np
from PIL import Image, ImageDraw

from ..inference.acoustic import laughter_decisions

REASONS = {
    "score": "원점수 부족", "other_voice": "다른 발성 점수가 우세", "non_voice": "환경음 점수가 우세",
    "duration": "지속 시간·피크 조건 미달", "accepted": "웃음 규칙 통과", "band": "음성 대역 재검사 통과",
    "quiet": "음량 기준 미달", "missing": "원점수 기록 없음", "no_segment": "현재 분석 구간 없음",
    "interpolation": "판정 점수가 기준 미만", "unassigned": "웃음 검출 / 화자 미확정",
    "unmapped": "웃음 검출 / 캐릭터 미연결", "failed": "구간 분석 실패",
    "pose": "옆모습의 정지 표정 사용",
    "fallback_original": "원본 웃음 → 화자 추정 배정", "fallback_segment": "분리 웃음 → 화자 추정 배정",
}


class Trace:
    def __init__(self, data):
        self.data = data
        frames = data.get("frames", [])
        self.times = np.arange(len(frames))*data["hopMs"] + data["centerMs"] + data["offsetMs"]
        self.raw = [f["laughter"] for f in frames]
        self.other = [f["otherVocal"] for f in frames]
        self.noise = [f["nonVocal"] for f in frames]
        self.values, self.reasons = laughter_decisions(frames, threshold_scale=data["sensitivity"]/.1)
        self.spans = data.get("audible_spans", [])
        self.starts = [p[0] for p in self.spans]

    def at(self, now_ms):
        if not len(self.times):
            return dict(raw=None, filtered=None, reason="missing", other=None, noise=None)
        index = int(np.clip(np.searchsorted(self.times, now_ms - self.data["hopMs"]/2), 0, len(self.times)-1))
        i = bisect_right(self.starts, now_ms)-1
        audible = i >= 0 and now_ms < self.spans[i][1]
        return dict(raw=float(np.interp(now_ms, self.times, self.raw)),
            filtered=float(np.interp(now_ms, self.times, self.values)) if audible else 0.,
            other=float(np.interp(now_ms, self.times, self.other)), noise=float(np.interp(now_ms, self.times, self.noise)),
            reason=self.reasons[index] if audible else "quiet")


class Diagnostics:
    def __init__(self, tracks, motion, people):
        self.motion = motion
        self.people = people
        self.legacy = not isinstance(tracks, dict)
        self.groups, self.original, self.starts = [], [], []
        self.trace = {}
        self.source_events = []
        if self.legacy:
            self.tracks = tracks
            return
        groups = defaultdict(list)
        for lane in tracks.get("lanes", []):
            groups[lane["group_id"]].append(lane)
            if lane.get("laughter_debug"):
                self.trace[lane["id"]] = Trace(lane["laughter_debug"])
        self.groups = sorted(groups.values(), key=lambda rows: rows[0]["startMs"])
        self.starts = [rows[0]["startMs"] for rows in self.groups]
        self.original = [Trace(row) for row in tracks.get("original_laughter_debug", [])]
        self.original_starts = [row.data["startMs"] for row in self.original]
        self.source_events = tracks.get("unassigned_laughter_events", [])
        from ..audio_lab.laughter_assignment import EventTimeline
        self.fallback = EventTimeline(tracks.get("fallback_laughter_events", []))

    def at(self, now_us, rendered=None):
        now = now_us/1000
        rows = []
        if self.legacy:
            for person in self.people:
                state = self.motion.at(person["person_id"], now_us) if self.motion else {}
                rows.append(dict(label=person["speaker"], raw=None, filtered=None, cutoff=None, other=None, noise=None,
                    detected=bool(state.get("laughing")), laughing=bool(state.get("laughing")), mouth=state.get("mouth", 0),
                    reason="missing", active=False))
            return dict(original=None, rows=rows, unassigned=False, sensitivity=None)
        i = bisect_right(self.starts, now)-1
        current = self.groups[i] if i >= 0 and now < self.groups[i][0]["endMs"] else []
        people = {p["speaker"]: p for p in self.people}
        used = set()
        for lane in current:
            speaker = lane.get("speaker")
            if speaker in people:
                used.add(speaker)
            frame = min(len(lane.get("laughter", []))-1, int((now-lane["startMs"])//20))
            cutoff = lane.get("laughter_threshold", .05)
            sample = self.trace[lane["id"]].at(now) if lane["id"] in self.trace else dict(
                raw=None, filtered=None, reason="missing", other=None, noise=None)
            # The stored score is exactly what CharacterMotion consumes. A backfilled
            # raw trace is diagnostic evidence and must not replace the saved decision.
            filtered = lane["laughter"][frame] if frame >= 0 else None
            detected = filtered is not None and filtered >= cutoff
            state = self.motion.at(people[speaker]["person_id"], now_us) if speaker in people and self.motion else {}
            drawing = (rendered or {}).get(speaker)
            expression = drawing["laughing"] if drawing else bool(state.get("laughing"))
            reason = "failed" if lane.get("state") != "complete" else "unassigned" if detected and not speaker else (
                "unmapped" if detected and speaker not in people else sample["reason"])
            if not detected and reason in {"accepted", "band"}:
                reason = "interpolation"
            if detected and state.get("laughing") and drawing and not expression:
                reason = "pose"
            label = speaker or "화자 미확정"
            if lane.get("assignment_source", "").endswith("provisional"):
                label += " (추정)"
            rows.append(dict(sample, label=label, filtered=filtered, cutoff=cutoff, detected=detected,
                laughing=expression, mouth=state.get("mouth", 0), reason=reason,
                active=True, segment_id=lane["id"]))
        for speaker in people.keys()-used:
            rows.append(dict(label=speaker, raw=None, filtered=None, cutoff=None, other=None, noise=None,
                detected=False, laughing=False, mouth=0., reason="no_segment", active=False))
        assigned = self.fallback.at(now)
        for speaker in dict.fromkeys(e["speaker"] for e in assigned):
            if speaker not in people:
                continue
            events = [e for e in assigned if e["speaker"] == speaker]
            # Do not present a guessed identity as a measured lane score. Show the
            # detected event's peak explicitly, separate from the original trace.
            segment_ids = {e.get("segment_id") for e in events if e.get("segment_id")}
            rows = [r for r in rows if r["label"].split(" ")[0] != speaker and r.get("segment_id") not in segment_ids]
            state = self.motion.at(people[speaker]["person_id"], now_us) if self.motion else {}
            drawing = (rendered or {}).get(speaker)
            expression = drawing["laughing"] if drawing else bool(state.get("laughing"))
            original_event = any(e.get("origin") == "original" for e in events)
            rows.append(dict(label=f"{speaker} (웃음 추정)", raw=None, filtered=None, cutoff=None, other=None, noise=None,
                detected=True, laughing=expression, mouth=state.get("mouth", 0.), active=True, fallback=True,
                event_peak=max(e["maxScore"] for e in events),
                reason="pose" if drawing and state.get("laughing") and not expression else
                "fallback_original" if original_event else "fallback_segment"))
        index = bisect_right(self.original_starts, now)-1
        trace = self.original[index] if index >= 0 and now < self.original[index].data["endMs"] else None
        original = None
        if trace:
            cutoff = trace.data["sensitivity"]*.5
            sample = trace.at(now)
            original = dict(sample, cutoff=cutoff, detected=sample["filtered"] is not None and sample["filtered"] >= cutoff)
        return dict(original=original, rows=rows,
            unassigned=any(e["startMs"] <= now < e["endMs"] for e in self.source_events),
            assigned_speakers=list(dict.fromkeys(e["speaker"] for e in assigned)),
            sensitivity=trace.data["sensitivity"] if trace else None)


def number(value):
    return "—" if value is None else f"{value:.2e}" if 0 < value < .00001 else f"{value:.5f}" if 0 < value < .001 else f"{value:.4f}"


def draw_overlay(image, diagnostics, now_us, fonts, rendered=None):
    snapshot = diagnostics.at(now_us, rendered)
    rows = sorted(snapshot["rows"], key=lambda r: (not r.get("fallback"), not r["active"]))[:3]
    positions = {person["speaker"]: i for i, person in enumerate(diagnostics.people)}
    rows.sort(key=lambda r: positions.get(r["label"].split(" ")[0], len(positions)))
    height = 82 + 50*(1+len(rows))
    overlay = Image.new("RGBA", image.size)
    draw = ImageDraw.Draw(overlay)
    draw.rounded_rectangle((10, 10, 530, height), radius=12, fill=(18, 27, 33, 255))
    white, muted, green, amber = (242, 247, 250), (177, 190, 199), (121, 236, 167), (255, 202, 116)
    sensitivity = number(snapshot["sensitivity"])
    draw.text((22, 19), f"웃음 진단  {now_us/1e6:.2f}s  · 감도 {sensitivity}", font=fonts[16], fill=white)
    draw.text((22, 42), "원점수 → 판정 점수 / 실제 기준  · 점수는 확률이 아닙니다", font=fonts[12], fill=muted)
    source = snapshot["original"]
    original = dict(label="원본", raw=None, filtered=None, cutoff=None, reason="missing", detected=False, other=None, noise=None)
    if source:
        original.update(source)
    for i, row in enumerate([original, *rows]):
        y = 63 + i*50
        color = green if row["detected"] else amber if row["raw"] is not None else muted
        state = ("화자 미확정 웃음 있음" if snapshot["unassigned"] else "원본 소리") if i == 0 else (
            f"검출 {'ON' if row['detected'] else 'OFF'} · 표정 {'ON' if row['laughing'] else 'OFF'} · 입 신호 {row['mouth']:.2f}")
        if i == 0 and snapshot.get("assigned_speakers"):
            state = "웃음 추정 배정 → " + ", ".join(snapshot["assigned_speakers"])
        elif row.get("fallback"):
            state = f"배정 ON · 표정 {'ON' if row['laughing'] else 'OFF'} · 입 신호 {row['mouth']:.2f}"
        draw.text((22, y), f"{row['label']}  {state}", font=fonts[13], fill=color)
        values = f"{number(row['raw'])} → {number(row['filtered'])} / {number(row['cutoff'])}"
        if row.get("fallback"):
            values = f"웃음 구간 피크 {number(row['event_peak'])}"
        draw.text((22, y+19), values, font=fonts[13], fill=white)
        reason = REASONS.get(row["reason"], row["reason"])
        if row["reason"] == "other_voice" and row["other"] is not None:
            reason = f"다른 발성 {number(row['other'])} 우세"
        elif row["reason"] == "non_voice" and row["noise"] is not None:
            reason = f"환경음 {number(row['noise'])} 우세"
        draw.text((290, y+20), reason, font=fonts[12], fill=muted)
        # Show score relative to the chosen cutoff, not a fake probability percent.
        draw.rectangle((22, y+39, 270, y+42), fill=(61, 72, 80))
        if row["filtered"] is not None and row["cutoff"]:
            width = min(248, max(0, row["filtered"]/(row["cutoff"]*2)*248))
            draw.rectangle((22, y+39, 22+width, y+42), fill=color)
        draw.line((146, y+37, 146, y+44), fill=white)
    draw.text((22, height-19), "막대 중앙 = 판정 기준 · 웃음 추정 배정은 실제 화자 확인과 다름", font=fonts[12], fill=muted)
    image.alpha_composite(overlay)
