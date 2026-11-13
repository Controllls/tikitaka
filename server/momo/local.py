"""Manage a loopback-only development backend independently of the Android client."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

import psutil

SERVER = Path(__file__).resolve().parents[1]


def read_state(directory):
    try:
        state = json.loads((directory / "local-processes.json").read_text(encoding="utf-8"))
        return {"port": 8765, "children": state} if isinstance(state, list) else state
    except (FileNotFoundError, ValueError):
        return {}


def write_state(directory, state):
    pending = directory / f"local-processes-{os.getpid()}.tmp"
    pending.write_text(json.dumps(state), encoding="utf-8")
    pending.replace(directory / "local-processes.json")


def record(process, name):
    return {"pid": process.pid, "created": psutil.Process(process.pid).create_time(), "name": name}


def owned_process(item):
    try:
        process = psutil.Process(item["pid"])
        if abs(process.create_time() - item["created"]) < .01 and process.is_running():
            return process
    except (psutil.NoSuchProcess, psutil.AccessDenied, KeyError):
        pass
    return None


def records(state):
    return state.get("children", []) + ([state["supervisor"]] if state.get("supervisor") else [])


def active(state):
    return any(owned_process(item) for item in records(state))


def probe(port, route):
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(f"http://127.0.0.1:{port}{route}", timeout=2) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as exc:
        return exc.code, {}
    except (OSError, ValueError):
        return None, {}


def status(directory, default_port=8765):
    state = read_state(directory)
    port = state.get("port", default_port)
    processes = [{"name": item.get("name", "legacy"), "running": owned_process(item) is not None}
                 for item in records(state)]
    live, _ = probe(port, "/health/live")
    ready, body = probe(port, "/health/ready")
    running = active(state)
    return {
        "status": "ready" if running and ready == 200 else "starting_or_unavailable" if running else "stopped",
        "api_url": f"http://127.0.0.1:{port}/", "emulator_url": f"http://10.0.2.2:{port}/",
        "api_live": live == 200, "analysis_ready": running and ready == 200,
        "data_origin": body.get("data_origin"), "processes": processes,
    }


@contextmanager
def control_lock(directory):
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "local-control.lock").open("a+b") as stream:
        stream.seek(0)
        stream.write(b"0")
        stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def stop(state):
    processes = {}
    for item in records(state):
        process = owned_process(item)
        if process and process.pid != os.getpid():
            try:
                for child in process.children(recursive=True):
                    processes[child.pid] = child
                processes[process.pid] = process
            except psutil.NoSuchProcess:
                pass
    # Supervisor is last in records: stop it first to prevent respawning children.
    for process in reversed(list(processes.values())):
        try:
            process.terminate()
        except psutil.NoSuchProcess:
            pass
    _, remaining = psutil.wait_procs(list(processes.values()), timeout=5)
    for process in remaining:
        try:
            process.kill()
        except psutil.NoSuchProcess:
            pass
    psutil.wait_procs(remaining, timeout=5)


def port_available(port):
    with socket.socket() as listener:
        try:
            listener.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def configure(args):
    """Preserve local accounts and data; never import Android or invoke adb/Gradle."""
    local = args.data_dir / "local-development.json"
    if local.exists():
        config = json.loads(local.read_text(encoding="utf-8"))
    else:
        config = dict(jwt_secret=secrets.token_hex(32))
        with local.open("x", encoding="utf-8") as stream:
            json.dump(config, stream, indent=2)
        local.chmod(0o600)
    os.environ.update(
        MOMO_PROFILE="fixture" if args.fixture else "development", MOMO_LOCAL_DISPATCH="true",
        MOMO_INFERENCE_DEVICE=args.device, MOMO_LAB_DEVICE="gpu" if args.device == "cuda" else "cpu", MOMO_JWT_SECRET=config["jwt_secret"],
        MOMO_DATABASE_URL=f"sqlite:///{(args.data_dir / 'momo.db').as_posix()}",
        MOMO_STORAGE_ROOT=str(args.data_dir / "objects"), MOMO_MODEL_ROOT=str(args.models),
        PYTHONIOENCODING="utf-8", TF_CPP_MIN_LOG_LEVEL="2",
    )
    if args.cuda_root:
        os.environ["MOMO_CUDA_LIBRARY_ROOT"] = str(args.cuda_root)
    if args.work_dir:
        os.environ["MOMO_EPISODE_WORK_ROOT"] = str(args.work_dir)
        upload_temp = args.work_dir / "_multipart"
        upload_temp.mkdir(parents=True, exist_ok=True)
        os.environ.update(TEMP=str(upload_temp), TMP=str(upload_temp), TMPDIR=str(upload_temp))
    from .db import Base, engine
    Base.metadata.create_all(engine)


def supervise(args):
    state = {"port": args.port, "supervisor": record(psutil.Process(), "supervisor"), "children": []}
    processes = []
    commands = [
        ("api", [sys.executable, "-m", "uvicorn", "momo.api:app", "--host", "127.0.0.1", "--port", str(args.port)]),
        ("worker", [sys.executable, "-m", "momo.worker", "local"]),
        ("maintenance", [sys.executable, "-m", "momo.maintenance"]),
    ]

    def launch(name, command):
        with (args.data_dir / f"{name}.log").open("ab") as log:
            return subprocess.Popen(command, cwd=SERVER, stdout=log, stderr=subprocess.STDOUT,
                                    stdin=subprocess.DEVNULL)

    try:
        with control_lock(args.data_dir):
            existing = read_state(args.data_dir)
            owner = owned_process(existing.get("supervisor", {}))
            own_ancestors = {os.getpid(), *(parent.pid for parent in psutil.Process().parents())}
            if active(existing) and (owner is None or owner.pid not in own_ancestors):
                raise RuntimeError("Backend already running; use status or stop first.")
            if not port_available(args.port):
                raise RuntimeError(f"Port {args.port} is already in use; no process was stopped.")
            write_state(args.data_dir, state)
        configure(args)
        for name, command in commands:
            process = launch(name, command)
            processes.append(process)
            state["children"].append(record(process, name))
            write_state(args.data_dir, state)
        print(f"Backend API: http://127.0.0.1:{args.port}/; analysis models are loading.", flush=True)
        while processes[0].poll() is None:
            time.sleep(2)
            for index in (1, 2):
                if processes[index].poll() is not None:
                    processes[index] = launch(*commands[index])
                    state["children"][index] = record(processes[index], commands[index][0])
                    write_state(args.data_dir, state)
        return 1
    finally:
        stop({"children": state["children"]})
        if read_state(args.data_dir).get("supervisor", {}).get("pid") == os.getpid():
            (args.data_dir / "local-processes.json").unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "run", "status", "stop"])
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--device", choices=["cpu", "cuda"], default="cuda")
    parser.add_argument("--cuda-root", type=Path)
    parser.add_argument("--models", type=Path, default=SERVER / "data/models")
    parser.add_argument("--data-dir", type=Path, default=SERVER / "data")
    parser.add_argument("--work-dir", type=Path, help="Episode PCM/separation/render scratch directory; durable data stays in --data-dir")
    parser.add_argument("--fixture", action="store_true", help="Contract tests only; never real inference")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    args.data_dir = args.data_dir.resolve()
    args.models = args.models.resolve()
    if args.work_dir:
        args.work_dir = args.work_dir.resolve()
    if args.cuda_root:
        args.cuda_root = args.cuda_root.resolve()
    if args.action == "status":
        report = status(args.data_dir, args.port)
        print(json.dumps(report, indent=2))
        return 0 if report["status"] == "ready" else 1
    if args.action == "run":
        try:
            return supervise(args)
        except KeyboardInterrupt:
            return 0
    with control_lock(args.data_dir):
        existing = read_state(args.data_dir)
        if args.action == "stop":
            stop(existing)
            (args.data_dir / "local-processes.json").unlink(missing_ok=True)
            print("Local backend stopped. Recordings, accounts and analysis results were preserved.")
            return 0
        if active(existing):
            print("Backend already running. Use status to check analysis readiness.")
            return 0
        if not port_available(args.port):
            parser.error(f"Port {args.port} is already in use; no process was stopped.")
        command = [sys.executable, str(SERVER / "manage.py"), "run", "--port", str(args.port),
                   "--device", args.device, "--models", str(args.models), "--data-dir", str(args.data_dir)]
        if args.cuda_root:
            command += ["--cuda-root", str(args.cuda_root)]
        if args.work_dir:
            command += ["--work-dir", str(args.work_dir)]
        if args.fixture:
            command += ["--fixture"]
        with (args.data_dir / "supervisor.log").open("ab") as log:
            kwargs = {"creationflags": subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP} \
                if os.name == "nt" else {"start_new_session": True}
            process = subprocess.Popen(command, cwd=SERVER, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, **kwargs)
        write_state(args.data_dir, {"port": args.port, "supervisor": record(process, "supervisor"), "children": []})
    print(f"Backend started independently on port {args.port}. Run status to check model readiness.")
    return 0
