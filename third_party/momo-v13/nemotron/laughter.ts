/** Recheck non-speech vocalizations without changing any primary speaker identity. */
export function vocalOnly(samples: Float32Array, events: any[]) {
  const masked = new Float32Array(samples.length);
  for (const e of events) if (['speech', 'laughter'].includes(e.kind)) {
    const a = Math.max(0, Math.floor(e.start * 16000)), b = Math.min(samples.length, Math.ceil(e.end * 16000));
    if (b > a) masked.set(samples.subarray(a, b), a);
  }
  return masked;
}

export function recoverLaughter(primary: any, secondary: any, events: any[]) {
  const ids = Object.keys(primary.speakerEnvelopes), candidates = Object.keys(secondary.speakerEnvelopes);
  for (const id of ids) delete primary.speakerEnvelopes[id].laughter;
  const rows: Record<string, Record<string, number>> = {}, mapping: Record<string, any> = {};
  // Anchor IDs using clean ordinary speech, never the laugh being recovered.
  for (const e of events.filter(e => e.kind === 'speech')) for (let i = Math.ceil(e.start / .02); i < Math.floor(e.end / .02); i++) {
    const active = ids.filter(id => primary.speakerEnvelopes[id].activity[i] >= .65);
    if (active.length !== 1) continue;
    for (const c of candidates) if (secondary.speakerEnvelopes[c].activity[i] >= .65) {
      rows[c] ||= {}; rows[c][active[0]] = (rows[c][active[0]] || 0) + .02;
    }
  }
  for (const [id, row] of Object.entries(rows)) {
    const ranked = Object.entries(row).sort((a, b) => b[1] - a[1]), total = ranked.reduce((a, x) => a + x[1], 0);
    if (ranked[0][1] >= .75 && ranked[0][1] / total >= .9 && ranked[0][1] - (ranked[1]?.[1] || 0) >= .5)
      mapping[id] = { speaker: ranked[0][0], agreement: ranked[0][1] / total, seconds: ranked[0][1] };
  }
  const counts: Record<string, number> = {};
  for (const m of Object.values(mapping)) counts[m.speaker] = (counts[m.speaker] || 0) + 1;
  for (const id of Object.keys(mapping)) if (counts[mapping[id].speaker] > 1) delete mapping[id];
  const hints: any[] = [];
  for (const e of events.filter(e => e.kind === 'laughter')) {
    const a = Math.floor(e.start / .02), b = Math.ceil(e.end / .02), length = Math.max(1, b - a);
    // A mixed laughing/speaking interval cannot identify the laugher from common audio alone.
    let overlap = 0; for (let i = a; i < b; i++) if (ids.filter(id => primary.speakerEnvelopes[id].activity[i] >= .5).length > 1) overlap++;
    if (overlap / length > .2) continue;
    const evidence = candidates.map(id => {
      const p = secondary.speakerEnvelopes[id].activity.slice(a, b);
      return { id, mean: p.reduce((s: number, v: number) => s + v, 0) / length, coverage: p.filter((v: number) => v >= .6).length / length };
    }).sort((a, b) => b.mean - a.mean);
    const best = evidence[0], mapped = best && mapping[best.id];
    if (!mapped || best.mean < .6 || best.coverage < .6 || best.coverage * length * .02 < .12 || (evidence[1]?.mean || 0) > .25) continue;
    hints.push({ start: e.start, end: e.end, speaker: Number(mapped.speaker), assignment: 'acoustic-recheck', coverage: best.coverage, agreement: mapped.agreement, referenceSeconds: mapped.seconds });
    const target = primary.speakerEnvelopes[mapped.speaker]; target.laughter ||= Array(target.activity.length).fill(0);
    // Evidence is for the sound event as a whole. Keep the expression through its brief pauses;
    // physical mouth/body amplitude still follows the original audio energy, not a constant.
    for (let i = a; i < Math.min(b, target.laughter.length); i++) target.laughter[i] = Math.max(target.laughter[i], best.mean);
  }
  primary.laughterSpeakers = hints;
  primary.diagnostics.laughter = { method: 'vocal-only-recheck-aligned-to-clean-primary-speech', checkedEvents: events.filter(e => e.kind === 'laughter').length, recoveredEvents: hints.length, anchors: mapping };
  return primary;
}
