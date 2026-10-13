"""Thin wrapper around uiautomator2: selector lists, human-like taps and swipes.

Elements are found by dumping the screen layout once and matching selectors against it
locally. That is one round trip to the device instead of one per selector, and it can't
hit the StaleObjectException / UiObjectNotFoundException races of live UiObjects."""
import random
import re
import time
import xml.etree.ElementTree as ET
from typing import List, Optional, Tuple

import uiautomator2 as u2

# uiautomator2 selector key -> (layout attribute, how to compare)
_SELECTOR_KEYS = {
    "description": ("content-desc", "equals"),
    "descriptionContains": ("content-desc", "contains"),
    "descriptionMatches": ("content-desc", "matches"),
    "descriptionStartsWith": ("content-desc", "startswith"),
    "text": ("text", "equals"),
    "textContains": ("text", "contains"),
    "textMatches": ("text", "matches"),
    "textStartsWith": ("text", "startswith"),
    "className": ("class", "equals"),
    "classNameMatches": ("class", "matches"),
    "resourceId": ("resource-id", "equals"),
    "resourceIdMatches": ("resource-id", "matches"),
    "packageName": ("package", "equals"),
}
_regex_cache = {}


def _matches(node, selector: dict) -> bool:
    for key, wanted in selector.items():
        if key not in _SELECTOR_KEYS:
            raise ValueError(f"Unsupported selector key in selectors.json: {key}")
        attr, how = _SELECTOR_KEYS[key]
        value = node.get(attr) or ""
        if how == "equals" and value != wanted:
            return False
        if how == "contains" and wanted not in value:
            return False
        if how == "startswith" and not value.startswith(wanted):
            return False
        if how == "matches":
            # *Matches selectors are Java regexes that must match the whole string.
            pattern = _regex_cache.get(wanted) or _regex_cache.setdefault(wanted, re.compile(wanted))
            if not pattern.fullmatch(value):
                return False
    return True


def _bounds(node) -> Tuple[int, int, int, int]:
    left, top, right, bottom = map(int, re.findall(r"-?\d+", node.get("bounds", "")))
    return left, top, right, bottom


class Element:
    """A node from a layout dump. Exposes .info like a uiautomator2 object does."""

    def __init__(self, node):
        left, top, right, bottom = _bounds(node)
        self.info = {
            "bounds": {"left": left, "top": top, "right": right, "bottom": bottom},
            "contentDescription": node.get("content-desc") or "",
            "text": node.get("text") or "",
        }


class Device:
    def __init__(self, serial: str = ""):
        self.d = u2.connect(serial or None)

    # ---- reading the screen ------------------------------------------------------

    def dump(self) -> str:
        """Screen layout as XML. (Hinge blocks screenshots, so this is how we 'see'.)"""
        return self.d.dump_hierarchy()

    def snapshot(self) -> list:
        """Every node on screen right now."""
        return list(ET.fromstring(self.dump()).iter("node"))

    @staticmethod
    def match(selectors: List[dict], nodes: list) -> list:
        """Elements matching the first selector in the list that matches anything."""
        for sel in selectors:
            found = [Element(n) for n in nodes if _matches(n, sel)]
            if found:
                return found
        return []

    def find(self, selectors: List[dict], timeout: float = 0.0, nodes: Optional[list] = None):
        """Return the first element matching any selector in the list, or None.
        With `nodes`, looks only in that snapshot (and ignores timeout)."""
        if nodes is not None:
            found = self.match(selectors, nodes)
            return found[0] if found else None
        deadline = time.time() + timeout
        while True:
            found = self.match(selectors, self.snapshot())
            if found:
                return found[0]
            if time.time() >= deadline:
                return None
            time.sleep(0.3)

    def find_all(self, selectors: List[dict]) -> list:
        return self.match(selectors, self.snapshot())

    def exists(self, selectors: List[dict], nodes: Optional[list] = None) -> bool:
        return self.find(selectors, nodes=nodes) is not None

    # ---- acting ------------------------------------------------------------------

    def tap(self, obj) -> None:
        """Tap a random point near the middle of the element, not the exact centre."""
        b = obj.info["bounds"]
        x = b["left"] + (b["right"] - b["left"]) * random.uniform(0.3, 0.7)
        y = b["top"] + (b["bottom"] - b["top"]) * random.uniform(0.3, 0.7)
        self.d.click(int(x), int(y))

    def type_into(self, obj, text: str) -> None:
        """Type into whatever the keyboard is attached to. Hinge's comment box isn't a
        real text field, so set_text() can't reach it; send_keys() pastes into the
        focused input, falling back to uiautomator2's own keyboard."""
        self.tap(obj)
        time.sleep(random.uniform(0.6, 1.0))
        self.d.send_keys(text)
        time.sleep(0.6)

    def swipe_up(self) -> None:
        w, h = self.d.window_size()
        x = w * random.uniform(0.4, 0.6)
        self.d.swipe(
            x, h * random.uniform(0.70, 0.78),
            x + random.uniform(-20, 20), h * random.uniform(0.30, 0.38),
            duration=random.uniform(0.2, 0.4),
        )

    def back(self) -> None:
        self.d.press("back")

    def foreground_package(self) -> str:
        return self.d.app_current().get("package", "")

    def described_nodes(self, patterns: List[str]) -> List[Tuple[str, Tuple[int, int, int, int]]]:
        """(content-desc, bounds) for every on-screen node whose description matches a pattern."""
        compiled = [re.compile(p, re.IGNORECASE) for p in patterns]
        nodes = []
        for node in self.snapshot():
            desc = node.get("content-desc") or ""
            if desc and any(c.search(desc) for c in compiled):
                nodes.append((desc, _bounds(node)))
        return nodes
