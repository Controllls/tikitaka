import os
from pathlib import Path
import subprocess
import sys

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import Session
from momo.db import Job, User


def test_installation_migration_preserves_existing_user_and_job(tmp_path):
    url = f"sqlite:///{(tmp_path / 'upgrade.db').as_posix()}"
    environment = {**os.environ, "MOMO_DATABASE_URL": url}
    server = Path(__file__).resolve().parents[1]

    def upgrade(revision):
        result = subprocess.run([sys.executable, "-m", "alembic", "upgrade", revision], cwd=server,
                                env=environment, capture_output=True, text=True, timeout=30)
        assert result.returncode == 0, result.stderr

    upgrade("0001_momo")
    engine = create_engine(url)
    try:
        with Session(engine) as db:
            user = User(username="existing-user", password_hash="preserved-hash")
            db.add(user)
            db.flush()
            job = Job(owner_id=user.id, options={}, model_lock_id="fixture", audio_sha256="a" * 64,
                      source_key="sources/preserved.audio")
            db.add(job)
            db.commit()
            user_id, job_id = user.id, job.id
        assert "installations" not in inspect(engine).get_table_names()
        upgrade("head")
        with Session(engine) as db:
            assert db.get(User, user_id).password_hash == "preserved-hash"
            assert db.get(Job, job_id).owner_id == user_id
            assert db.get(Job, job_id).source_key == "sources/preserved.audio"
            assert db.execute(text("SELECT COUNT(*) FROM installations")).scalar() == 0
            assert db.execute(text("SELECT version_num FROM alembic_version")).scalar() == "0004_phone_companion"
            for table in ("phone_pairs", "phone_devices", "phone_episodes", "phone_jobs"):
                assert db.execute(text(f"SELECT COUNT(*) FROM {table}")).scalar() == 0
    finally:
        engine.dispose()
