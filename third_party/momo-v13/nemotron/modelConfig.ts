import type { ExecutionProvider } from "./diarizer";
import type { StreamingConfig } from "./speakerCache";

export const MODEL_VARIANTS = ["model", "model_fp16", "model_quantized", "model_q4", "model_q4f16"] as const;
export type ModelVariant = (typeof MODEL_VARIANTS)[number];

export const STREAMING_MODES = ["ultra_low_latency", "very_low_latency", "low_latency", "offline"] as const;
export type StreamingMode = (typeof STREAMING_MODES)[number];

export interface VariantInfo {
  label: string;
  /** Approximate download size of the weights, in megabytes. */
  sizeMb: number;
  /**
   * Backends this variant is known to produce correct output on. The WebGPU backend only has
   * kernels for the float32 graph: the quantised graphs fail to build, and the float16 one builds
   * but returns badly degraded probabilities.
   */
  backends: readonly ExecutionProvider[];
}

export const VARIANT_INFO: Record<ModelVariant, VariantInfo> = {
  model: { label: "fp32", sizeMb: 398, backends: ["webgpu", "wasm"] },
  model_fp16: { label: "fp16", sizeMb: 200, backends: ["wasm"] },
  model_quantized: { label: "int8", sizeMb: 121, backends: ["wasm"] },
  model_q4: { label: "q4", sizeMb: 83, backends: ["wasm"] },
  model_q4f16: { label: "q4f16", sizeMb: 73, backends: ["wasm"] },
};

export function variantsFor(backend: ExecutionProvider): ModelVariant[] {
  return MODEL_VARIANTS.filter((variant) => VARIANT_INFO[variant].backends.includes(backend));
}

/** Best default for a backend: WebGPU is fast enough for float32, CPU needs the quantised graph. */
export function defaultVariantFor(backend: ExecutionProvider): ModelVariant {
  return backend === "webgpu" ? "model" : "model_quantized";
}

/** `config.json` of the checkpoint, narrowed to the fields the browser runtime needs. */
export interface ModelConfig {
  chunk_length: number;
  chunk_right_context: number;
  fifo_length: number;
  speaker_cache_update_period: number;
  audio_config: { hidden_size: number; num_mel_bins: number; subsampling_factor: number };
  head_config: { num_speakers: number; subsampling_factor: number };
  streaming_config: StreamingConfig;
}

/** `processor_config.json` of the checkpoint. */
export interface ProcessorConfig {
  feature_extractor: {
    feature_size: number;
    hop_length: number;
    n_fft: number;
    win_length: number;
    preemphasis: number;
    sampling_rate: number;
  };
  streaming_modes: Record<string, [number, number]>;
  subsampling_factor: number;
}

export interface CheckpointConfig {
  model: ModelConfig;
  processor: ProcessorConfig;
}

export async function loadCheckpointConfig(baseUrl: string): Promise<CheckpointConfig> {
  const [model, processor] = await Promise.all([
    fetchJson<ModelConfig>(`${baseUrl}/config.json`),
    fetchJson<ProcessorConfig>(`${baseUrl}/processor_config.json`),
  ]);
  return { model, processor };
}

/**
 * `(chunk_length, chunk_right_context)` in encoder frames for a streaming mode. The offline mode
 * uses the much longer buffer declared at the top level of `config.json`.
 */
export function chunkSizesFor(config: CheckpointConfig, mode: StreamingMode): [number, number] {
  if (mode === "offline") return [config.model.chunk_length, config.model.chunk_right_context];

  const sizes = config.processor.streaming_modes[mode];
  if (!sizes) throw new Error(`Unknown streaming mode "${mode}"`);
  return sizes;
}

/**
 * Cache sizes for a mode. Offline replays a whole recording in one pass and so uses the wider FIFO
 * queue and update period from the top level of `config.json`.
 */
export function streamingConfigFor(config: CheckpointConfig, mode: StreamingMode): StreamingConfig {
  if (mode !== "offline") return config.model.streaming_config;
  return {
    ...config.model.streaming_config,
    fifo_length: config.model.fifo_length,
    speaker_cache_update_period: config.model.speaker_cache_update_period,
  };
}

/** Input buffer latency of a mode in milliseconds: a chunk is emitted once its look-ahead arrives. */
export function latencyMs(config: CheckpointConfig, mode: StreamingMode): number {
  const [chunkLength, rightContext] = chunkSizesFor(config, mode);
  const { hop_length: hopLength, sampling_rate: samplingRate } = config.processor.feature_extractor;
  const encoderFrameMs = (config.processor.subsampling_factor * hopLength * 1000) / samplingRate;
  return Math.round((chunkLength + rightContext) * encoderFrameMs);
}

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Failed to fetch ${url}: ${response.status} ${response.statusText}`);
  return response.json() as Promise<T>;
}
