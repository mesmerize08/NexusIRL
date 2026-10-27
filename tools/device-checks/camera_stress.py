"""Rapid Back/Front switching on a phone (ANDROID_SERIAL), then checks the logs.

Safety: before every pair of taps the screen is read again; the run stops if NexusIRL's main
screen (with Back and Front) is not showing, so a tap never lands on another control. Each pair
is two taps 0.1–0.3 s apart (the fast race); pairs are 1–2 s apart (the screen read).
"""
import os
import random
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
_SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = next(
    (p for p in (os.path.join(_SDK, "platform-tools", n) for n in ("adb.exe", "adb")) if os.path.exists(p)),
    "adb",
)
UI = [sys.executable, "-I", os.path.join(HERE, "ui.py")]
SERIAL = ["-s", os.environ["ANDROID_SERIAL"]]


def adb(*args):
    return subprocess.run([ADB, *SERIAL, *args], capture_output=True, text=True, errors="replace").stdout


def main_screen_positions():
    screen = subprocess.run(UI + ["dump"], capture_output=True, text=True).stdout
    if not screen.startswith("[focus: io.github.mesmerize08.nexusirl]") or "'Go live'" not in screen:
        return None
    positions = {}
    for name in ("Back", "Front"):
        m = re.search(r"'%s' TextView \((\d+), (\d+), (\d+), (\d+)\)" % name, screen)
        if m is None:
            return None
        positions[name] = ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2)
    return positions


random.seed(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
pairs = int(sys.argv[2]) if len(sys.argv) > 2 else 10
adb("logcat", "-c")
done = 0
for _ in range(pairs):
    positions = main_screen_positions()
    if positions is None:
        print("STOPPED: main screen not showing")
        break
    for name in ("Back", "Front"):
        x, y = positions[name]
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(random.choice([0.1, 0.2, 0.3]))
    done += 1
time.sleep(4)
log = adb("logcat", "-d")
pid = adb("shell", "pidof", "io.github.mesmerize08.nexusirl").strip()
refs = [int(r) for r in re.findall(r"increaseCamConnRef: ref=(\d+)", log)]
connects = re.findall(r"connect call \(PID \d+ UID \d+ \"io.github.mesmerize08.nexusirl\", camera ID (\d+)\)", log)
problems = [line for line in log.splitlines() if re.search(
    r"Unsupported set|Can't finish configuring|Camera error|configuration failed|request failed|FATAL EXCEPTION|ERROR_MAX_CAMERAS|dead thread", line)
    and (f" {pid} " in line or "Camera3" in line or "CameraDeviceClient" in line)]
screen = subprocess.run(UI + ["dump"], capture_output=True, text=True).stdout
status = re.findall(r"'((?:Front|Back) \(\d+\)|Camera:[^']*)'", screen)
print(f"pairs {done}, camera opens {len(connects)}: {''.join(connects)}")
print(f"max cameras open at once: {max(refs) if refs else 0}")
print(f"problems: {len(problems)}")
for line in problems[:5]:
    print("  ", line[:200])
print(f"app running: {bool(pid)}, camera now: {status}")
