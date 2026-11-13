"""Port of vocalOnly/recoverLaughter: anchors come from clean ordinary speech only."""
import numpy as np


def vocal_only(pcm, kinds):
    masked = pcm.copy()
    for i, kind in enumerate(kinds):
        if kind not in {"speech", "laughter"}:
            masked[i*320:(i+1)*320] = 0
    return masked


def recover(primary, secondary, kinds):
    laughter = np.zeros_like(primary)
    anchors = np.zeros((8, 8))
    for i, kind in enumerate(kinds):
        if kind != "speech":
            continue
        active = np.flatnonzero(primary[i] >= .65)
        if len(active) == 1:
            for candidate in np.flatnonzero(secondary[i] >= .65):
                anchors[candidate, active[0]] += .02
    mapping = {}
    for candidate, row in enumerate(anchors):
        ranked = np.argsort(-row, kind="stable")
        first, runner = row[ranked[0]], row[ranked[1]]
        if first >= .75 and first/max(1e-12, sum(row)) >= .9 and first-runner >= .5:
            mapping[candidate] = int(ranked[0])
    counts = {v: list(mapping.values()).count(v) for v in mapping.values()}
    mapping = {k: v for k, v in mapping.items() if counts[v] == 1}
    start = 0
    while start < len(kinds):
        if kinds[start] != "laughter":
            start += 1
            continue
        end = start+1
        while end < len(kinds) and kinds[end] == "laughter":
            end += 1
        if np.mean((primary[start:end] >= .5).sum(axis=1) > 1) <= .2:
            means = secondary[start:end].mean(axis=0)
            ranked = np.argsort(-means, kind="stable")
            best = int(ranked[0])
            coverage = np.mean(secondary[start:end, best] >= .6)
            if best in mapping and means[best] >= .6 and coverage >= .6 and coverage*(end-start)*.02 >= .12 and means[ranked[1]] <= .25:
                laughter[start:end, mapping[best]] = means[best]
        start = end
    return laughter
