"""The background loop: read the screen, decide, act, wait. Runs until Stop is pressed."""
import json
import random
import re
import threading
import time
import traceback
import uuid
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from pathlib import Path
from typing import List, Optional, Tuple

from . import emulator
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
    "skip_keywords": [],
    "profile_read_scrolls": 8,
    "auto_restart_emulator": True,
    "avd_name": "",
    "emulator_boot_timeout_seconds": 300,
    "max_emulator_restarts_per_hour": 5,
    "ai_comments": False,
    "ai_model": "claude-opus-5-5",
    "ai_style": "",
    "ai_prefer_prompts": True,
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
        self._serial = ""
        self._avd = ""
        self._emulator_restarts: List[float] = []
        self._ai = None
        self._ai_paused_until = 0.0
        self._ai_pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="ai-comment")
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
            self.state = "connecting to emulator"
            self._serial = self.config["device_serial"] or emulator.first_emulator_serial()
            self._avd = self.config["avd_name"] or emulator.avd_name(self._serial)
            if not emulator.healthy(self._serial):
                if not self._restart_emulator("The emulator isn't running."):
                    return
            dev, ui = self._connect()
            comments = CommentPicker(self.root / "comments.txt", self.config["comment_no_repeat"])
            self._ai = self._make_ai_commenter()
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
                if self.config["auto_restart_emulator"] and not emulator.healthy(self._serial):
                    # Reopening Hinge can't help when the emulator itself crashed or froze.
                    if not self._restart_emulator(f"{problem} The emulator isn't answering."):
                        break
                    dev, ui = self._connect()
                    restarts = 0
                    tried_discover_tab = False
                    self._log("Opening Hinge.")
                    try:
                        ui.restart_app()
                    except Exception as exc:
                        self._log(f"Couldn't open Hinge: {exc}.")
                    continue
                limit = self.config["max_restarts_in_a_row"]
                if restarts >= limit:
                    self._log(f"{problem} Already restarted Hinge {restarts} times in a row; stopping.")
                    break
                restarts += 1
                tried_discover_tab = False
                self.state = "restarting Hinge"
                self._log(f"{problem} Closing Hinge and opening it fresh ({restarts}/{limit}).")
                try:
                    ui.restart_app()
                except Exception as exc:
                    # Usually the emulator going away mid-restart; the next round finds out.
                    self._log(f"Couldn't reopen Hinge: {exc}.")
        except Exception as exc:
            self._log(f"Error: {exc}")
            with self.log_path.open("a", encoding="utf-8") as f:
                f.write(traceback.format_exc())
        finally:
            self.pending = None
            self.next_action_at = None
            self.state = "stopped"
            self._log("Stopped.")

    def _connect(self):
        """Fresh connection to the emulator. Right after a boot uiautomator2's helper
        may still be starting, so this retries for a while."""
        # Imported here so the control page still starts when no device is attached.
        from .device import Device
        from .ui import HingeUI

        for attempt in range(6):
            try:
                dev = Device(self._serial)
                dev.foreground_package()
                return dev, HingeUI(dev, self._load_json(self.root / "selectors.json"))
            except Exception:
                if attempt == 5 or self._stop.is_set():
                    raise
                self._stop.wait(10)

    def _restart_emulator(self, problem: str) -> bool:
        """Close the emulator (force-killing it if it's hung) and cold-boot it again,
        as you would with Android Studio's "Cold Boot Now". True once it's back up."""
        cfg = self.config
        if not cfg["auto_restart_emulator"]:
            self._log(f"{problem} auto_restart_emulator is off; stopping.")
            return False
        avd = self._avd or emulator.last_used_avd()
        if not avd:
            self._log(f"{problem} Don't know which emulator to start; set avd_name in config.json.")
            return False
        self._avd = avd
        hour_ago = time.time() - 3600
        self._emulator_restarts = [t for t in self._emulator_restarts if t > hour_ago]
        limit = cfg["max_emulator_restarts_per_hour"]
        if len(self._emulator_restarts) >= limit:
            self._log(f"{problem} Already restarted the emulator {limit} times this hour; stopping.")
            return False
        self._emulator_restarts.append(time.time())
        self.state = "restarting the emulator"
        self._log(
            f"{problem} Closing the emulator and cold-booting {avd} "
            f"({len(self._emulator_restarts)}/{limit} this hour)."
        )
        emulator.kill(self._serial, avd, self._log)
        if self._stop.is_set():
            return False
        return emulator.start(
            self._serial, avd, cfg["emulator_boot_timeout_seconds"], self._log,
            self.data / "emulator.log", stop=self._stop,
        )

    def _handle_profile(self, ui, dev, comments: CommentPicker) -> None:
        cfg = self.config
        dry = cfg["dry_run"]

        if random.random() < cfg["skip_probability"]:
            self._skip(ui, dry, "random skip")
            return

        patterns = self._keyword_patterns()
        texts: List[str] = []
        # With AI comments, stay at the bottom after reading: the bot picks a prompt
        # there, starts the comment, and scrolls back up to that prompt's heart while
        # Claude writes it.
        plan_ahead = self._ai is not None and cfg["ai_prefer_prompts"]
        if patterns or self._ai is not None:
            self.state = "reading the profile"
            texts = ui.read_profile(cfg["profile_read_scrolls"], scroll_back=not plan_ahead)
            hit = self._find_keyword(patterns, texts)
            if hit:
                self._skip(ui, dry, f"profile mentions '{hit}'")
                return

        self.state = "looking at a profile"
        target, label, pending = None, "", None
        prompts = ui.prompts(texts) if plan_ahead else []
        if prompts:
            label = random.choice(prompts)
            pending = self._ai_pool.submit(self._ai_comment, label, texts)
            target = ui.find_like_for(label, ui.last_read_scrolls + 2)
            if target is None:
                pending = None  # its comment would answer a prompt we're not liking
                self._log("Couldn't find the heart for the chosen prompt; picking another.")
        elif plan_ahead:
            ui.scroll_back(ui.last_read_scrolls)
        if target is None:
            target = ui.pick_like_target(cfg["max_scrolls"], prefer_prompts=plan_ahead)
            if target is None:
                self._log("No like button found on this profile.")
                self._skip(ui, dry, "no like button")
                return
            label = ui.describe(target)

        def get_comment() -> str:
            if pending is None:
                return self._ai_comment(label, texts) or comments.pick()
            self.state = "waiting for the AI comment"
            try:
                return pending.result() or comments.pick()
            except Exception as exc:
                self._log(f"AI comment failed ({exc}); using comments.txt.")
                return comments.pick()

        if cfg["mode"] == "auto" and not dry:
            # Open the comment box while the comment is still being written.
            self.state = "opening the like sheet"
            if not ui.open_like(target):
                self._failures += 1
                self._log("The like sheet didn't open; trying again next round.")
                return
            self._failures = 0
            comment = get_comment()
            self.state = "writing comment"
            ui.write_comment(comment)
            self._send(ui, label, comment)
            return

        comment = get_comment()
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
        self._send(ui, label, comment)

    def _send(self, ui, label: str, comment: str) -> None:
        if ui.send():
            self._log(f"Sent like #{self._bump_sent()} on '{label}': “{comment}”")
        else:
            raise RuntimeError("Tapped Send, but the comment box stayed open")

    def _make_ai_commenter(self):
        if not self.config["ai_comments"]:
            return None
        try:
            from .ai_comments import AICommenter

            ai = AICommenter(self.config["ai_model"], self.config["ai_style"])
        except Exception as exc:
            self._log(f"AI comments are off: {exc}. Using comments.txt.")
            return None
        self._log(f"AI comments on ({self.config['ai_model']}).")
        return ai

    def _ai_comment(self, label: str, texts: List[str]) -> Optional[str]:
        """A comment written for this profile, or None to use comments.txt."""
        if self._ai is None or time.time() < self._ai_paused_until:
            return None
        import anthropic

        try:
            comment = self._ai.write(label, texts or [label])
        except anthropic.AuthenticationError:
            self._log("The Anthropic API key was rejected; AI comments are off until restart.")
            self._ai = None
            return None
        except anthropic.PermissionDeniedError as exc:
            self._log(f"Anthropic API refused the key ({exc.message}); AI comments are off until restart.")
            self._ai = None
            return None
        except anthropic.RateLimitError as exc:
            # Wait as long as the API asks (at least a minute) before trying again;
            # comments.txt covers the likes in between.
            try:
                wait = max(60, int(float(exc.response.headers.get("retry-after", "60"))))
            except ValueError:
                wait = 60
            self._ai_paused_until = time.time() + wait
            self._log(f"Anthropic API rate limit; using comments.txt for the next {wait // 60} min.")
            return None
        except anthropic.BadRequestError as exc:
            if "credit balance" in str(exc.message).lower():
                self._log("Anthropic credit has run out; using comments.txt until restart.")
                self._ai = None
            else:
                self._log(f"Anthropic API rejected the request ({exc.message}); using comments.txt this time.")
            return None
        except anthropic.APIStatusError as exc:
            self._log(f"Anthropic API error {exc.status_code}; using comments.txt this time.")
            return None
        except anthropic.APIConnectionError:
            self._log("Couldn't reach the Anthropic API; using comments.txt this time.")
            return None
        except anthropic.AnthropicError as exc:
            # Raised before any request is sent: usually no API key in the environment.
            self._log(
                f"AI comments are off until restart ({exc}). "
                "Start app.py with ANTHROPIC_API_KEY set to use them."
            )
            self._ai = None
            return None
        if comment is None:
            self._log("AI comment came back unusable; using comments.txt this time.")
            return None
        self._log(f"AI comment for '{label}': “{comment}”")
        return comment

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

    def _keyword_patterns(self) -> List[Tuple[str, re.Pattern]]:
        """skip_keywords as whole-word, case-insensitive patterns, so 'he/him' doesn't
        match 'she/her' and 'gym' doesn't match 'gymnast'."""
        return [
            (word, re.compile(rf"(?<!\w){re.escape(word.strip())}(?!\w)", re.IGNORECASE))
            for word in self.config["skip_keywords"]
            if word.strip()
        ]

    @staticmethod
    def _find_keyword(patterns, texts: List[str]) -> Optional[str]:
        for word, pattern in patterns:
            if any(pattern.search(text) for text in texts):
                return word
        return None

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
