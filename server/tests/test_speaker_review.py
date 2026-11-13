import asyncio
import copy
import json

from fastapi.testclient import TestClient
import httpx
import numpy as np
import pytest

from momo.audio_lab import speaker_review as review
from momo.audio_lab.models import AudioConfig, SpeakerReview
from momo.audio_lab import worker, segment_worker
from momo.topic_lab import create_app
from test_audio_lab import audio_client as base_audio_client, fake_process, upload
from test_character_segments import lane, cached_job

review_client = base_audio_client
SPEAKERS = [dict(speaker_id="S1"), dict(speaker_id="S2")]


def paired():
    rows = [lane("G000001_0", None), lane("G000001_1", None)]
    rows[0]["candidates"] = [dict(speaker="S1", score=.59), dict(speaker="S2", score=.58)]
    rows[1]["candidates"] = [dict(speaker="S1", score=.6), dict(speaker="S2", score=.2)]
    return rows


def test_local_fallback_does_not_invent_distinct_speakers_for_two_stems():
    rows = paired()
    result = review.review(rows, SPEAKERS, np.zeros((50, 8)), {0: "S1", 1: "S2"}, SpeakerReview())
    assert [r["speaker"] for r in rows] == ["S1", "S1"]
    assert all(r["acoustic_speaker"] is None and r["requires_review"] for r in rows)
    assert result["unassigned_before"] == 2 and result["unassigned_after"] == 0
    review.review(rows, SPEAKERS, np.zeros((50, 8)), {}, SpeakerReview(mode="conservative"))
    assert all(r["speaker"] is None for r in rows)


def test_manual_unknown_and_silent_or_failed_lanes_are_never_forced():
    rows = paired()
    rows[0]["manually_reviewed"] = True
    silent = lane("G000002_0", None, speech=0)
    silent["utterances"] = []
    failed = lane("G000003_0", None)
    failed["state"] = "failed"
    rows += [silent, failed]
    review.review(rows, SPEAKERS, np.zeros((50, 8)), {}, SpeakerReview())
    assert [r["speaker"] for r in rows] == [None, "S1", None, None]


def run_claude(rows, handler):
    async def call():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await review.review_claude(rows, SPEAKERS, "claude-test", "test-secret-key-123", lambda _: None, client)
    return asyncio.run(call())


def response(decisions, **kwargs):
    return httpx.Response(200, json=dict(stop_reason="end_turn", content=[dict(type="text",
        text=json.dumps(dict(decisions=decisions)))], usage={"input_tokens": 20, "output_tokens": 10}, **kwargs))


def decision(identity, speaker):
    return dict(segment_id=identity, speaker=speaker, confidence="low", reason="Context suggests this speaker.",
                dialogue=[dict(utterance_index=0, intent="statement", addressee=None)])


def test_claude_uses_messages_schema_keeps_text_timing_and_provenance():
    rows = paired()
    review.review(rows, SPEAKERS, np.zeros((50, 8)), {}, SpeakerReview())
    originals = copy.deepcopy([r["utterances"] for r in rows])
    def handle(request):
        assert request.url.path == "/v1/messages"
        assert request.headers["x-api-key"] == "test-secret-key-123"
        body = json.loads(request.content)
        assert body["output_config"]["format"]["type"] == "json_schema"
        assert "untrusted" in body["system"]
        assert "audio" not in body
        return response([decision(rows[0]["id"], "S1"), decision(rows[1]["id"], "S2")])
    result = run_claude(rows, handle)
    assert result["state"] == "complete" and result["reviewed_segments"] == 2
    assert [r["speaker"] for r in rows] == ["S1", "S2"]
    assert all(r["assignment_source"] == "claude_provisional" and r["requires_review"] for r in rows)
    assert [r["utterances"] for r in rows] == originals


@pytest.mark.parametrize("bad", ["duplicate_id", "unknown_id", "missing", "unknown_speaker", "truncated"])
def test_invalid_model_responses_do_not_partially_mutate_assignments(bad):
    rows = paired()
    review.review(rows, SPEAKERS, np.zeros((50, 8)), {}, SpeakerReview())
    before = copy.deepcopy(rows)
    decisions = [decision(rows[0]["id"], "S1"), decision(rows[1]["id"], "S2")]
    if bad == "duplicate_id":
        decisions.append(decisions[0])
    if bad == "unknown_id":
        decisions[0]["segment_id"] = "G999999_0"
    if bad == "missing":
        decisions.pop()
    if bad == "unknown_speaker":
        decisions[0]["speaker"] = "S8"
    result = run_claude(rows, lambda _: httpx.Response(200, json={"stop_reason": "max_tokens"}) if bad == "truncated" else response(decisions))
    assert result["state"] == "failed" and rows == before


