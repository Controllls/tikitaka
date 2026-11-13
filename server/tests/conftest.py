import os
import secrets
import tempfile
from pathlib import Path

_temp = tempfile.TemporaryDirectory(prefix="momo-tests-")
os.environ.update(MOMO_PROFILE="fixture", MOMO_LOCAL_DISPATCH="true", MOMO_JWT_SECRET=secrets.token_hex(32),
                  MOMO_DATABASE_URL=f"sqlite:///{Path(_temp.name).as_posix()}/test.db",
                  MOMO_STORAGE_ROOT=str(Path(_temp.name)/"objects"))
import pytest
from fastapi.testclient import TestClient
from momo.api import app
from momo.db import Base, engine, SessionLocal, User
from momo.auth import passwords


@pytest.fixture(autouse=True)
def database():
    from momo.api import _login_attempts
    _login_attempts.clear()
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    yield
    engine.dispose()


@pytest.fixture
def client():
    with TestClient(app) as client:
        yield client


@pytest.fixture
def users(client):
    result = []
    for index in range(2):
        password = secrets.token_urlsafe(20)
        with SessionLocal() as db:
            user = User(username=f"tester{index}", password_hash=passwords.hash(password))
            db.add(user)
            db.commit()
            user_id = user.id
        tokens = client.post("/v1/auth/login", json={"username": f"tester{index}", "password": password}).json()
        result.append((user_id, {"Authorization": "Bearer "+tokens["access_token"]}, tokens))
    return result
