import asyncio
import json

from fastapi.testclient import TestClient
import httpx
import pytest

from momo.topic_lab import Connection, STATIC, create_app, parse_transcript

KEY = "sk-local-test-not-a-real-api-key"
PARTITION = {"topics": [
    {"start_id": 1, "title": "강아지와 카페", "reason": "산책 중 있었던 일화"},
    {"start_id": 7, "title": "주말 여행", "reason": "여행 계획을 함께 결정"},
]}


def completion(partition=PARTITION, **overrides):
    return {"id": "resp_test", "model": "gpt-test-snapshot", "status": "completed",
            "output": [{"type": "message", "content": [
                {"type": "output_text", "text": json.dumps(partition, ensure_ascii=False)}]}],
            "usage": {"input_tokens": 100, "output_tokens": 50, "total_tokens": 150}, **overrides}


def sample_payload():
    data = json.loads((STATIC / "sample.json").read_text(encoding="utf-8"))
    return {"model": "gpt-test-a", "transcript": json.dumps(data, ensure_ascii=False),
            "windows": data["windows"]}


def authorize(client):
    data = client.get("/api/session").json()
    client.headers.update({"X-Lab-Token": data["csrf"]})
    return data


def lab_client(handler):
    app = create_app(transport=httpx.MockTransport(handler), credentials=Connection(api_key=KEY))
    return TestClient(app, base_url="http://127.0.0.1:8770")


def test_real_api_contract_and_existing_clip_selection():
    calls = []

    def upstream(request):
        calls.append(request)
        assert str(request.url) == "https://api.openai.com/v1/responses"
        assert request.headers["authorization"] == "Bearer " + KEY
        return httpx.Response(200, json=completion(), headers={"x-request-id": "req_test"})

    with lab_client(upstream) as client:
        authorize(client)
        response = client.post("/api/analyze", json=sample_payload())
        assert response.status_code == 200, response.text
        result = response.json()
        assert result["provider"] == "openai"
        assert result["model"] == "gpt-test-snapshot"
        assert result["requested_model"] == "gpt-test-a"
        assert [(c["startMs"], c["endMs"]) for c in result["clips"]] == [(0, 32000), (34000, 70000)]
        assert all("Qwen" not in c["boundaryMethod"] for c in result["clips"])
        assert result["usage"]["total_tokens"] == 150
        assert result["request_id"] == "req_test"
        assert KEY not in response.text
        body = json.loads(calls[0].content)
        assert body["model"] == "gpt-test-a"
        assert body["store"] is False
        assert body["text"]["format"]["strict"] is True
        assert "startMs" not in body["input"]
        assert "temperature" not in body
        assert len(calls) == 1
        other = client.post("/api/analyze", json={**sample_payload(), "model": "gpt-test-b"}).json()
        assert other["input_hash"] == result["input_hash"]
        assert json.loads(calls[1].content)["model"] == "gpt-test-b"


def test_plain_text_does_not_invent_timestamps_or_laughter():
    partition = {"topics": [{"start_id": 1, "title": "대화", "reason": "하나의 이야기"}]}
    with lab_client(lambda _: httpx.Response(200, json=completion(partition))) as client:
        authorize(client)
        result = client.post("/api/analyze", json={"model": "gpt-test", "transcript": "안녕\n반가워"}).json()
        assert result["timed"] is False
        assert result["clips"] == []
        assert result["topics"][0]["startMs"] is None
        assert result["topics"][0]["end_id"] == 2


def test_bom_json_preserves_timestamps_and_duration():
    with lab_client(lambda _: httpx.Response(200, json=completion())) as client:
        authorize(client)
        payload = sample_payload()
        payload["transcript"] = "\ufeff  " + payload["transcript"]
        response = client.post("/api/analyze", json=payload)
        assert response.status_code == 200
        assert response.json()["input"]["durationMs"] == 72000
        assert response.json()["timed"] is True


def test_browser_key_is_not_shared_with_another_session(monkeypatch):
    monkeypatch.setattr("momo.topic_lab.environment_credentials", lambda: None)
    app = create_app(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=completion())))
    with TestClient(app, base_url="http://127.0.0.1:8770") as client:
        assert authorize(client)["configured"] is False
        assert client.post("/api/connection", json={"api_key": KEY}).status_code == 200
        first_cookie = client.cookies.get("topic_lab_session")
        client.cookies.clear()
        assert authorize(client)["configured"] is False
        assert client.cookies.get("topic_lab_session") != first_cookie
        assert client.post("/api/analyze", json=sample_payload()).status_code == 401


@pytest.mark.parametrize("changes", [
    {"transcript": "{"}, {"transcript": ""}, {"transcript": "텍스트", "windows": [{"startMs": 0, "endMs": 1000, "laughter": .5}]},
    {"windows": [{"startMs": 1000, "endMs": 500, "laughter": .5}]},
    {"windows": [{"startMs": 0, "endMs": 1000, "laughter": 2}]},
    {"threshold": 0}, {"max_clips": 0}, {"max_output_tokens": 1}, {"model": "../responses"},
    {"windows": [{"startMs": 71000, "endMs": 75000, "laughter": .5}]},
])
def test_invalid_input_rejected_before_charged_request(changes):
    def upstream(_):
        pytest.fail("invalid input must not call OpenAI")
    with lab_client(upstream) as client:
        authorize(client)
        assert client.post("/api/analyze", json={**sample_payload(), **changes}).status_code == 422