def test_manual_decision_is_locked_but_same_voice_in_sibling_is_allowed():
    rows = paired()
    rows[0].update(speaker="S1", manually_reviewed=True)
    review.review(rows, SPEAKERS, np.zeros((50, 8)), {}, SpeakerReview())
    def handle(request):
        segments = json.loads(json.loads(request.content)["messages"][0]["content"])["segments"]
        assert segments[0]["locked"] and not segments[0]["target"]
        return response([decision(rows[1]["id"], "S1")])
    result = run_claude(rows, handle)
    assert result["state"] == "complete" and [r["speaker"] for r in rows] == ["S1", "S1"]
    assert rows[0]["assignment_source"] == "manual" and "claude_review" not in rows[0]


def test_claude_may_identify_separator_leakage_as_the_same_speaker():
    rows = paired()
    review.review(rows, SPEAKERS, np.zeros((50, 8)), {}, SpeakerReview())
    result = run_claude(rows, lambda _: response([decision(r["id"], "S1") for r in rows]))
    assert result["state"] == "complete" and [r["speaker"] for r in rows] == ["S1", "S1"]


def test_provider_auth_failure_stops_long_recording_requests_and_redacts_errors():
    rows = [lane(f"G{i:06d}_0", None, start=i*1000, end=(i+1)*1000) for i in range(50)]
    calls = []
    def handle(request):
        calls.append(request)
        return httpx.Response(401, json={"error": {"message": "test-secret-key-123 private transcript"}})
    result = run_claude(rows, handle)
    assert len(calls) == 1 and result["state"] == "failed"
    assert "test-secret" not in json.dumps(result) and "private transcript" not in json.dumps(result)


def test_review_only_reuses_audio_models_and_topic_results(tmp_path, monkeypatch):
    prior, original, rows = cached_job(tmp_path)
    rows[0]["speaker"] = None
    (prior / "segment_G000001.json").write_text(json.dumps([rows[0]]))
    output = tmp_path / "review"
    output.mkdir()
    monkeypatch.setattr(segment_worker, "SegmentEngine", lambda *a: pytest.fail("must reuse audio"))
    monkeypatch.setattr(worker, "partition", lambda *a: pytest.fail("must reuse topics"))
    worker.run(dict(id="review", directory=str(output), source="unused", input=original["input"],
        config=original["config"], reuse=str(prior / "result.json"), review_speakers=True))
    result = json.loads((output / "result.json").read_text(encoding="utf-8"))
    assert result["speaker_review"]["unassigned_after"] == 0
    assert result["characters"]["captions"][0]["requires_review"]
    assert result["characters"]["provisional_segments"] == 1
    assert result["stages"]["stt"]["data"]["processed_groups"] == 0
    assert json.loads((prior / "segment_G000001.json").read_text())[0]["speaker"] is None


def test_claude_key_is_required_only_for_review_and_never_persisted(review_client, monkeypatch):
    client, lab = review_client
    captured = []
    fake_process(monkeypatch, captured)
    client.delete("/api/claude/connection")
    config = AudioConfig(pipeline="segments", speaker_review=SpeakerReview(mode="claude", model="claude-test"))
    body = dict(upload_id=upload(client), config=config.model_dump())
    assert client.post("/api/audio/jobs", json=body).status_code == 401
    key = "test-anthropic-secret-123"
    assert client.post("/api/claude/connection", json={"api_key": key}).status_code == 200
    result = client.post("/api/audio/jobs", json=body)
    assert result.status_code == 200
    assert captured[0]["claude_key"] == key
    assert key not in json.dumps(captured[0]["config"])
    assert all(key not in p.read_text(errors="ignore") for p in lab.root.rglob("*.json"))
    client.cookies.clear()
    client.headers["X-Lab-Token"] = client.get("/api/session").json()["csrf"]
    client.delete("/api/claude/connection")
    assert not client.get("/api/claude/session").json()["configured"]
    assert client.get("/api/claude/models").status_code == 401


def test_claude_models_use_account_endpoint_and_csrf(monkeypatch):
    def handle(request):
        assert request.url.host == "api.anthropic.com" and request.url.path == "/v1/models"
        return httpx.Response(200, json={"data": [{"id": "claude-example", "display_name": "Example"}], "has_more": False})
    with TestClient(create_app(transport=httpx.MockTransport(handle)), base_url="http://127.0.0.1:8770") as client:
        token = client.get("/api/session").json()["csrf"]
        assert client.post("/api/claude/connection", json={"api_key": "test-secret-key-123"}).status_code == 403
        client.headers["X-Lab-Token"] = token
        client.post("/api/claude/connection", json={"api_key": "test-secret-key-123"})
        assert client.get("/api/claude/models").json()["models"] == [{"id": "claude-example", "name": "Example"}]
