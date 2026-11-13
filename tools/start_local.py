"""Compatibility entry point. Backend lifecycle belongs to server/manage.py."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from momo.local import main  # noqa: E402

if __name__ == "__main__":
    arguments = sys.argv[1:]
    action = "stop" if "--stop" in arguments else "status" if "--status" in arguments else "run"
    raise SystemExit(main([action] + [arg for arg in arguments if arg not in {"--stop", "--status"}]))
