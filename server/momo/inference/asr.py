from ..contracts import Caption, Word


class TranscriptionEngine:
    def __init__(self, root, device):
        from faster_whisper import WhisperModel
        self.model = WhisperModel(str(root / "whisper"), device=device,
                                  compute_type="float16" if device == "cuda" else "int8",
                                  cpu_threads=4, num_workers=1, local_files_only=True)

    def analyze(self, pcm, acoustic, cancel_check=lambda: None, *, progress=lambda value: None):
        # Explicit original-time crops; do not use a compacted audio clock for captions.
        spans = []
        for i, kind in enumerate(acoustic["kinds"]):
            if kind == "speech":
                if spans and i-spans[-1][1] <= 25 and (i-spans[-1][0]) < 1450:
                    spans[-1][1] = i+1
                else:
                    spans.append([i, i+1])
        captions = []
        duration_us = round(len(pcm)/16000*1e6)
        for index, (a, b) in enumerate(spans):
            progress(index / len(spans))
            cancel_check()
            start = max(0, a*320-1600)
            end = min(len(pcm), b*320+1600)
            offset = start/16000
            segments, _ = self.model.transcribe(pcm[start:end], language="ko", task="transcribe",
                                                word_timestamps=True, vad_filter=False, beam_size=5,
                                                condition_on_previous_text=False)
            for segment in segments:
                cancel_check()
                if segment.no_speech_prob > .8 and segment.avg_logprob < -1:
                    continue
                text = segment.text.strip()
                begin = max(0, round((offset+segment.start)*1e6))
                finish = min(duration_us, round((offset+segment.end)*1e6))
                if not text or finish <= begin:
                    continue
                words = []
                for word in segment.words or []:
                    wa = max(begin, round((offset+word.start)*1e6))
                    wb = min(finish, round((offset+word.end)*1e6))
                    if wb > wa:
                        words.append(Word(start_us=wa, end_us=wb, text=word.word))
                captions.append(Caption(caption_id=f"C{len(captions)+1}", start_us=begin,
                                        end_us=finish, text=text, words=words))
        progress(1)
        return captions
