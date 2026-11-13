"""Pinned local ECAPA embeddings. Session speaker labels are never persistent identities."""
import numpy as np
from ..contracts import Interval, SpeakerSignature
from ..prepare_voice_model import verify


def speech_segments(events, speaker_id):
    """Only unambiguous solo speech; trim boundaries, cap CPU work and stored samples."""
    merged = []
    for event in events:
        if event.kind != "speech" or event.requires_review or event.active_speaker_ids != [speaker_id] or event.laughing_speaker_ids:
            continue
        if merged and merged[-1][1] == event.start_us:
            merged[-1][1] = event.end_us
        else:
            merged.append([event.start_us, event.end_us])
    result, remaining = [], 30_000_000
    for start, end in merged:
        start, end = start + 100_000, end - 100_000
        while end-start >= 1_500_000 and remaining >= 1_500_000 and len(result) < 20:
            size = min(end-start, remaining, 6_000_000)
            result.append(Interval(start_us=start, end_us=start+size))
            start += size
            remaining -= size
    return result


class VoiceEncoder:
    def __init__(self, root):
        import torch
        from speechbrain.lobes.features import Fbank
        from speechbrain.processing.features import InputNormalization
        from speechbrain.lobes.models.ECAPA_TDNN import ECAPA_TDNN
        torch.set_num_threads(4)
        self.torch = torch
        self.features = Fbank(n_mels=80).eval()
        self.normalize = InputNormalization(norm_type="sentence", std_norm=False).eval()
        self.model = ECAPA_TDNN(input_size=80, channels=[1024, 1024, 1024, 1024, 3072],
                              kernel_sizes=[5, 3, 3, 3, 1], dilations=[1, 2, 3, 4, 1],
                              attention_channels=128, lin_neurons=192).eval()
        self.model.load_state_dict(torch.load(verify(root), map_location="cpu", weights_only=True))

    def encode(self, pcm):
        torch = self.torch
        with torch.inference_mode():
            waveform = torch.from_numpy(np.array(pcm, dtype=np.float32, copy=True)).unsqueeze(0)
            lengths = torch.ones(1)
            features = self.normalize(self.features(waveform), lengths)
            vector = self.model(features, lengths).flatten().numpy()
            return vector / max(float(np.linalg.norm(vector)), 1e-9)

    def analyze(self, pcm, result, cancel_check):
        signatures = []
        for speaker in result.speakers:
            vectors, accepted = [], []
            for segment in speech_segments(result.events, speaker.speaker_id):
                cancel_check()
                clip = pcm[segment.start_us*16000//1_000_000:segment.end_us*16000//1_000_000]
                if not len(clip) or float(np.sqrt(np.mean(clip**2))) < .002:
                    continue
                vector = self.encode(clip)
                if np.isfinite(vector).all():
                    vectors.append(vector)
                    accepted.append(segment)
            duration = sum(s.end_us-s.start_us for s in accepted)
            if duration < 3_000_000:
                continue
            centroid = np.average(vectors, axis=0, weights=[s.end_us-s.start_us for s in accepted])
            centroid /= max(float(np.linalg.norm(centroid)), 1e-9)
            # Reject internally inconsistent speaker clusters instead of enrolling mixed identities.
            if any(float(v @ centroid) < .60 for v in vectors):
                continue
            signatures.append(SpeakerSignature(speaker_id=speaker.speaker_id, embedding=centroid.tolist(),
                                               speech_us=duration, segments=accepted))
        return signatures
