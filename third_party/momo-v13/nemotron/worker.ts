import * as ort from 'onnxruntime-web';
import { StreamingDiarizer } from './diarizer';
import { formatResult } from './result';
import { vocalOnly, recoverLaughter } from './laughter';

const BASE = 'https://huggingface.co/onnx-community/Nemotron-3-Diarization-ONNX/resolve/353b6f8ad2cac3580e982d7fbdf0a010786b0406/';
const CACHE = 'momo-nemotron-fp32-353b6f8a';
const ASSETS = {
  graph: { file: 'onnx/model.onnx', bytes: 303466, sha: '12a7c98fc1ee6ec98ea728c7ffd9fcf6b245f3c9c1ea9f7c2a7cc47c023e3f9d' },
  weights: { file: 'onnx/model.onnx_data', bytes: 398184448, sha: 'c293d9b5930eb9f6172f095ced052c0d1bbdbeb2594a115497583ca209b1dbd6' }
};
const report = (message: string) => self.postMessage({ type: 'progress', message });
ort.env.wasm.wasmPaths = new URL('./vendor/nemotron/', import.meta.url).href;
ort.env.wasm.numThreads = 1;
ort.env.logLevel = 'error';

async function load(asset: typeof ASSETS.graph) {
  const url = BASE + asset.file;
  const cache = await caches.open(CACHE).catch(() => null);
  for (let attempt = 0; attempt < 2; attempt++) {
    const cached = attempt === 0 ? await cache?.match(url).catch(() => undefined) : undefined;
    const controller = new AbortController(); let timeout: ReturnType<typeof setTimeout>;
    const arm = () => { clearTimeout(timeout); timeout = setTimeout(() => controller.abort(), 90000); }; arm();
    try {
      const response = cached || await fetch(url, { signal: controller.signal, credentials: 'omit' });
      if (!response.ok || !response.body) throw Error(`모델 다운로드에 실패했습니다 (${response.status}). 인터넷 연결을 확인한 뒤 다시 시도해 주세요.`);
      const reader = response.body.getReader(), bytes = new Uint8Array(asset.bytes); let offset = 0, last = 0;
      for (;;) {
        const { done, value } = await reader.read(); if (done) break; arm();
        if (offset + value.length > bytes.length) throw Error('모델 파일의 크기가 다릅니다.');
        bytes.set(value, offset); offset += value.length;
        if (performance.now() - last > 250) { report(`${cached ? '저장된' : '전문'} 모델 ${cached ? '읽는' : '내려받는'} 중 · ${Math.round(offset / 1e6)} / ${Math.ceil(asset.bytes / 1e6)} MB`); last = performance.now(); }
      }
      clearTimeout(timeout!);
      report('모델 파일을 검증하고 있습니다…');
      const sha = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))).map(v => v.toString(16).padStart(2, '0')).join('');
      if (offset !== asset.bytes || sha !== asset.sha) {
        await cache?.delete(url); if (cached && !attempt) continue;
        throw Error('모델 파일 검증에 실패했습니다. 인터넷 연결을 확인하고 다시 시도해 주세요.');
      }
      if (!cached && cache) {
        try { await cache.put(url, new Response(bytes, { headers: { 'Content-Type': 'application/octet-stream' } })); }
        catch { report('저장 공간이 부족해 모델을 이번에만 사용합니다. 다음 분석에서는 다시 내려받을 수 있어요.'); }
      }
      return bytes;
    } catch (e: any) {
      if (e.name === 'AbortError') throw Error('모델 다운로드가 90초 동안 진행되지 않았습니다. 연결을 확인하고 다시 시도해 주세요.');
      throw e;
    } finally { clearTimeout(timeout!); }
  }
  throw Error('모델을 읽지 못했습니다. 다시 시도해 주세요.');
}

