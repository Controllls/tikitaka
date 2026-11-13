import secrets
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import jwt
import pytest
from sqlalchemy import func, select
from momo.db import Installation, Session, SessionLocal, User
from test_api import submit


def credentials():
    return dict(installation_id=str(uuid.uuid4()), installation_secret=secrets.token_hex(32))


def register(client, identity):
    response = client.post("/v1/auth/installation", json=identity)
    assert response.status_code == 200, response.text
    tokens = response.json()
    return tokens, {"Authorization": "Bearer " + tokens["access_token"]}


def claims(tokens):
    return jwt.decode(tokens["access_token"], options={"verify_signature": False})


def test_repeat_registration_and_expired_session_keep_ownership(client):
    identity = credentials()
    first, headers = register(client, identity)
    job = submit(client, headers).json()["job_id"]
    with SessionLocal() as db:
        db.get(Session, claims(first)["sid"]).expires_at = time.time() - 1
        db.commit()
    assert client.post("/v1/auth/refresh", json={"refresh_token": first["refresh_token"]}).status_code == 401
    second, headers = register(client, identity)
    assert claims(first)["sub"] == claims(second)["sub"]
    assert first["refresh_token"] != second["refresh_token"]
    assert client.get(f"/v1/analyses/{job}", headers=headers).status_code == 200
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(User)) == 1
        assert db.get(Installation, identity["installation_id"]).secret_hash != identity["installation_secret"]


def test_id_alone_or_wrong_secret_cannot_claim_installation(client):
    identity = credentials()
    _, headers = register(client, identity)
    assert client.post("/v1/auth/installation", json={"installation_id": identity["installation_id"]}).status_code == 422
    assert client.post("/v1/auth/installation", json={**identity, "installation_secret": secrets.token_hex(32)}).status_code == 401
    assert client.get("/v1/analyses", headers=headers).status_code == 200
    register(client, identity)


@pytest.mark.parametrize("suffix,method", [("", "get"), ("/result", "get"), ("/tracks", "get"), ("/cancel", "post"), ("/retry", "post"), ("", "delete")])
def test_installation_cannot_access_another_installations_job(client, suffix, method):
    first, owner = register(client, credentials())
    second, other = register(client, credentials())
    assert claims(first)["sub"] != claims(second)["sub"]
    job = submit(client, owner).json()["job_id"]
    assert getattr(client, method)(f"/v1/analyses/{job}{suffix}", headers={**other, "Idempotency-Key": "retry"}).status_code == 404
    assert client.get("/v1/analyses", headers=other).json()["items"] == []


def test_concurrent_registration_creates_one_owner(client):
    identity = credentials()
    with ThreadPoolExecutor(max_workers=3) as pool:
        responses = list(pool.map(lambda _: client.post("/v1/auth/installation", json=identity), range(3)))
    assert all(response.status_code == 200 for response in responses)
    assert len({claims(response.json())["sub"] for response in responses}) == 1
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(User)) == 1
        assert db.scalar(select(func.count()).select_from(Installation)) == 1


def test_revocation_blocks_access_refresh_and_registration(client):
    identity = credentials()
    tokens, headers = register(client, identity)
    with SessionLocal() as db:
        db.get(Installation, identity["installation_id"]).revoked_at = time.time()
        db.commit()
    assert client.get("/v1/analyses", headers=headers).status_code == 403
    assert client.post("/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 403
    assert client.post("/v1/auth/installation", json=identity).status_code == 403


def test_registration_rate_limit_cannot_be_bypassed_with_new_ids(client):
    for _ in range(10):
        register(client, credentials())
    assert client.post("/v1/auth/installation", json=credentials()).status_code == 429


def test_validation_does_not_echo_installation_secret(client):
    secret = "invalid-secret-never-echo"
    response = client.post("/v1/auth/installation", json={**credentials(), "installation_secret": secret})
    assert response.status_code == 422 and secret not in response.text


def test_old_account_jobs_stay_with_old_account(client, users):
    job = submit(client, users[0][1]).json()["job_id"]
    _, anonymous = register(client, credentials())
    assert client.get(f"/v1/analyses/{job}", headers=anonymous).status_code == 404
    assert client.get(f"/v1/analyses/{job}", headers=users[0][1]).status_code == 200
