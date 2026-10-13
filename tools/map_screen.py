"""Save what's on the emulator screen right now: layout XML and a readable summary.
(No screenshot: Hinge blocks them, they come out black.)

Read-only: it never taps anything. Open the screen you want in Hinge, then run:
    .venv/bin/python tools/map_screen.py discover            # -> captures/discover.{xml,txt}
    .venv/bin/python tools/map_screen.py like_sheet emulator-5556   # pick a device if several are running
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import uiautomator2 as u2


def main() -> None:
    label = sys.argv[1] if len(sys.argv) > 1 else "screen"
    serial = sys.argv[2] if len(sys.argv) > 2 else None
    out = Path(__file__).resolve().parent.parent / "captures"
    out.mkdir(exist_ok=True)

    d = u2.connect(serial)
    xml = d.dump_hierarchy()
    (out / f"{label}.xml").write_text(xml, encoding="utf-8")

    lines = [f"foreground: {d.app_current()}", f"window: {d.window_size()}", ""]
    for node in ET.fromstring(xml).iter("node"):
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        rid = (node.get("resource-id") or "").split("/")[-1]
        clickable = node.get("clickable") == "true"
        if not (text or desc or rid or clickable):
            continue
        cls = (node.get("class") or "").split(".")[-1]
        lines.append(
            f"{cls:<16} {'CLICK' if clickable else '     '} id={rid:<30} "
            f"text={text[:50]!r:<54} desc={desc[:50]!r:<54} {node.get('bounds')}"
        )
    (out / f"{label}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Saved captures/{label}.xml and {label}.txt ({len(lines) - 3} elements)")


if __name__ == "__main__":
    main()
