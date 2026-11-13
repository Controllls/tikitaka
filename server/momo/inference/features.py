"""Numerical ports of v13 audio-features.js and Nemotron featureExtractor.ts/mel.ts."""
import math
import numpy as np
from scipy.signal import lfilter


def speech_band(samples):
    # lfilter computes in float64 like JS; only the output is rounded to Float32Array.
    high = lfilter([.91, -.91], [1, -.91], np.asarray(samples, dtype=np.float64))
    return lfilter([.70], [1, -.30], high).astype(np.float32)


def envelope(samples):
    raw = np.asarray(samples, dtype=np.float64)
    high = lfilter([.966, -.966], [1, -.966], raw)
    voice = lfilter([.747], [1, -.253], high)
    return (np.array([np.sqrt(np.mean(raw[i:i+320] ** 2)) for i in range(0, len(raw), 320)]),
            np.array([np.sqrt(np.mean(voice[i:i+320] ** 2)) for i in range(0, len(raw), 320)]))


def quantile(values, q=.85):
    ordered = np.sort(values)
    return float(ordered[math.floor((len(ordered)-1)*q)]) if len(ordered) else 0.


def mel_bank():
    step = math.log(6.4) / 27
    maximum = 15 + math.log(8) / step
    mels = np.linspace(0, maximum, 130)
    points = np.where(mels >= 15, 1000 * np.exp(step * (mels-15)), mels * (200/3))
    frequencies = np.arange(257) * 16000 / 512
    rows = []
    for left, center, right in zip(points[:-2], points[1:-1], points[2:]):
        rows.append(np.maximum(0, np.minimum((frequencies-left)/(center-left),
                    (right-frequencies)/(right-center))) * (2/(right-left)))
    return np.asarray(rows, dtype=np.float32)


class FeatureExtractor:
    def __init__(self):
        self.window = np.pad(np.hanning(400).astype(np.float32), (56, 56))
        self.filters = mel_bank()

    def num_frames(self, count, centered):
        return max(0, math.floor((count + (512 if centered else 0) - 512)/160) + (0 if centered else 1))

    def extract(self, pcm, centered):
        count = self.num_frames(len(pcm), centered)
        if not count:
            return np.zeros((0, 128), np.float32)
        signal = np.empty(len(pcm), np.float32)
        signal[0] = pcm[0]
        signal[1:] = (pcm[1:].astype(np.float64) - .97 * pcm[:-1].astype(np.float64)).astype(np.float32)
        if centered:
            signal = np.pad(signal, (256, 256))
        frames = np.lib.stride_tricks.sliding_window_view(signal, 512)[::160][:count]
        windowed = (frames * self.window).astype(np.float32)
        power = (np.abs(np.fft.rfft(windowed.astype(np.float64))) ** 2).astype(np.float32)
        # JS sums rounded Float32 FFT power/filter products in double precision.
        return np.log(power.astype(np.float64) @ self.filters.astype(np.float64).T + 2**-24).astype(np.float32)
