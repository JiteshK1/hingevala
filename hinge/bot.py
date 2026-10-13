"""The background loop: read the screen, decide, act, wait. Runs until Stop is pressed."""
import json
import random
import threading
import time
import traceback
import uuid
from collections import deque
from datetime import date, datetime
from pathlib import Path
from typing import Optional, Tuple

from .comments import CommentPicker

# Used for any setting missing from config.json, so an older or hand-edited file still works.
DEFAULT_CONFIG = {
    "device_serial": "",
    "mode": "approve",
    "dry_run": True,
    "daily_cap": 30,
    "delay_seconds": [30, 120],
    "dry_run_delay_seconds": [5, 10],
    "skip_probability": 0.25,
    "active_hours": [8, 24],
    "max_scrolls": 3,
    "comment_no_repeat": 10,
    "approval_timeout_seconds": 600,
    "max_restarts_in_a_row": 3,
}


class Bot:
    def __init__(self, root: Path):
        self.root = root
        self.data = root / "data"
        self.data.mkdir(exist_ok=True)
        self.config_path = root / "config.json"
        self.counter_path = self.data / "state.json"
        self.log_path = self.data / "bot.log"
        self.config = self._load_config()

        self.state = "stopped"
        self.pending: Optional[dict] = None
        self.next_action_at: Optional[float] = None
        self.dry_runs = 0
        self._failures = 0
        self.log_lines = deque(maxlen=300)

        self._stop = threading.Event()
        self._decision_ready = threading.Event()
        self._decision: Optional[Tuple[str, Optional[str]]] = None
        self._thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

    # ---- used by the control page -------------------------------------------------

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> bool:
        with self._lock:
            if self.running:
                return False
            self.config = self._load_config()
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, daemon=True)
            self._thread.start()
            return True

    def stop(self) -> None:
        self._stop.set()
        self._decision_ready.set()
        if self.running:
            self.state = "stopping…"

    def decide(self, pending_id: str, action: str, comment: Optional[str] = None) -> bool:
        pending = self.pending
        if pending is None or pending["id"] != pending_id or action not in ("send", "skip"):
            return False
        self._decision = (action, comment)
        self._decision_ready.set()
        return True

    def update_options(self, **options) -> None:
        if not self.running:
            # Pick up edits made to config.json by hand since the last start.
            self.config = self._load_config()
        for key, value in options.items():
            if value is not None:
                self.config[key] = value
        self.config_path.write_text(json.dumps(self.config, indent=2) + "\n", encoding="utf-8")

    def status(self) -> dict:
        next_in = None
        if self.next_action_at is not None:
            next_in = max(0, int(self.next_action_at - time.time()))
        return {
            "running": self.running,
            "state": self.state,
            "mode": self.config["mode"],
            "dry_run": self.config["dry_run"],
            "daily_cap": self.config["daily_cap"],
            "sent_today": self._sent_today(),
            "dry_runs": self.dry_runs,
            "next_action_in": next_in,
            "pending": self.pending,
            "log": list(self.log_lines)[-150:],
        }

    # ---- the loop -------------------------------------------------------------------

    def _run(self) -> None:
        try:
            # Imported here so the control page still starts when no device is attached.
            from .device import Device
            from .ui import HingeUI

            self.state = "connecting to emulator"
            dev = Device(self.config.get("device_serial", ""))
            ui = HingeUI(dev, self._load_json(self.root / "selectors.json"))
            comments = CommentPicker(self.root / "comments.txt", self.config["comment_no_repeat"])
            self._log(
                f"Started (mode={self.config['mode']}, dry_run={self.config['dry_run']}, "
                f"daily_cap={self.config['daily_cap']})"
            )

            tried_discover_tab = False
            restarts = 0
            self._failures = 0
            told_outside_hours = False
            while not self._stop.is_set():
                if not self._in_active_hours():
                    if not told_outside_hours:
                        start, end = self.config["active_hours"]
                        self._log(
                            f"Outside active hours ({start}:00–{end}:00 in config.json); "
                            f"waiting until {start}:00. Change active_hours to run now."
                        )
                        told_outside_hours = True
                    self.state = "waiting for active hours"
                    self._stop.wait(60)
                    continue
                told_outside_hours = False
                cap = self.config["daily_cap"]  # 0 means no limit
                if cap and not self.config["dry_run"] and self._sent_today() >= cap:
                    self._log("Daily cap reached.")
                    break

                # Anything that sets `problem` is fixed by removing Hinge from Recents and
                # opening it fresh. Things a restart can't fix (cap, verification, paywall) break.
                problem = None
                try:
                    screen, detail = ui.detect()
                    if screen in ("stop", "unknown") and not tried_discover_tab and ui.go_to_discover():
                        # Probably just on another tab (e.g. Matches shows an upgrade offer).
                        tried_discover_tab = True
                        self._log("Not on the Discover feed; tapped the Discover tab.")
                        continue
                    if screen == "discover":
                        tried_discover_tab = False
                        self._handle_profile(ui, dev, comments)
                        if self._failures >= 3:
                            self._failures = 0
                            problem = "3 failed attempts in a row."
                        else:
                            restarts = 0
                            self._pause()
                    elif screen == "popup":
                        self._log(f"Closing the '{detail}' popup.")
                        ui.dismiss_popup(detail)
                        self._stop.wait(2)
                    elif screen == "like_sheet":
                        # Closing it unsent needs Back, and an extra Back leaves Hinge.
                        problem = "A comment box was left open."
                    elif screen == "not_hinge":
                        problem = f"Hinge isn't on screen (showing {detail or 'nothing'})."
                    elif screen == "stop":
                        self._log(f"Stopping: saw the '{detail}' screen.")
                        break
                    else:
                        capture = self.data / f"unknown-{datetime.now():%Y%m%d-%H%M%S}.xml"
                        capture.write_text(dev.dump(), encoding="utf-8")
                        problem = f"Didn't recognise this screen (saved {capture.name})."
                except Exception as exc:
                    with self.log_path.open("a", encoding="utf-8") as f:
                        f.write(traceback.format_exc())
                    problem = f"Error: {exc}."

                if problem is None or self._stop.is_set():
                    continue
                limit = self.config["max_restarts_in_a_row"]
                if restarts >= limit:
                    self._log(f"{problem} Already restarted Hinge {restarts} times in a row; stopping.")
                    break
                restarts += 1
                tried_discover_tab = False
                self.state = "restarting Hinge"
                self._log(f"{problem} Closing Hinge and opening it fresh ({restarts}/{limit}).")
                ui.restart_app()
        except Exception as exc:
            self._log(f"Error: {exc}")
            with self.log_path.open("a", encoding="utf-8") as f:
                f.write(traceback.format_exc())
        finally:
            self.pending = None
            self.next_action_at = None
            self.state = "stopped"
            self._log("Stopped.")

    def _handle_profile(self, ui, dev, comments: CommentPicker) -> None:
        cfg = self.config
        dry = cfg["dry_run"]

        if random.random() < cfg["skip_probability"]:
            self._skip(ui, dry, "random skip")
            return

        self.state = "looking at a profile"
        target = ui.pick_like_target(cfg["max_scrolls"])
        if target is None:
            self._log("No like button found on this profile.")
            self._skip(ui, dry, "no like button")
            return
        label = ui.describe(target)
        comment = comments.pick()

        if cfg["mode"] == "approve":
            decision = self._ask_approval(ui, label, comment)
            if decision is None:
                return
            action, comment = decision
            if action == "skip":
                self._skip(ui, dry, "you skipped")
                return

        if dry:
            # Stop before opening the comment box: closing it unsent needs Back presses,
            # which can leave Hinge entirely.
            self.dry_runs += 1
            self._log(f"[dry run] Would like '{label}' with “{comment}”")
            return

        self.state = "writing comment"
        if not ui.open_like(target):
            self._failures += 1
            self._log("The like sheet didn't open; trying again next round.")
            return
        self._failures = 0
        ui.write_comment(comment)

        if ui.send():
            self._log(f"Sent like #{self._bump_sent()} on '{label}': “{comment}”")
        else:
            raise RuntimeError("Tapped Send, but the comment box stayed open")

    def _ask_approval(self, ui, label: str, comment: str) -> Optional[Tuple[str, str]]:
        # Hinge blocks screenshots, so the approval card shows the profile as text.
        self._decision = None
        self._decision_ready.clear()
        self.pending = {
            "id": uuid.uuid4().hex,
            "target": label,
            "comment": comment,
            "profile_items": ui.profile_items(),
        }
        self.state = "waiting for your approval"
        answered = self._decision_ready.wait(self.config["approval_timeout_seconds"])
        self.pending = None

        if self._stop.is_set():
            return None
        if not answered or self._decision is None:
            self._log("No answer in time. Stopping.")
            self._stop.set()
            return None
        action, edited = self._decision
        return action, (edited.strip() if edited and edited.strip() else comment)

    def _skip(self, ui, dry: bool, reason: str) -> None:
        if dry:
            self._log(f"[dry run] Would skip ({reason}).")
        else:
            ui.skip()
            self._log(f"Skipped ({reason}).")

    def _pause(self) -> None:
        low, high = self.config["dry_run_delay_seconds" if self.config["dry_run"] else "delay_seconds"]
        seconds = random.uniform(low, high)
        self.next_action_at = time.time() + seconds
        self.state = "waiting"
        self._stop.wait(seconds)
        self.next_action_at = None

    # ---- helpers --------------------------------------------------------------------

    def _in_active_hours(self) -> bool:
        start, end = self.config["active_hours"]
        hour = datetime.now().hour
        return start <= hour < end if start <= end else (hour >= start or hour < end)

    def _sent_today(self) -> int:
        if not self.counter_path.exists():
            return 0
        saved = self._load_json(self.counter_path)
        return saved["sent"] if saved.get("date") == date.today().isoformat() else 0

    def _bump_sent(self) -> int:
        sent = self._sent_today() + 1
        self.counter_path.write_text(
            json.dumps({"date": date.today().isoformat(), "sent": sent}), encoding="utf-8"
        )
        return sent

    def _log(self, message: str) -> None:
        line = f"{datetime.now():%H:%M:%S}  {message}"
        self.log_lines.append(line)
        with self.log_path.open("a", encoding="utf-8") as f:
            f.write(f"{datetime.now():%Y-%m-%d} {line}\n")

    def _load_config(self) -> dict:
        return {**DEFAULT_CONFIG, **self._load_json(self.config_path)}

    @staticmethod
    def _load_json(path: Path) -> dict:
        return json.loads(path.read_text(encoding="utf-8"))