@pytest.mark.parametrize("data", [
    [{"id": 1, "text": "a"}, {"id": 3, "text": "b"}],
    [{"id": True, "text": "a"}],
    [{"text": "a", "startMs": 0}],
    [{"text": "a", "startMs": 0, "endMs": 100}, {"text": "b"}],
    [{"text": "a", "startMs": 10, "endMs": 100}, {"text": "b", "startMs": 0, "endMs": 200}],
    {"utterances": [{"text": "a", "startMs": 0, "endMs": 100}], "durationMs": 50},
])
def test_transcript_ids_and_times_validated(data):
    with pytest.raises(ValueError):
        parse_transcript(json.dumps(data))


@pytest.mark.parametrize("response", [
    completion(status="incomplete"),
    completion({"topics": [{"start_id": 2, "title": "bad", "reason": "bad"}]}),
    completion({"topics": [{"start_id": 1, "title": "a", "reason": "a"}, {"start_id": 1, "title": "b", "reason": "b"}]}),
    completion({"topics": []}),
    completion(output=[{"type": "message", "content": [{"type": "output_text", "text": "not json"}]}]),
    completion(output=[{"type": "message", "content": [{"type": "refusal", "refusal": "no"}]}]),
])
def test_incomplete_refusal_and_invalid_partition_not_silently_accepted(response):
    calls = []

    def upstream(request):
        calls.append(request)
        return httpx.Response(200, json=response)
    with lab_client(upstream) as client:
        authorize(client)
        result = client.post("/api/analyze", json=sample_payload())
        assert result.status_code in (422, 502)
        assert "topics" not in result.json()
        assert len(calls) == 1  # no unexpected, billable retry


@pytest.mark.parametrize("status", [400, 401, 403, 404, 429, 500])
def test_api_errors_never_echo_provider_secret(status):
    with lab_client(lambda _: httpx.Response(status, json={"error": {"message": "Invalid key: " + KEY}})) as client:
        authorize(client)
        response = client.post("/api/analyze", json=sample_payload())
        assert response.status_code == (502 if status == 500 else status)
        assert KEY not in response.text


def test_key_sessions_csrf_and_origin():
    app = create_app(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=completion())),
                     credentials=Connection(api_key=KEY))
    with TestClient(app, base_url="http://127.0.0.1:8770") as client:
        session = authorize(client)
        assert KEY not in json.dumps(session)
        assert "HttpOnly" in client.get("/api/session").headers["set-cookie"]
        assert client.post("/api/analyze", json=sample_payload(), headers={"X-Lab-Token": "bad"}).status_code == 403
        assert client.post("/api/analyze", json=sample_payload(), headers={"Origin": "https://evil.example"}).status_code == 403
        assert client.get("/", headers={"Host": "evil.example"}).status_code == 403
        assert client.delete("/api/connection").status_code == 200
        assert client.post("/api/analyze", json=sample_payload()).status_code == 401
        response = client.post("/api/connection", json={"api_key": {"accidental": KEY}})
        assert response.status_code == 422
        assert KEY not in response.text
        assert client.post("/api/connection", json={"api_key": KEY}).status_code == 200
        assert client.post("/api/analyze", json=sample_payload()).status_code == 200
        client.cookies.clear()
        assert client.post("/api/analyze", json=sample_payload()).status_code == 401


def test_models_list_and_scoped_headers():
    def upstream(request):
        assert request.url.path == "/v1/models"
        assert request.headers["OpenAI-Project"] == "proj_test"
        assert request.headers["OpenAI-Organization"] == "org-test"
        return httpx.Response(200, json={"data": [{"id": i} for i in
            ["gpt-test-a", "gpt-test-b", "gpt-image-test", "gpt-transcribe", "text-embedding-3-small"]]})
    with lab_client(upstream) as client:
        authorize(client)
        client.post("/api/connection", json={"api_key": KEY, "project_id": "proj_test", "organization_id": "org-test"})
        response = client.get("/api/models")
        assert response.status_code == 200
        assert set(response.json()["models"]) == {"gpt-test-a", "gpt-test-b"}


def test_parallel_run_rejected_and_lock_released_after_timeout():
    async def scenario():
        entered, release = asyncio.Event(), asyncio.Event()

        async def upstream(request):
            entered.set()
            await release.wait()
            raise httpx.ReadTimeout("timeout", request=request)

        app = create_app(transport=httpx.MockTransport(upstream), credentials=Connection(api_key=KEY))
        async with app.router.lifespan_context(app), httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://127.0.0.1:8770"
        ) as client:
            session = (await client.get("/api/session")).json()
            client.headers["X-Lab-Token"] = session["csrf"]
            first = asyncio.create_task(client.post("/api/analyze", json=sample_payload()))
            await asyncio.wait_for(entered.wait(), 5)
            assert (await client.post("/api/analyze", json=sample_payload())).status_code == 409
            assert (await client.delete("/api/connection")).status_code == 409
            release.set()
            assert (await first).status_code == 504
            assert (await client.post("/api/analyze", json=sample_payload())).status_code == 504
    asyncio.run(scenario())


def test_static_page_limits_and_security_headers():
    with lab_client(lambda _: httpx.Response(200, json={})) as client:
        page = client.get("/")
        assert page.status_code == 200
        assert "MOMO" in page.text
        assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
        assert page.headers["cache-control"] == "no-store"
        assert client.get("/static/app.js").status_code == 200
        assert client.get("/static/../../.env").status_code == 404
        authorize(client)
        assert client.post("/api/analyze", content="x" * 800_001).status_code == 413
