"""Claude credentials stay in the local session; workers receive them on stdin."""
import os
from pathlib import Path
import re

from dotenv import dotenv_values
from fastapi import HTTPException, Request
import httpx
from pydantic import BaseModel, SecretStr


class ClaudeConnection(BaseModel):
    api_key: SecretStr = SecretStr("")


async def request_claude(client, key, method, path, body=None):
    try:
        response = await client.request(method, "https://api.anthropic.com/v1/" + path,
            headers={"x-api-key": key, "anthropic-version": "2023-06-01"}, json=body)
    except httpx.HTTPError as exc:
        raise HTTPException(502, "Claude 연결에 실패했습니다. 네트워크를 확인하고 다시 시도해 주세요.") from exc
    if response.is_error:
        # Provider messages may echo private input or credentials.
        message = {401: "Claude API 키를 확인해 주세요.", 403: "Claude 모델 접근 권한을 확인해 주세요.",
            404: "Claude 모델 ID를 확인해 주세요.", 429: "Claude API 잔액·요청 한도를 확인해 주세요.",
            400: "Claude 모델의 JSON 출력 지원 여부와 요청 설정을 확인해 주세요."}.get(
            response.status_code, "Claude API 오류입니다. 잠시 후 다시 시도해 주세요.")
        raise HTTPException(response.status_code if response.status_code < 500 else 502, message)
    try:
        data = response.json()
        if not isinstance(data, dict):
            raise ValueError()
    except ValueError as exc:
        raise HTTPException(502, "Claude 응답 형식을 읽을 수 없습니다.") from exc
    return data, response.headers.get("request-id")


def install_claude(app, session_for):
    values = dict(dotenv_values(Path(__file__).resolve().parents[2] / ".env"))
    values.update(os.environ)
    environment_key = (values.get("ANTHROPIC_API_KEY") or "").strip()

    def key_for(session):
        key = session.claude_key.get_secret_value() if session.claude_key else environment_key
        if session.claude_disabled or not key:
            raise HTTPException(401, "Claude 화자 재검토에 사용할 Anthropic API 키를 연결해 주세요.")
        return key

    @app.get("/api/claude/session")
    async def status(request: Request):
        session = session_for(request)
        return {"configured": bool(not session.claude_disabled and (session.claude_key or environment_key))}

    @app.post("/api/claude/connection")
    async def connect(body: ClaudeConnection, request: Request):
        session = session_for(request)
        key = body.api_key.get_secret_value().strip() or environment_key
        if not re.fullmatch(r"[!-~]{16,512}", key):
            raise HTTPException(422, "Anthropic API 키를 입력해 주세요. 공백·줄바꿈은 포함할 수 없습니다.")
        session.claude_key, session.claude_disabled = SecretStr(key), False
        return {"configured": True}

    @app.delete("/api/claude/connection")
    async def disconnect(request: Request):
        session = session_for(request)
        session.claude_key, session.claude_disabled = None, True
        return {"configured": False}

    @app.get("/api/claude/models")
    async def models(request: Request):
        key = key_for(session_for(request))
        rows, cursor = [], ""
        for _ in range(10):
            data, _ = await request_claude(app.state.openai, key, "GET", "models?limit=100" + cursor)
            rows.extend({"id": row["id"], "name": row.get("display_name", row["id"])}
                        for row in data.get("data", []) if re.fullmatch(r"claude-[a-zA-Z0-9_.-]+", row.get("id", "")))
            last = data.get("last_id", "")
            if not data.get("has_more") or not re.fullmatch(r"[a-zA-Z0-9_.-]+", last):
                break
            cursor = "&after_id=" + last
        return {"models": rows}

    return key_for
