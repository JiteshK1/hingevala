"""Read-only health check: is the emulator reachable, is Hinge open, do the selectors match?

    .venv/bin/python tools/doctor.py
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from hinge.device import Device  # noqa: E402
from hinge.ui import HingeUI  # noqa: E402


def main() -> None:
    config = json.loads((ROOT / "config.json").read_text(encoding="utf-8"))
    selectors = json.loads((ROOT / "selectors.json").read_text(encoding="utf-8"))
    dev = Device(config.get("device_serial", ""))
    ui = HingeUI(dev, selectors)

    print("Foreground app:     ", dev.foreground_package())
    print("Detected screen:    ", ui.detect())
    print("Like buttons visible:", len(dev.find_all(selectors["discover"]["like_buttons"])))
    print("Skip button found:  ", dev.exists(selectors["discover"]["skip_button"]))
    print("Comment box found:  ", dev.exists(selectors["like_sheet"]["comment_field"]))
    print("Send button found:  ", dev.exists(selectors["like_sheet"]["send_button"]))


if __name__ == "__main__":
    main()
