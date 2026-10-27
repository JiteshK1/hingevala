"""Starting, checking and killing the Android emulator, without Android Studio.
(healthy() and adb() work for a real phone too.)

Used by the bot (to cold-boot the emulator when it crashes mid-run) and by
tools/watchdog.py (to restart it when it freezes)."""
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path
from typing import Callable, List, Optional

SDK = Path(os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk"))
AVD_HOME = Path(os.environ.get("ANDROID_AVD_HOME") or os.path.expanduser("~/.android/avd"))


def _binary(name: str, sdk_subdir: str) -> str:
    """From PATH, else from the Android SDK (PATH is often unset when not run from a shell)."""
    return shutil.which(name) or str(SDK / sdk_subdir / name)


def adb(serial: str, *args: str, timeout: float = 10) -> str:
    """Output of an adb command, or "" if it failed or hung."""
    try:
        out = subprocess.run(
            [_binary("adb", "platform-tools"), "-s", serial, *args],
            capture_output=True, text=True, timeout=timeout,
        )
        return out.stdout.strip() if out.returncode == 0 else ""
    except (subprocess.TimeoutExpired, OSError):
        return ""


def first_device_serial() -> str:
    """Serial of the first phone or emulator adb knows about, or the default emulator-5554."""
    try:
        out = subprocess.run(
            [_binary("adb", "platform-tools"), "devices"], capture_output=True, text=True, timeout=10
        ).stdout
    except (subprocess.TimeoutExpired, OSError):
        out = ""
    found = re.findall(r"^(\S+)\s+device$", out, re.MULTILINE)
    return found[0] if found else "emulator-5554"


def is_emulator(serial: str) -> bool:
    """False for a real phone (USB serial, or ip:port over wireless debugging)."""
    return serial.startswith("emulator-")


def healthy(serial: str) -> bool:
    return adb(serial, "shell", "getprop", "sys.boot_completed") == "1"


def avd_name(serial: str) -> str:
    """Name of the AVD running as `serial` ("" if it isn't answering).
    `adb emu avd name` prints the name, then "OK"."""
    return adb(serial, "emu", "avd", "name").split("\n")[0].strip()


def last_used_avd() -> str:
    """The AVD whose disk was written most recently: the one that was running before a crash."""
    avds = [(d / "userdata-qemu.img.qcow2", d.stem) for d in AVD_HOME.glob("*.avd")]
    avds = [(disk.stat().st_mtime, name) for disk, name in avds if disk.exists()]
    return max(avds)[1] if avds else ""


def pids(avd: str) -> List[int]:
    out = subprocess.run(
        ["pgrep", "-f", f"qemu-system.*-avd {re.escape(avd)}( |$)"], capture_output=True, text=True
    )
    return [int(p) for p in out.stdout.split()]


def kill(serial: str, avd: str, log: Callable[[str], None]) -> None:
    """Close the emulator: politely first, then force-kill whatever is left of it."""
    if pids(avd):
        adb(serial, "emu", "kill", timeout=10)
        deadline = time.time() + 20
        while pids(avd) and time.time() < deadline:
            time.sleep(1)
    for pid in pids(avd):
        log(f"Emulator didn't quit; force-killing process {pid}.")
        subprocess.run(["kill", "-9", str(pid)])
    time.sleep(3)
    # A crashed emulator leaves its lock files behind, and the next launch then refuses
    # to start ("another emulator instance is running"). Only cleared once it's gone.
    if not pids(avd):
        for lock in (AVD_HOME / f"{avd}.avd").glob("*.lock"):
            try:
                if lock.is_dir():
                    shutil.rmtree(lock)
                else:
                    lock.unlink()
            except OSError:
                pass


def start(
    serial: str,
    avd: str,
    boot_timeout: int,
    log: Callable[[str], None],
    log_file: Path,
    stop: Optional[threading.Event] = None,
) -> bool:
    """Cold-boot the AVD, like Android Studio's "Cold Boot Now" (a snapshot saved
    mid-crash can't be trusted). True once Android has finished booting."""
    log(f"Starting the {avd} emulator (cold boot).")
    port = re.search(r"emulator-(\d+)$", serial)
    with log_file.open("a") as out:
        subprocess.Popen(
            [_binary("emulator", "emulator"), "-avd", avd, "-no-snapshot-load",
             "-netdelay", "none", "-netspeed", "full",  # what Android Studio passes
             "-crash-report-mode", "never",  # no "Send report / Don't send" popup
             *(["-port", port.group(1)] if port else [])],  # keep the same serial
            stdout=out, stderr=subprocess.STDOUT, start_new_session=True,  # outlives us
        )
    wait = stop.wait if stop is not None else time.sleep
    deadline = time.time() + boot_timeout
    while time.time() < deadline:
        if stop is not None and stop.is_set():
            return False
        if healthy(serial):
            wait(10)  # let the launcher and Play services settle
            adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb(serial, "shell", "wm", "dismiss-keyguard")
            log("Emulator is up.")
            return True
        wait(5)
    log(f"Emulator didn't finish booting within {boot_timeout}s.")
    return False
