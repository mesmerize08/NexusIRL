"""Feature sweep on a phone (ANDROID_SERIAL): exercises main-screen controls and reports problems.

Every tap first reads the screen (ui.py) and taps only a control with exactly that label inside
NexusIRL; the controls column is scrolled to find it. Toggles are switched back off. Dialogs and
screens are closed with Back and the main screen must return.
"""
import os
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
PACKAGE = "io.github.mesmerize08.nexusirl"


def adb(*args):
    return subprocess.run([ADB, "-s", os.environ["ANDROID_SERIAL"], *args], capture_output=True, text=True, errors="replace").stdout


def dump():
    return subprocess.run(UI + ["dump"], capture_output=True, text=True).stdout


MAIN_LABELS = ("'Go live'", "'Streams'", "'Scenes'", "'Back'", "'Front'", "'Camera settings'", "'Torch'")


def on_main(screen):
    """NexusIRL's main screen (any part of its controls column), not stealth mode or another screen."""
    # The camera overlay's controls (or Go live) only exist on the main screen; scene pages have
    # scenes called "Back" and "Front", so those names prove nothing.
    return (screen.startswith(f"[focus: {PACKAGE}]") and "stealth mode" not in screen
            and ("'Camera settings'" in screen or "'Go live'" in screen))


def find(label):
    """Scrolls the controls column until [label] shows; returns its centre or None."""
    for direction in ("down",) * 8 + ("up",) * 12:
        screen = dump()
        if not screen.startswith(f"[focus: {PACKAGE}]"):
            return None
        m = re.search(r"'%s' TextView \((\d+), (\d+), (\d+), (\d+)\)" % re.escape(label), screen)
        if m and int(m.group(4)) - int(m.group(2)) > 20:
            # Tap only once the column has stopped moving: same position in a second reading.
            time.sleep(0.7)
            again = re.search(r"'%s' TextView \((\d+), (\d+), (\d+), (\d+)\)" % re.escape(label), dump())
            if again and again.groups() == m.groups():
                return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2
            continue
        # The controls column: on the right in landscape, below the preview in portrait.
        portrait = re.search(r"'0.6x' TextView \((\d+),", screen)
        if portrait and int(portrait.group(1)) < 400:
            x, (y1, y2) = "540", ((1900, 1100) if direction == "down" else (1100, 1900))
        else:
            x, (y1, y2) = "1700", ((950, 350) if direction == "down" else (350, 950))
        adb("shell", "input", "swipe", x, str(y1), x, str(y2), "600")
        time.sleep(1.5)
    return None


def tap(label):
    position = find(label)
    if position is None:
        return False
    adb("shell", "input", "tap", str(position[0]), str(position[1]))
    return True


def status_problems(screen):
    return re.findall(r"'((?:Camera:|Error|Failed|failed)[^']*)'", screen)


results = []


def step(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)


start = dump()
m = re.search(r"'0.6x' TextView \((\d+),", start)
if not on_main(start) or m is None or int(m.group(1)) < 400:
    sys.exit("Start on NexusIRL's main screen in the landscape layout")
adb("logcat", "-c")
pid = adb("shell", "pidof", PACKAGE).strip()

# Zoom presets, quickly one after another (lens changes inside the logical camera).
for label in ("0.6x", "1x", "2x", "5x", "1x"):
    tap(label)
    time.sleep(0.5)
time.sleep(2)
screen = dump()
step("zoom presets 0.6x/1x/2x/5x", on_main(screen) and not status_problems(screen), str(status_problems(screen)))

# Toggles: on, then off.
# Stealth mode blacks out the screen; it is left with a double tap.
if tap("Stealth mode"):
    time.sleep(1.5)
    black = dump()
    adb("shell", "input", "tap", "1170", "540")
    adb("shell", "input", "tap", "1170", "540")
    time.sleep(1.5)
    step("stealth mode on and off", "stealth mode" in black and on_main(dump()))
else:
    step("stealth mode on and off", False, "not found")

for label in ("Torch", "Mute", "Local overlays", "Gray scale", "Sepia", "Pixellate",
              "CRT", "4:3", "Whirlpool", "Pinch", "Show chat"):
    if not tap(label):
        step(f"toggle {label}", False, "not found")
        continue
    time.sleep(1.5)
    screen = dump()
    problems = status_problems(screen)
    # Stealth mode and local overlays hide parts of the UI; turn them off by tapping again anywhere they are.
    tapped_off = tap(label)
    time.sleep(1.5)
    after = dump()
    step(f"toggle {label}", tapped_off and on_main(after) and not problems and not status_problems(after),
         str(problems + status_problems(after)))

# Dialogs and screens: open, then Back.
for label in ("Camera settings", "Bitrate", "Mic", "LUTs", "Edit", "Chat settings", "Streams", "Scenes",
              "Import and export settings"):
    if not tap(label):
        step(f"open {label}", False, "not found")
        continue
    time.sleep(1.5)
    opened = dump()
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.5)
    back = dump()
    step(f"open {label}", opened.startswith(f"[focus: {PACKAGE}]") and on_main(back) and not status_problems(back),
         "" if on_main(back) else "did not return to the main screen")

# Portrait: the layout turns portrait, then back.
if tap("Portrait"):
    time.sleep(3)
    portrait = dump()
    m = re.search(r"'0.6x' TextView \((\d+),", portrait)
    is_portrait = m is not None and int(m.group(1)) < 400
    tap("Portrait")
    time.sleep(3)
    back = dump()
    m = re.search(r"'0.6x' TextView \((\d+),", back)
    step("portrait layout on and off", is_portrait and m is not None and int(m.group(1)) >= 400 and on_main(back))
else:
    step("portrait layout on and off", False, "not found")

# Cameras, including the front camera's torch (it has none).
for label in ("Front", "Back", "Front"):
    tap(label)
    time.sleep(2)
tap("Torch")
time.sleep(1.5)
screen = dump()
step("front camera and its torch", on_main(screen) and "Front (" in screen and not status_problems(screen),
     str(status_problems(screen)))
tap("Torch")
time.sleep(1)
tap("Back")
time.sleep(2)
screen = dump()
step("back camera again", on_main(screen) and "Back (" in screen and not status_problems(screen), str(status_problems(screen)))

log = adb("logcat", "-d")
new_pid = adb("shell", "pidof", PACKAGE).strip()
errors = [line for line in log.splitlines()
          if f" {pid} " in line and re.search(r" E |FATAL|Exception|Camera error|failed", line)
          # Android's own graphics and WebView noise. "perf_hint: Session ID too large" is the
          # platform's render thread on Android 17 once the system's hint-session counter passes
          # 32 bits (seen on the S26 on 2026-10-28, with old and new builds alike).
          and not re.search(r"AdrenoGLES|libpenguin|chromium|BufferQueueProducer|cr_|SurfaceComposer|HWUI|perf_hint: Session ID too large", line)]
step("app still running (same process)", new_pid == pid, f"{pid} -> {new_pid}")
step("no errors in the app's log", not errors, f"{len(errors)} lines")
for line in errors[:12]:
    print("   ", line[:220])
print(f"\n{sum(ok for _, ok, _ in results)}/{len(results)} passed")
