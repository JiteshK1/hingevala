"""Keeps the emulator alive: restarts it when it freezes or crashes, then resumes the bot.

    .venv/bin/python tools/watchdog.py                 # uses the AVD that's running now
    .venv/bin/python tools/watchdog.py --avd Pixel_7   # also starts it if it isn't running

Run it in its own terminal next to app.py. Every --interval seconds it asks the emulator a
trivial question over adb. After --failures missed answers in a row (or if the emulator
process is gone) it stops the bot, kills the emulator, cold-boots it, and presses Start
again if the bot was running before the freeze. It also closes Android's
"<app> isn't responding" dialogs.

The emulators it starts never show the "Send report / Don't send" popup.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
CONTROL_PAGE = "http://127.0.0.1:8000"


def log(message: str) -> None:
    line = f"{datetime.now():%Y-%m-%d %H:%M:%S}  {message}"
    print(line, flush=True)
    with (DATA / "watchdog.log").open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def adb(serial: str, *args: str, timeout: float = 10) -> str:
    """Output of an adb command, or "" if it failed or hung."""
    try:
        out = subprocess.run(
            ["adb", "-s", serial, *args], capture_output=True, text=True, timeout=timeout
        )
        return out.stdout.strip() if out.returncode == 0 else ""
    except subprocess.TimeoutExpired:
        return ""


def emulator_binary() -> str:
    found = shutil.which("emulator")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    return os.path.join(sdk, "emulator", "emulator")


def emulator_pids(avd: str) -> list:
    out = subprocess.run(
        ["pgrep", "-f", f"qemu-system.*-avd {avd}( |$)"], capture_output=True, text=True
    )
    return [int(p) for p in out.stdout.split()]


# ---- the bot, through the control page --------------------------------------------

def bot_api(path: str, post: bool = False) -> dict:
    """Call the control page. {} if app.py isn't running."""
    try:
        req = urllib.request.Request(CONTROL_PAGE + path, method="POST" if post else "GET")
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read())
    except OSError:
        return {}


def bot_running() -> bool:
    return bool(bot_api("/api/status").get("running"))


def stop_bot() -> None:
    bot_api("/api/stop", post=True)
    deadline = time.time() + 60  # it may be stuck in an adb call to the frozen emulator
    while bot_running() and time.time() < deadline:
        time.sleep(2)


# ---- health -----------------------------------------------------------------------

def healthy(serial: str) -> bool:
    return adb(serial, "shell", "getprop", "sys.boot_completed") == "1"


def close_anr_dialog(serial: str) -> None:
    """Tap "Close app" on an "<app> isn't responding" dialog, if one is showing.
    If it was Hinge, the bot sees Hinge is gone and reopens it."""
    focus = adb(serial, "shell", "dumpsys", "window", "displays")
    if "Application Not Responding" not in focus:
        return
    import uiautomator2 as u2

    log("An 'isn't responding' dialog is showing; tapping Close app.")
    try:
        d = u2.connect(serial)
        for label in ("Close app", "OK", "Wait"):
            if d(text=label).click_exists(timeout=3):
                return
    except Exception as exc:
        log(f"Couldn't close the dialog: {exc}")


# ---- restarting -------------------------------------------------------------------

def kill_emulator(serial: str, avd: str) -> None:
    adb(serial, "emu", "kill", timeout=10)
    deadline = time.time() + 20
    while emulator_pids(avd) and time.time() < deadline:
        time.sleep(1)
    for pid in emulator_pids(avd):
        log(f"Emulator didn't quit; force-killing process {pid}.")
        subprocess.run(["kill", "-9", str(pid)])
    time.sleep(3)


def start_emulator(serial: str, avd: str, boot_timeout: int) -> bool:
    """Cold-boot the AVD (a snapshot saved mid-freeze can't be trusted). True once booted."""
    log(f"Starting the {avd} emulator (cold boot).")
    with (DATA / "emulator.log").open("a") as out:
        subprocess.Popen(
            [emulator_binary(), "-avd", avd, "-no-snapshot-load", "-no-boot-anim",
             "-crash-report-mode", "never"],  # no "Send report / Don't send" popup
            stdout=out, stderr=subprocess.STDOUT, start_new_session=True,  # outlives us
        )
    deadline = time.time() + boot_timeout
    while time.time() < deadline:
        if healthy(serial):
            time.sleep(10)  # let the launcher and Play services settle
            adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb(serial, "shell", "wm", "dismiss-keyguard")
            log("Emulator is up.")
            return True
        time.sleep(5)
    log(f"Emulator didn't finish booting within {boot_timeout}s.")
    return False


def recover(serial: str, avd: str, resume_bot: bool, boot_timeout: int) -> bool:
    if bot_running():
        log("Stopping the bot.")
        stop_bot()
    kill_emulator(serial, avd)
    if not start_emulator(serial, avd, boot_timeout):
        return False
    if resume_bot:
        if bot_api("/api/start", post=True).get("started"):
            log("Pressed Start on the bot; it will reopen Hinge.")
        else:
            log("Couldn't start the bot. Is app.py running?")
    return True


def main() -> None:
    config = json.loads((ROOT / "config.json").read_text(encoding="utf-8"))
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--avd", help="AVD name, e.g. Pixel_7 (default: the one running now)")
    p.add_argument("--serial", default=config.get("device_serial") or "emulator-5554")
    p.add_argument("--interval", type=int, default=20, help="seconds between checks")
    p.add_argument("--failures", type=int, default=3, help="missed checks in a row before restarting")
    p.add_argument("--boot-timeout", type=int, default=300)
    p.add_argument("--max-restarts", type=int, default=5, help="per hour; then give up")
    args = p.parse_args()
    DATA.mkdir(exist_ok=True)

    # `adb emu avd name` prints the name, then "OK".
    avd = args.avd or adb(args.serial, "emu", "avd", "name").split("\n")[0].strip()
    if not avd:
        sys.exit(f"No emulator answering on {args.serial}. Pass --avd (see: emulator -list-avds).")
    log(f"Watching {avd} on {args.serial}, checking every {args.interval}s.")

    if not emulator_pids(avd) and not start_emulator(args.serial, avd, args.boot_timeout):
        sys.exit("Couldn't start the emulator.")

    misses = 0
    bot_was_running = False  # as of the last healthy check, so a freeze doesn't erase it
    restarts = []
    while True:
        if not emulator_pids(avd):
            problem = "The emulator process is gone (it crashed or was closed)."
        elif healthy(args.serial):
            misses = 0
            bot_was_running = bot_running()
            close_anr_dialog(args.serial)
            time.sleep(args.interval)
            continue
        else:
            misses += 1
            if misses < args.failures:
                time.sleep(args.interval)
                continue
            problem = f"The emulator hasn't answered {misses} checks in a row (frozen)."

        restarts = [t for t in restarts if t > time.time() - 3600]
        if len(restarts) >= args.max_restarts:
            log(f"{problem} Already restarted {len(restarts)} times this hour; giving up.")
            sys.exit(1)
        restarts.append(time.time())
        log(f"{problem} Restarting it ({len(restarts)}/{args.max_restarts} this hour).")
        recover(args.serial, avd, bot_was_running, args.boot_timeout)
        misses = 0


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
