import json
from pathlib import Path
import numpy as np
import pytest
import jsonschema
from momo.contracts import AnalysisResult, MotionTracks, validate_pair
from momo.inference.audio_io import decode
from momo.inference.laughter import recover, vocal_only
from momo.errors import ServiceError
from test_api import wav

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize("seconds", [3, 30])
def test_fixtures(seconds):
    result = json.loads((ROOT/f"tests/fixtures/result-{seconds}.json").read_text(encoding="utf-8"))
    tracks = json.loads((ROOT/f"tests/fixtures/tracks-{seconds}.json").read_text(encoding="utf-8"))
    for data, name in [(result, "analysis-result"), (tracks, "motion-tracks")]:
        jsonschema.Draft202012Validator(json.loads((ROOT/f"contracts/{name}.schema.json").read_text(encoding="utf-8"))).validate(data)
    validate_pair(AnalysisResult.model_validate(result), MotionTracks.model_validate(tracks))


@pytest.mark.parametrize("mutation", ["gap", "laugh_ref", "nonfinite", "length", "version"])
def test_semantic_rejection(mutation):
    result = json.loads((ROOT/"tests/fixtures/result-3.json").read_text(encoding="utf-8"))
    tracks = json.loads((ROOT/"tests/fixtures/tracks-3.json").read_text(encoding="utf-8"))
    if mutation == "gap":
        result["events"][0]["start_us"] = 1
    if mutation == "laugh_ref":
        result["events"][0]["laughing_speaker_ids"] = ["S8"]
    if mutation == "nonfinite":
        tracks["mixed_voice_energy"][0] = float("nan")
    if mutation == "length":
        tracks["speaker_tracks"][0]["activity"].pop()
    if mutation == "version":
        result["schema_version"] = "2.0"
    with pytest.raises(ValueError):
        validate_pair(AnalysisResult.model_validate(result), MotionTracks.model_validate(tracks))


def test_decoder_length_and_silence(tmp_path):
    source, target = tmp_path/"source", tmp_path/"pcm"
    source.write_bytes(wav(.2))
    pcm, duration, mime = decode(source, target)
    assert duration == 200000 and np.max(np.abs(pcm)) == 0
    source.write_bytes(wav(600.2))
    with pytest.raises(ServiceError, match="AUDIO_TOO_LONG"):
        decode(source, target)


def test_laugh_identity_anchor_and_overlap():
    primary = np.zeros((150,8), np.float32)
    secondary = primary.copy()
    primary[:100,0] = .95
    secondary[:150,3] = .9
    kinds = ["speech"]*100+["laughter"]*50
    laugh = recover(primary, secondary, kinds)
    assert np.all(laugh[100:,0] >= .5) and not np.any(laugh[:100])
    primary[100:,0:2] = .8
    assert not np.any(recover(primary, secondary, kinds))
    pcm=np.ones(150*320,np.float32)
    assert np.all(vocal_only(pcm,["silence"]*150)==0)
