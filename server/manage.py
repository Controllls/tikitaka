"""Independent backend entry point; no Android SDK or root working directory required."""
from momo.local import main

if __name__ == "__main__":
    raise SystemExit(main())
