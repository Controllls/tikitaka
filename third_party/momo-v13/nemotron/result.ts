/** Preserve all independent speaker lanes; passenger count never changes inference. */
export function formatResult(probabilities: Float32Array, duration: number, requestedCount = 0, backend = 'wasm', seconds = 0) {
  const countFrames = Math.min(Math.floor(probabilities.length / 8), Math.floor(duration * 100));
  const frames = Math.ceil(countFrames / 2), segments: any[] = [], tracks: Record<string, any> = {};
  for (let speaker = 0; speaker < 8; speaker++) {
    const activity: number[] = []; let start: number | null = null;
    const spans: any[] = [];
    for (let i = 0; i < frames; i++) {
      const a = probabilities[i * 16 + speaker], b = i * 2 + 1 < countFrames ? probabilities[i * 16 + 8 + speaker] : a;
      if (!Number.isFinite(a) || !Number.isFinite(b)) throw Error('분석 결과에 올바르지 않은 값이 있습니다. 다시 분석해 주세요.');
      const p = (a + b) / 2; activity.push(+p.toFixed(5));
      if (p > .5 && start === null) start = i * .02;
      if (start !== null && (p <= .5 || i === frames - 1)) {
        const end = Math.min(duration, (p > .5 ? i + 1 : i) * .02);
        spans.push({ start: +start.toFixed(4), end: +end.toFixed(4), speaker }); start = null;
      }
    }
    if (spans.length) { segments.push(...spans); tracks[speaker] = { step: .02, offset: 0, activity, source: 'activity-weighted-mixture' }; }
  }
  segments.sort((a, b) => a.start - b.start || a.speaker - b.speaker);
  const detectedCount = Object.keys(tracks).length, countConflict = !!requestedCount && detectedCount !== requestedCount;
  const warnings = ['화자별 활동을 추적한 결과입니다. 겹친 음성의 개별 파형이나 개별 자막을 복원한 결과는 아닙니다.'];
  if (countConflict) warnings.push(`알려주신 ${requestedCount}명과 분석된 ${detectedCount}개 목소리가 다릅니다. 연결을 확인해 주세요.`);
  if (detectedCount > 4) warnings.push('목소리가 4개보다 많이 감지됐습니다. 캐릭터 연결과 짧은 잡음 구간을 확인해 주세요.');
  return { analysisRevision: 'web-laughter-v6', segments, speakerEnvelopes: tracks, laughterSpeakers: [],
    engine: `NVIDIA Nemotron 3 / browser ${backend} FP32 / web-laughter-v6`,
    diagnostics: { version: 6, detectedCount, requestedCount, countConflict, warnings,
      modelRevision: '353b6f8ad2cac3580e982d7fbdf0a010786b0406', backend, precision: 'FP32', runtime: 'onnxruntime-web-1.24.3', seconds,
      identity: { method: 'whole-recording-arrival-order-cache', frameSeconds: .01, motionFrameSeconds: .02, uncertain: [], uncertainSeconds: 0 } } };
}
