import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("momo_evaluation", Path(__file__).resolve().parents[2]/"tools/evaluate_quality.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


def test_quality_metrics_use_permutation_and_overlap():
    reference = dict(duration_us=1000000,segments=[dict(start_us=0,end_us=1000000,speaker_ids=["alice","bob"])],
        laughter=[],transcript="안녕 하세요")
    result = dict(duration_us=1000000,speakers=[dict(speaker_id="S1"),dict(speaker_id="S2")],
        events=[dict(start_us=0,end_us=1000000,kind="speech",active_speaker_ids=["S2","S1"],
            laughing_speaker_ids=[],requires_review=False)],captions=[dict(text="안녕하세요")])
    measured = evaluation.score(reference,result,collar_us=0)
    assert measured["diarization"]["der"] == 0
    assert measured["transcription"]["cer"] == 0
    result["events"][0]["active_speaker_ids"] = ["S1"]
    assert evaluation.score(reference,result,collar_us=0)["diarization"]["der"] == .5
