"""Knows what Hinge's screens look like (from selectors.json) and how to act on them."""
import random
import re
import time
from typing import List, Tuple

from .device import Device


class HingeUI:
    def __init__(self, device: Device, selectors: dict):
        self.dev = device
        self.sel = selectors
        self.package = selectors["package"]
        self.last_read_scrolls = 0

    def detect(self, wait: float = 8.0) -> Tuple[str, str]:
        """Return (screen, detail). screen is one of:
        discover, like_sheet, popup, stop, not_hinge, unknown.
        Right after a like or skip the next profile can take several seconds to load, so
        this keeps re-reading the screen for up to `wait` seconds before giving up."""
        current = self.dev.foreground_package()
        if current != self.package:
            return "not_hinge", current
        deadline = time.time() + wait
        while True:
            nodes = self.dev.snapshot()
            sheet = self.sel["like_sheet"]
            if self.dev.exists(sheet["comment_field"], nodes) and self.dev.exists(sheet["send_button"], nodes):
                return "like_sheet", ""
            for popup in self.sel["popups_to_dismiss"]:
                if self.dev.exists(popup["marker"], nodes):
                    return "popup", popup["name"]
            for stop in self.sel["stop_screens"]:
                if self.dev.exists(stop["marker"], nodes):
                    return "stop", stop["name"]
            if self.dev.exists(self.sel["discover"]["skip_button"], nodes):
                return "discover", ""
            if time.time() >= deadline:
                return "unknown", ""
            time.sleep(0.7)

    def go_to_discover(self) -> bool:
        tab = self.dev.find(self.sel["nav"]["discover_tab"])
        if tab is None:
            return False
        self.dev.tap(tab)
        time.sleep(2)
        return True

    def dismiss_popup(self, name: str) -> None:
        popup = next(p for p in self.sel["popups_to_dismiss"] if p["name"] == name)
        button = self.dev.find(popup["dismiss"])
        if button is not None:
            self.dev.tap(button)
        else:
            self.dev.back()

    def pick_like_target(self, max_scrolls: int, prefer_prompts: bool = False):
        """Scroll a random amount down the profile and pick a random visible like button.
        With prefer_prompts, a prompt's heart wins over a photo's when both are visible
        (a comment can answer a prompt; it can't see a photo)."""
        for _ in range(random.randint(0, max_scrolls)):
            self.dev.swipe_up()
            time.sleep(random.uniform(0.8, 1.8))
        buttons = self._usable_like_buttons()
        if not buttons:
            self.dev.swipe_up()
            time.sleep(1.2)
            buttons = self._usable_like_buttons()
        if prefer_prompts:
            prompts = [b for b in buttons if "prompt" in b.info["contentDescription"].lower()]
            buttons = prompts or buttons
        return random.choice(buttons) if buttons else None

    def _usable_like_buttons(self) -> list:
        """Like buttons fully on screen and above the bottom tab bar, read once the
        scroll has settled. A heart half hidden behind the tab bar would make the tap
        land on a tab instead, and one still sliding would make it land beside the heart."""
        previous = None
        for _ in range(4):
            nodes = self.dev.snapshot()
            buttons = self.dev.match(self.sel["discover"]["like_buttons"], nodes)
            positions = [tuple(b.info["bounds"].values()) for b in buttons]
            if positions == previous:
                break
            previous = positions
            time.sleep(0.4)
        tab = self.dev.find(self.sel["nav"]["discover_tab"], nodes=nodes)
        if tab is not None:
            content_bottom = tab.info["bounds"]["top"]
        else:
            content_bottom = int(self.dev.d.window_size()[1] * 0.9)
        usable = []
        for button in buttons:
            b = button.info["bounds"]
            fully_visible = (b["bottom"] - b["top"]) >= 0.8 * (b["right"] - b["left"])
            if fully_visible and b["bottom"] <= content_bottom:
                usable.append(button)
        return usable

    def read_profile(self, max_scrolls: int, scroll_back: bool = True) -> List[str]:
        """Every piece of profile text (name, pronouns, prompts, details), read while
        scrolling to the bottom of the profile. Hinge's own buttons, filter chips and tab
        bar are left out, as is anything outside Hinge. Scrolls back to the top unless
        scroll_back is False; last_read_scrolls says how far down it went."""
        chrome = [re.compile(p) for p in self.sel["discover"]["chrome_descriptions"]]
        seen: List[str] = []
        scrolls = 0
        while True:
            new = 0
            for node in self.dev.snapshot():
                if node.get("package") != self.package or node.get("clickable") == "true":
                    continue
                for value in (node.get("text") or "", node.get("content-desc") or ""):
                    value = value.strip()
                    if value and value not in seen and not any(c.fullmatch(value) for c in chrome):
                        seen.append(value)
                        new += 1
            # Nothing new after a scroll means the end of the profile.
            if scrolls >= max_scrolls or (scrolls and not new):
                break
            self.dev.swipe_up()
            scrolls += 1
            time.sleep(random.uniform(0.5, 0.9))
        self.last_read_scrolls = scrolls
        if scroll_back:
            self.scroll_back(scrolls)
        return seen

    def scroll_back(self, swipes: int) -> None:
        for _ in range(swipes):
            self.dev.swipe_down()
            time.sleep(random.uniform(0.3, 0.6))
        time.sleep(0.5)

    def prompts(self, texts: List[str]) -> List[str]:
        """The prompt-and-answer entries among read_profile() texts."""
        return [t for t in texts if re.match(r"(?i)^prompt: ", t)]

    def find_like_for(self, item: str, max_swipes: int):
        """Scroll up from where the profile is now until the like button for `item`
        (a description from read_profile) is fully on screen. None if it never shows up."""
        patterns = self.sel["discover"]["profile_item_patterns"]
        for swipe in range(max_swipes + 1):
            buttons = self._usable_like_buttons()
            items = self.dev.described_nodes(patterns)
            for button in buttons:
                # read_profile() strips whitespace; Hinge's descriptions sometimes end in a space.
                if self.describe(button, items).strip() == item.strip():
                    return button
            if swipe < max_swipes:
                self.dev.swipe_down()
                time.sleep(random.uniform(0.4, 0.7))
        return None

    def profile_items(self) -> List[str]:
        """Descriptions of the photos and prompts currently on screen."""
        patterns = self.sel["discover"]["profile_item_patterns"]
        return [desc for desc, _ in self.dev.described_nodes(patterns)]

    def describe(self, button, items=None) -> str:
        """What a like button is attached to: the smallest photo/prompt area containing it,
        preferring the kind the button names ('Like photo' -> a photo). `items` reuses a
        described_nodes() result instead of reading the screen again."""
        info = button.info
        b = info["bounds"]
        x, y = (b["left"] + b["right"]) // 2, (b["top"] + b["bottom"]) // 2
        button_desc = info.get("contentDescription") or "like button"
        kind = button_desc.split()[-1].lower()  # 'photo', 'prompt', 'video', ...
        if items is None:
            items = self.dev.described_nodes(self.sel["discover"]["profile_item_patterns"])
        containing = [
            ((right - left) * (bottom - top), desc)
            for desc, (left, top, right, bottom) in items
            if left <= x <= right and top <= y <= bottom
        ]
        same_kind = [c for c in containing if kind in c[1].lower()]
        best = same_kind or containing
        if best:
            return min(best)[1]
        # Some hearts sit just outside their prompt's area: take the nearest of the same kind.
        nearby = [
            (min(abs(y - top), abs(y - bottom)), desc)
            for desc, (left, top, right, bottom) in items
            if kind in desc.lower()
        ]
        return min(nearby)[1] if nearby else button_desc

    def open_like(self, button) -> bool:
        self.dev.tap(button)
        return self.dev.find(self.sel["like_sheet"]["comment_field"], timeout=5) is not None

    def write_comment(self, text: str) -> None:
        field = self.dev.find(self.sel["like_sheet"]["comment_field"], timeout=3)
        if field is None:
            raise RuntimeError("Comment box not found on the like sheet")
        self.dev.type_into(field, text)
        # Never send a like whose comment didn't make it into the box. The box's text isn't
        # exposed, but Hinge renames the button to "Send like with message" once it has text.
        if self.dev.find(self.sel["like_sheet"]["comment_typed_marker"], timeout=3) is None:
            raise RuntimeError("Typed the comment but the box still looks empty; not sending")

    def send(self) -> bool:
        """Tap Send like; True once the sheet has closed. If the first tap doesn't take
        (the sheet is often still sliding as the keyboard settles), re-read where the
        button is and tap again. Only re-taps while the sheet is still open, so a like
        can't be sent twice."""
        send_button = self.sel["like_sheet"]["send_button"]
        for _ in range(3):
            button = self.dev.find(send_button, timeout=3)
            if button is None:
                return True  # sheet already closed
            self.dev.tap(button)
            deadline = time.time() + 4
            while time.time() < deadline:
                time.sleep(0.5)
                if not self.dev.exists(send_button):
                    return True
        return False

    def restart_app(self) -> None:
        """Remove Hinge from Recents (force-stop) and open it fresh.
        Only closes the app; your login and data are untouched."""
        self.dev.d.app_stop(self.package)
        time.sleep(2)
        self.dev.d.app_start(self.package)
        ready = self.sel["discover"]["skip_button"] + self.sel["nav"]["discover_tab"]
        self.dev.find(ready, timeout=25)  # wait for the feed or the tab bar to load

    def skip(self) -> None:
        button = self.dev.find(self.sel["discover"]["skip_button"], timeout=5)
        if button is None:
            raise RuntimeError("Skip button not found")
        self.dev.tap(button)