async function analyze(samples: Float32Array, count: number, preference: string, soundEvents: any[] = []) {
  if (!(samples instanceof Float32Array) || samples.length < 1600 || samples.length > 600 * 16000 || samples.some(x => !Number.isFinite(x)))
    throw Error('0.1초부터 10분까지의 정상적인 녹음으로 분석해 주세요.');
  let backend: 'wasm' | 'webgpu' = 'wasm';
  if (preference !== 'wasm') {
    try { if (await (navigator as any).gpu?.requestAdapter()) backend = 'webgpu'; } catch { /* same FP32 model on CPU */ }
  }
  report(backend === 'webgpu' ? '브라우저 그래픽 가속으로 전문 모델을 준비합니다…' : '그래픽 가속을 사용할 수 없어 같은 FP32 모델을 CPU로 준비합니다. 시간이 더 걸릴 수 있어요.');
  const getJson = async (file: string) => { const r = await fetch(new URL('./vendor/nemotron/' + file, import.meta.url)); if (!r.ok) throw Error('분석 설정을 불러오지 못했습니다. 새로고침해 주세요.'); return r.json(); };
  const config = { model: await getJson('config.json'), processor: await getJson('processor_config.json') };
  const source = { config, model: await load(ASSETS.graph), externalDataName: 'model.onnx_data', externalData: await load(ASSETS.weights) };
  report(`전문 모델 시작 중 · ${backend === 'webgpu' ? '그래픽 가속' : 'CPU'} · 처음에는 시간이 걸릴 수 있어요`);
  // Never silently change models or lose the beginning of a recording on a retry.
  let diarizer: StreamingDiarizer;
  try { diarizer = await StreamingDiarizer.create(source, { executionProvider: backend, streamingMode: 'offline' }); }
  catch (e) { throw Error(backend === 'webgpu' ? '이 브라우저의 그래픽 가속에서 모델을 시작하지 못했습니다. 실행 장치를 CPU로 바꾸고 다시 분석해 주세요.' : '모델을 시작하지 못했습니다. 다른 무거운 탭을 닫고 최신 데스크톱 Chrome 또는 Edge에서 다시 시도해 주세요.'); }
  source.model = new Uint8Array(0); source.externalData = new Uint8Array(0);
  const start = performance.now();
  const run = async (audio: Float32Array, recheck = false) => {
    diarizer.reset();
    const probabilities = new Float32Array(Math.ceil(audio.length / 160) * 8); let emitted = 0;
    const collect = (frames: any) => { probabilities.set(frames.probabilities, frames.startFrame * 8); emitted = Math.max(emitted, frames.startFrame + frames.numFrames); };
    for (let offset = 0; offset < audio.length; offset += 16000) {
      collect(await diarizer.push(audio.subarray(offset, offset + 16000)));
      report(`${recheck ? '웃음의 목소리 재확인' : '같은 목소리·겹친 발화 추적'} 중 · ${Math.min(audio.length / 16000, offset / 16000 + 1).toFixed(0)} / ${Math.ceil(audio.length / 16000)}초 · ${backend === 'webgpu' ? '그래픽 가속' : 'CPU'}`);
    }
    collect(await diarizer.flush());
    return formatResult(probabilities.subarray(0, emitted * 8), audio.length / 16000, count, backend, +((performance.now() - start) / 1000).toFixed(2));
  };
  try {
    const primary = await run(samples);
    if (soundEvents.some(e => e.kind === 'laughter') && primary.diagnostics.detectedCount > 0) {
      const secondary = await run(vocalOnly(samples, soundEvents), true);
      recoverLaughter(primary, secondary, soundEvents);
    }
    return primary;
  } finally { await diarizer.dispose(); }
}

let busy = false;
self.onmessage = async ({ data }) => {
  if (busy) return; busy = true;
  try { const result = await analyze(data.samples, data.count || 0, data.backend || 'auto', Array.isArray(data.soundEvents) ? data.soundEvents : []); self.postMessage({ type: 'complete', result }); }
  catch (e: any) { self.postMessage({ type: 'error', message: e?.message || '전문 분석에 실패했습니다. 다시 시도해 주세요.' }); }
};
