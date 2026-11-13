"""Short, timed head turns toward the addressee, independent of mouth motion."""
from bisect import bisect_left, bisect_right


class Gaze:
    def __init__(self, people, seats, events):
        self.people = {p["speaker"]: p for p in people}
        self.events = {speaker: [] for speaker in self.people}
        for event in events:
            speaker, target = event.get("speaker"), event.get("addressee")
            if speaker == target or speaker not in self.people or target not in self.people:
                continue
            if event.get("intent") not in {"question", "address"} or event["endMs"] <= event["startMs"]:
                continue
            for who, other in ((speaker, target), (target, speaker)):
                x = seats[self.people[who]["seat"]]
                other_x = seats[self.people[other]["seat"]]
                if abs(x - other_x) < 1:
                    continue
                # Face the other person near the end of a question and briefly
                # through the reply; return to the configured resting direction.
                end = event["endMs"] + 1000
                start = max(0, event["startMs"] - 120, end - 4000)
                self.events[who].append(dict(start=start, end=end, pose="left" if other_x < x else "right",
                                             addressee=other, intent=event["intent"]))
        for rows in self.events.values():
            rows.sort(key=lambda r: r["start"])
        self.starts = {speaker: [r["start"] for r in rows] for speaker, rows in self.events.items()}

    def at(self, speaker, now_us):
        now = now_us / 1000
        rows = self.events.get(speaker, [])
        starts = self.starts.get(speaker, [])
        active = [r for r in rows[bisect_left(starts, now - 4000):bisect_right(starts, now)] if now < r["end"]]
        if not active:
            return {}
        event = active[-1]
        edge = max(0, min(1, (now - event["start"]) / 140, (event["end"] - now) / 160))
        return dict(pose=event["pose"], pose_mix=edge * edge * (3 - 2 * edge),
                    looking_at=event["addressee"], dialogue_intent=event["intent"])
