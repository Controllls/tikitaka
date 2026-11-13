"""Stop the topic subprocess even if the inference worker is hard-killed by its lease watchdog."""
import os
import subprocess
import sys
import time
import psutil


def main():
    parent_pid, parent_created = int(sys.argv[1]), float(sys.argv[2])
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    child = subprocess.Popen(sys.argv[3:], creationflags=flags, stdin=subprocess.DEVNULL)
    try:
        while child.poll() is None:
            try:
                if abs(psutil.Process(parent_pid).create_time() - parent_created) > .01:
                    break
            except psutil.NoSuchProcess:
                break
            time.sleep(.5)
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()


if __name__ == "__main__":
    main()
