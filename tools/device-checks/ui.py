"""Guarded uiautomator driver for testing NexusIRL on a personal phone.

    python ui.py dump
    python ui.py tap "<exact text>" [n]
    python ui.py tapxy X Y
    python ui.py type "<text>"        (only into a focused NexusIRL text field)
    python ui.py key <KEYCODE>
    python ui.py swipe X1 Y1 X2 Y2

Every input first checks that the focused window belongs to NexusIRL (or Android's permission
dialog) and refuses otherwise, so a chat bubble or notification popping up over the app can never
receive taps or text. Exit code 3 means "refused: another app is in front".
"""
import html
import os
import re
import subprocess
import sys
import time

# adb from ANDROID_HOME (or the default SDK folder on Windows), else from PATH.
_SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = next(
    (p for p in (os.path.join(_SDK, "platform-tools", n) for n in ("adb.exe", "adb")) if os.path.exists(p)),
    "adb",
)
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
APP = "io.github.mesmerize08.nexusirl"
ALLOWED = (APP, "com.google.android.permissioncontroller", "com.android.permissioncontroller")


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", env=ENV).stdout


def focused_package():
    out = adb("shell", "dumpsys", "window")
    match = re.search(r"mCurrentFocus=Window\{(\S+) \S+ ([^}]+)\}", out)
    if not match:
        return None
    window, title = match.group(1), match.group(2)
    if "/" in title or re.fullmatch(r"[\w.]+", title):
        return title.split("/")[0]
    # Pop-ups (drop-down menus) are titled "Pop-up window": ask the system which app owns it.
    details = adb("shell", "dumpsys", "window", "windows")
    block = re.search(r"Window\{" + re.escape(window) + r" .*?package=(\S+)", details, re.S)
    return block.group(1) if block else title


def guard():
    package = focused_package()
    if package not in ALLOWED:
        print(f"REFUSED: focused window is {package!r}, not NexusIRL", file=sys.stderr)
        sys.exit(3)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r"<node ([^>]*?)/?>", xml):
        attrs = dict((k, html.unescape(v)) for k, _, v in re.findall(r'([\w-]+)=(["\'])(.*?)\2', m.group(1)))
        b = re.findall(r"\d+", attrs.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append(
                {
                    "text": attrs.get("text", ""),
                    "cls": attrs.get("class", "").split(".")[-1],
                    "checked": attrs.get("checked"),
                    "focused": attrs.get("focused") == "true",
                    "package": attrs.get("package", ""),
                    "bounds": (x1, y1, x2, y2),
                }
            )
    return out


def find(text, index=0):
    # Exact matches on NexusIRL's (or the permission dialog's) own elements only.
    matches = [n for n in nodes() if n["text"] == text and n["package"] in ALLOWED]
    if len(matches) <= index:
        raise SystemExit(f"not found: {text!r}")
    return matches[index]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    cmd = sys.argv[1]
    if cmd == "dump":
        print(f"[focus: {focused_package()}]")
        hidden = 0
        for n in nodes():
            if n["package"] not in ALLOWED:
                hidden += 1  # Other apps' content is never printed (personal phone).
                continue
            if n["text"] or n["cls"] in ("Button", "EditText", "Switch", "RadioButton", "CheckBox"):
                extra = " checked" if n["checked"] == "true" else ""
                extra += " focused" if n["focused"] else ""
                print(f"{n['text'][:90]!r} {n['cls']} {n['bounds']}{extra}")
        if hidden:
            print(f"[{hidden} elements of other apps not shown]")
        return
    guard()
    if cmd == "tap":
        x1, y1, x2, y2 = find(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0)["bounds"]
        guard()
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(1)
    elif cmd == "tapxy":
        adb("shell", "input", "tap", sys.argv[2], sys.argv[3])
        time.sleep(1)
    elif cmd == "type":
        field = [n for n in nodes() if n["focused"] and n["cls"] == "EditText"]
        if not field or field[0]["package"] != APP:
            print("REFUSED: no focused NexusIRL text field", file=sys.stderr)
            sys.exit(3)
        guard()
        adb("shell", "input", "text", sys.argv[2].replace(" ", "%s").replace("&", "\\&").replace("?", "\\?"))
        time.sleep(0.5)
    elif cmd == "key":
        adb("shell", "input", "keyevent", sys.argv[2])
        time.sleep(0.5)
    elif cmd == "swipe":
        adb("shell", "input", "swipe", *sys.argv[2:6], "300")
        time.sleep(1)
    else:
        raise SystemExit(f"unknown command {cmd}")


main()
