"""Exercise real backend process isolation without touching development accounts or audio."""
import json
from pathlib import Path
import socket
import subprocess
import sys
import time

import psutil
import pytest

from momo.local import owned_process, read_state, records

MANAGE = Path(__file__).resolve().parents[1] / "manage.py"


def command(action, directory, port):
    return subprocess.run(
        [sys.executable, str(MANAGE), action, "--data-dir", str(directory), "--port", str(port), "--fixture", "--work-dir", str(directory / "scratch")],
        cwd=directory.parent, capture_output=True, text=True, timeout=20,
    )


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_ready(directory, port):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        report = command("status", directory, port)
        if report.returncode == 0:
            return json.loads(report.stdout)
        time.sleep(.2)
    pytest.fail("Backend did not become ready; " + (directory / "supervisor.log").read_text())


def test_standalone_start_stop_restart_preserves_data(tmp_path):
    directory = tmp_path / "backend-data"
    port = available_port()
    try:
        started = command("start", directory, port)
        assert started.returncode == 0, started.stderr
        report = wait_ready(directory, port)
        assert report["analysis_ready"] and report["data_origin"] == "fixture"
        first = read_state(directory)
        duplicate = command("start", directory, port)
        assert duplicate.returncode == 0
        assert read_state(directory)["supervisor"] == first["supervisor"]
        credentials = (directory / "local-development.json").read_bytes()
        assert command("stop", directory, port).returncode == 0
        assert all(owned_process(item) is None for item in records(first))
        report = json.loads(command("status", directory, port).stdout)
        assert report["status"] == "stopped" and not report["api_live"]
        assert (directory / "momo.db").exists()
        assert command("start", directory, port).returncode == 0
        wait_ready(directory, port)
        assert (directory / "local-development.json").read_bytes() == credentials
    finally:
        command("stop", directory, port)


def test_occupied_port_does_not_start_or_stop_unrelated_service(tmp_path):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        result = command("start", tmp_path / "occupied", port)
        assert result.returncode != 0 and "already in use" in result.stderr
        assert not (tmp_path / "occupied/local-processes.json").exists()
        assert listener.fileno() != -1


def test_reused_pid_is_not_owned():
    current = psutil.Process()
    assert owned_process({"pid": current.pid, "created": current.create_time() - 100}) is None


def test_legacy_state_can_be_read_without_credentials(tmp_path):
    old = [{"pid": 123, "created": 0}]
    (tmp_path / "local-processes.json").write_text(json.dumps(old))
    assert read_state(tmp_path) == {"port": 8765, "children": old}
