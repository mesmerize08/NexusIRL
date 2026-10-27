# Device checks

Scripts that exercise NexusIRL on a real phone over adb and report problems. Run them before a
release and after camera or UI changes. They need a debug build (screens are readable over
uiautomator) and Python 3, and work with the phone's own settings: nothing is changed that is not
put back.

Set `ANDROID_SERIAL` to the phone (`adb devices` lists it), start NexusIRL on its main screen in
landscape, and keep the phone plugged in and unlocked (`adb shell svc power stayon usb` while
testing, `false` afterwards).

| Script | What it checks |
|---|---|
| `sweep.py` | Every main-screen control: zoom presets, stealth mode, torch, mute, effects (on and off again), the Portrait layout, each screen opened from the main screen and closed with Back, Back/Front cameras and the front camera's torch. Fails on error text on screen, a restarted app or errors in the app's log. |
| `camera_stress.py [seed] [pairs]` | Rapid Back/Front switching (pairs of taps 0.1–0.3 s apart). Fails if two cameras are ever open at once, a camera reports an error or the app restarts. |
| `ui.py` | The guarded driver both use: it reads the screen and refuses any input unless NexusIRL (or Android's permission dialog) is in front, so a notification or chat bubble can never receive taps or text. |

```sh
ANDROID_SERIAL=<serial> python tools/device-checks/sweep.py
ANDROID_SERIAL=<serial> python tools/device-checks/camera_stress.py 1 10
```

Taps are only made on controls found by their exact label in a fresh reading of the screen, once
their position is stable, and a run stops if NexusIRL's main screen is not showing. Stealth mode
is left with a double tap in the middle of the screen.

Results (2026-10-27): Galaxy S26 Ultra `sweep.py` 27/27; `camera_stress.py` on the S26 Ultra (Android 17) and
Huawei P30 Pro (Android 10), every switch a close before the next open,
never two cameras open and no errors.
