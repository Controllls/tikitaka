"""Local, isolated OpenAI topic experiment. Does not start the app worker or write its DB."""
from __future__ import annotations

from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import time
from urllib.parse import urlsplit

from dotenv import dotenv_values
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
import httpx
import jsonschema
from pydantic import BaseModel, ConfigDict, Field, SecretStr, StrictInt, model_validator

from .highlights.selection import group_events
from .highlights.topics import (
    PARTITION_SCHEMA, PROMPT_VERSION, SYSTEM, boundary_for_event, context_content,
    select_topics, validate_partition,
)

STATIC = Path(__file__).with_name("topic_lab_web")
MAX_BODY = 800_000
SESSION_SECONDS = 12 * 3600


class Connection(BaseModel):
    api_key: SecretStr = SecretStr("")
    project_id: str = Field(default="", max_length=200, pattern=r"^[a-zA-Z0-9_-]*$")
    organization_id: str = Field(default="", max_length=200, pattern=r"^[a-zA-Z0-9_-]*$")


class Window(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    startMs: StrictInt = Field(ge=0, le=7_200_000)
    endMs: StrictInt = Field(gt=0, le=7_200_000)
    laughter: float = Field(ge=0, le=1)
    music: float = Field(default=0, ge=0, le=1)

    @model_validator(mode="after")
    def ordered(self):
        if self.endMs <= self.startMs:
            raise ValueError("endMs must be greater than startMs")
        return self


class Experiment(BaseModel):
    model: str = Field(min_length=1, max_length=200, pattern=r"^[a-zA-Z0-9_.:\-]+$")
    transcript: str = Field(min_length=1, max_length=250_000)
    windows: list[Window] = Field(default_factory=list, max_length=20_000)
    threshold: float = Field(default=.1, gt=0, le=1, allow_inf_nan=False)
    max_clips: int = Field(default=3, ge=1, le=10)
    max_output_tokens: int = Field(default=8192, ge=1024, le=32768)


@dataclass
class Session:
    csrf: str = field(default_factory=lambda: secrets.token_urlsafe(32))
    credentials: Connection | None = field(default=None, repr=False)
    claude_key: SecretStr | None = field(default=None, repr=False)
    claude_disabled: bool = False
    disabled: bool = False
    busy: bool = False
    touched: float = field(default_factory=time.monotonic)


def parse_transcript(text: str) -> tuple[list[dict], bool, int]:
    text = text.strip().lstrip("\ufeff").strip()
    duration = None
    if text.startswith(("{", "[")):
        try:
            data = json.loads(text)
        except ValueError as exc:
            raise ValueError("전사 JSON 형식을 확인해 주세요.") from exc
        if isinstance(data, dict):
            duration = data.get("durationMs")
            data = data.get("utterances")
    else:
        data = [{"text": line.strip()} for line in text.splitlines() if line.strip()]
    if not isinstance(data, list) or not 1 <= len(data) <= 1200:
        raise ValueError("전사는 1~1,200개 발화가 필요합니다. JSON은 utterances 배열 또는 발화 배열을 사용하세요.")
    utterances = []
    timed = None
    for index, item in enumerate(data):
        if not isinstance(item, dict) or not isinstance(item.get("text"), str) or not item["text"].strip():
            raise ValueError(f"{index + 1}번째 발화에 text 문자열이 필요합니다.")
        uid = item.get("id", index + 1)
        if type(uid) is not int or uid < 0 or (utterances and uid != utterances[-1]["id"] + 1):
            raise ValueError("발화 id는 순서대로 1씩 증가하는 음이 아닌 정수여야 합니다.")
        has_time = "startMs" in item and "endMs" in item
        if ("startMs" in item) != ("endMs" in item) or (timed is not None and timed != has_time):
            raise ValueError("모든 발화에 startMs·endMs를 함께 넣거나, 모든 시간 정보를 빼 주세요.")
        timed = has_time
        normalized = {"id": uid, "text": item["text"].strip()}
        if timed:
            start, end = item["startMs"], item["endMs"]
            if type(start) is not int or type(end) is not int or not 0 <= start < end <= 7_200_000:
                raise ValueError("발화 시간은 0~7,200,000 범위의 밀리초 정수이며 시작 < 종료여야 합니다.")
            if utterances and (start < utterances[-1]["startMs"] or end < utterances[-1]["endMs"]):
                raise ValueError("발화의 시작·종료 시간을 각각 시간순으로 정렬해 주세요.")
            normalized.update(startMs=start, endMs=end)
        utterances.append(normalized)
    if sum(len(u["text"]) for u in utterances) > 60_000:
        raise ValueError("한 번의 비교 실험은 전사문 60,000자까지 지원합니다. 짧은 구간으로 나눠 주세요.")
    last = utterances[-1].get("endMs", 0)
    if duration is not None and (type(duration) is not int or not last <= duration <= 7_200_000):
        raise ValueError("durationMs는 마지막 발화 종료 이상, 7,200,000 이하의 정수여야 합니다.")
    return utterances, bool(timed), duration if duration is not None else last


def environment_credentials() -> Connection | None:
    values = dict(dotenv_values(Path(__file__).resolve().parents[1] / ".env"))
    values.update(os.environ)
    key = (values.get("OPENAI_API_KEY") or "").strip()
    return Connection(api_key=key, project_id=values.get("OPENAI_PROJECT_ID") or "",
                      organization_id=values.get("OPENAI_ORG_ID") or "") if key else None


def headers(credentials: Connection) -> dict:
    result = {"Authorization": "Bearer " + credentials.api_key.get_secret_value()}
    if credentials.project_id:
        result["OpenAI-Project"] = credentials.project_id
    if credentials.organization_id:
        result["OpenAI-Organization"] = credentials.organization_id
    return result


async def openai_request(client: httpx.AsyncClient, credentials: Connection, method: str,
                         path: str, body: dict | None = None) -> tuple[dict, str | None]:
    try:
        response = await client.request(method, "https://api.openai.com/v1/" + path,
                                        headers=headers(credentials), json=body)
    except httpx.TimeoutException as exc:
        raise HTTPException(504, "OpenAI 응답이 5분 안에 완료되지 않았습니다. 과금 여부를 확인한 뒤 다시 실행하세요.") from exc
    except httpx.HTTPError as exc:
        raise HTTPException(502, "OpenAI에 연결할 수 없습니다. 인터넷·프록시 설정을 확인해 주세요.") from exc
    if response.is_error:
        # Never forward provider error text: it may echo credentials or user input.
        messages = {
            400: "요청 설정을 지원하지 않는 모델이거나 문맥 한도를 넘었습니다. Responses·JSON Schema 지원 모델로 바꾸거나 입력을 줄여 주세요.",
            401: "API 키 인증에 실패했습니다. 유효한 OpenAI API 키를 다시 입력해 주세요.",
            403: "이 키에 요청 권한이 없습니다. 프로젝트·모델·Models 읽기 및 Responses 쓰기 권한을 확인해 주세요.",
            404: "모델을 찾을 수 없거나 접근할 수 없습니다. 모델 ID와 계정 권한을 확인해 주세요.",
            429: "OpenAI 사용 한도 또는 요청 속도를 초과했습니다. API 잔액·사용 한도를 확인해 주세요.",
        }
        raise HTTPException(response.status_code if response.status_code < 500 else 502,
                            messages.get(response.status_code, "OpenAI API 오류입니다. 잠시 후 다시 실행해 주세요."))
    try:
        data = response.json()
        if not isinstance(data, dict):
            raise ValueError("object expected")
    except ValueError as exc:
        raise HTTPException(502, "OpenAI 응답을 읽을 수 없습니다.") from exc
    return data, response.headers.get("x-request-id")


def extract_partition(data: dict, utterances: list[dict]) -> tuple[dict, list[dict]]:
    if data.get("status") == "incomplete":
        raise HTTPException(502, "응답이 끝나기 전에 중단되었습니다. 출력 토큰 한도를 늘리거나 전사문을 줄여 주세요.")
    if data.get("status") != "completed":
        raise HTTPException(502, "OpenAI가 분석을 완료하지 못했습니다. 자동 재호출하지 않았습니다.")
    texts = []
    for output in data.get("output", []):
        if output.get("type") != "message":
            continue
        for content in output.get("content", []):
            if content.get("type") == "refusal":
                raise HTTPException(422, "모델이 이 입력의 분석을 거절했습니다. 입력 내용을 확인해 주세요.")
            if content.get("type") == "output_text":
                texts.append(content.get("text", ""))
    try:
        raw = json.loads("".join(texts))
        jsonschema.validate(raw, PARTITION_SCHEMA)
        parts = validate_partition(raw, utterances)
    except (ValueError, TypeError, jsonschema.ValidationError) as exc:
        raise HTTPException(502, "모델 결과의 주제 경계가 올바르지 않습니다. 다른 모델로 비교해 주세요. 자동 재호출하지 않았습니다.") from exc
    return raw, parts


def create_app(*, transport: httpx.AsyncBaseTransport | None = None,
               credentials: Connection | None = None) -> FastAPI:
    from .audio_lab.service import AudioLab, install_routes
    audio_lab = AudioLab()
    sessions: dict[str, Session] = {}
    env = credentials if credentials is not None else environment_credentials()

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(timeout=httpx.Timeout(300, connect=20), transport=transport,
                                     follow_redirects=False) as client:
            app.state.openai = client
            try:
                yield
            finally:
                audio_lab.close()
                sessions.clear()

    app = FastAPI(title="MOMO Topic Lab", lifespan=lifespan, docs_url=None, redoc_url=None,
                  openapi_url=None)
    app.state.audio_lab = audio_lab

    @app.middleware("http")
    async def local_only(request: Request, call_next):
        host = urlsplit("http://" + request.headers.get("host", "")).hostname
        origin = request.headers.get("origin")
        expected = f"{request.url.scheme}://{request.headers.get('host')}"
        if host not in {"localhost", "127.0.0.1", "::1"} or (origin and origin != expected):
            return JSONResponse({"detail": "로컬 페이지에서 접속해 주세요."}, status_code=403)
        if request.url.path in {"/api/audio/uploads", "/api/video/assets", "/api/video/packages"} and request.method == "POST":
            from .audio_lab.limits import MAX_ASSET_BYTES, upload_limit_message
            from .config import settings
            length = request.headers.get("content-length", "0")
            original = request.url.path == "/api/audio/uploads"
            limit = settings.lab_max_audio_bytes if original else MAX_ASSET_BYTES
            if not length.isdigit() or int(length) > limit:
                return JSONResponse({"detail": upload_limit_message() if original else "디자이너 소스는 한 번에 80 MB 이하로 추가해 주세요."}, status_code=413)
        elif request.method in {"POST", "PUT", "PATCH"}:
            chunks, size = [], 0
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_BODY:
                    return JSONResponse({"detail": "입력 파일은 800 KB 이하여야 합니다."}, status_code=413)
                chunks.append(chunk)
            request._body = b"".join(chunks)
        response = await call_next(request)
        response.headers.update({
            "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
            "Referrer-Policy": "no-referrer",
            "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        })
        return response

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, exc):
        # Pydantic's default response includes invalid input, including a submitted API key.
        fields = sorted({".".join(str(p) for p in e["loc"][1:]) for e in exc.errors()})
        return JSONResponse({"detail": "입력 형식·범위를 확인해 주세요: " + ", ".join(fields)}, status_code=422)

    def session_for(request: Request, *, csrf=True) -> Session:
        session = sessions.get(request.cookies.get("topic_lab_session", ""))
        if session is None or time.monotonic() - session.touched > SESSION_SECONDS:
            raise HTTPException(401, "페이지 세션이 만료되었습니다. 새로고침한 뒤 키를 다시 설정해 주세요.")
        if csrf and not secrets.compare_digest(request.headers.get("x-lab-token", ""), session.csrf):
            raise HTTPException(403, "페이지를 새로고침한 뒤 다시 실행해 주세요.")
        session.touched = time.monotonic()
        return session

    def key_for(session: Session) -> Connection:
        key = None if session.disabled else session.credentials or env
        if not key:
            raise HTTPException(401, "먼저 OpenAI API 키를 연결해 주세요.")
        return key

    @app.get("/api/session")
    async def status(request: Request):
        now = time.monotonic()
        for sid in list(sessions):
            if now - sessions[sid].touched > SESSION_SECONDS and not sessions[sid].busy:
                del sessions[sid]
        sid = request.cookies.get("topic_lab_session", "")
        if sid not in sessions:
            if len(sessions) >= 128:
                raise HTTPException(503, "세션이 많습니다. 테스트 서버를 다시 시작해 주세요.")
            sid = secrets.token_urlsafe(32)
            sessions[sid] = Session()
        s = sessions[sid]
        s.touched = now
        response = JSONResponse({"csrf": s.csrf, "configured": bool(not s.disabled and (s.credentials or env)),
                                 "source": "session" if s.credentials else "environment" if env else None,
                                 "environment_available": bool(env), "prompt_version": PROMPT_VERSION})
        response.set_cookie("topic_lab_session", sid, httponly=True, samesite="strict")
        return response

    @app.post("/api/connection")
    async def connect(body: Connection, request: Request):
        s = session_for(request)
        if s.busy:
            raise HTTPException(409, "분석이 끝난 뒤 키를 변경해 주세요.")
        key = body.api_key.get_secret_value().strip()
        if key:
            if not re.fullmatch(r"[!-~]{16,512}", key):
                raise HTTPException(422, "API 키 형식을 확인해 주세요. 공백·줄바꿈은 포함할 수 없습니다.")
            s.credentials = body.model_copy(update={"api_key": SecretStr(key)})
        elif env:
            s.credentials = None
        else:
            raise HTTPException(422, "OpenAI API 키를 입력해 주세요.")
        s.disabled = False
        return {"configured": True}

    @app.delete("/api/connection")
    async def disconnect(request: Request):
        s = session_for(request)
        if s.busy:
            raise HTTPException(409, "분석이 끝난 뒤 연결을 해제해 주세요.")
        s.credentials, s.disabled = None, True
        return {"configured": False}

    @app.get("/api/models")
    async def models(request: Request):
        data, _ = await openai_request(app.state.openai, key_for(session_for(request)), "GET", "models")
        excluded = ("audio", "realtime", "transcrib", "tts", "image", "search", "embedding", "moderation")
        ids = sorted({row["id"] for row in data.get("data", []) if isinstance(row.get("id"), str)
                      and row["id"].startswith(("gpt-", "o1", "o3", "o4", "ft:gpt-"))
                      and not any(word in row["id"] for word in excluded)}, reverse=True)
        return {"models": ids, "note": "목록은 접근 가능한 텍스트 모델 후보입니다. Responses·JSON Schema 지원 여부는 실행 시 확인됩니다."}

    @app.post("/api/analyze")
    async def analyze(body: Experiment, request: Request):
        s = session_for(request)
        key = key_for(s)
        if s.busy:
            raise HTTPException(409, "이미 분석 중입니다. 완료 후 다시 실행해 주세요.")
        try:
            transcript = body.transcript.strip().lstrip("\ufeff").strip()
            utterances, timed, duration = parse_transcript(transcript)
            windows = [w.model_dump() for w in body.windows]
            if windows and not timed:
                raise ValueError("웃음 후보와 결합하려면 전사에 startMs·endMs 시간 정보가 필요합니다.")
            if windows:
                explicit_duration = json.loads(transcript).get("durationMs") if transcript.startswith("{") else None
                last_window = max(w["endMs"] for w in windows)
                if explicit_duration is not None and last_window > duration:
                    raise ValueError("웃음 후보의 종료 시간이 durationMs를 초과합니다.")
                duration = max(duration, last_window)
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from exc
        normalized = {"utterances": utterances, "windows": windows, "threshold": body.threshold,
                      "max_clips": body.max_clips, "durationMs": duration}
        fingerprint = hashlib.sha256(json.dumps(normalized, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        payload = {
            "model": body.model, "instructions": SYSTEM, "input": context_content(utterances),
            "store": False, "max_output_tokens": body.max_output_tokens,
            "text": {"format": {"type": "json_schema", "name": "conversation_topics",
                                 "strict": True, "schema": PARTITION_SCHEMA}},
        }
        s.busy = True
        started = time.perf_counter()
        try:
            data, request_id = await openai_request(app.state.openai, key, "POST", "responses", payload)
            raw, partition = extract_partition(data, utterances)
            by_id = {u["id"]: u for u in utterances}
            topics = [dict(p, startMs=by_id[p["start_id"]].get("startMs"),
                           endMs=by_id[p["end_id"]].get("endMs"),
                           text="\n".join(u["text"] for u in utterances if p["start_id"] <= u["id"] <= p["end_id"]))
                      for p in partition]
            events = group_events(windows, body.threshold) if windows else []
            decisions = [dict(boundary_for_event(partition, event, utterances), eventId=event["eventId"])
                         for event in events]
            clips = select_topics(decisions, utterances, events, duration, body.max_clips) if events else []
            for clip in clips:
                clip["boundaryMethod"] = "OpenAI topic model + supplied transcript timestamps"
            return {"id": secrets.token_hex(8), "created_at": datetime.now(timezone.utc).isoformat(),
                    "provider": "openai", "requested_model": body.model, "model": data.get("model", body.model),
                    "elapsed_ms": round((time.perf_counter() - started) * 1000), "usage": data.get("usage") or {},
                    "request_id": request_id, "response_id": data.get("id"), "input_hash": fingerprint,
                    "prompt_version": PROMPT_VERSION, "max_output_tokens": body.max_output_tokens,
                    "timed": timed, "topics": topics, "clips": clips, "events": events,
                    "input": normalized, "raw": raw}
        finally:
            s.busy = False

    @app.get("/")
    async def index():
        return FileResponse(STATIC / "audio.html")

    @app.get("/text")
    async def text_index():
        return FileResponse(STATIC / "index.html")

    from .audio_lab.claude import install_claude
    claude_key_for = install_claude(app, session_for)
    install_routes(app, audio_lab, session_for, key_for, claude_key_for)
    app.mount("/static", StaticFiles(directory=STATIC), name="static")
    return app
