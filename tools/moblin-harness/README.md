# Moblin's system tests against NexusIRL

Upstream Moblin's Python system-test harness (`upstream/tests`) drives the app over the remote
control protocol: it imports settings, goes live, reads the status and checks what arrives at
MediaMTX or ffmpeg. Because NexusIRL speaks the same protocol, the same tests run against it.
`run_android.py` runs them from Windows, macOS or Linux with these differences from
`python -m tests.test`:

- No check for macOS tools (qrtool, ltc-tools, lsof, tcpdump); tests that need them fail.
- NexusIRL's web remote control needs its access token: the imported settings set a known test
  token, and requests to the phone's web remote control send it.

## Setup

1. Python 3.12 or newer, ffmpeg and [MediaMTX](https://github.com/bluenviron/mediamtx/releases)
   on `PATH`.
2. A work directory outside the repository (the harness writes logs and files next to itself):

   ```sh
   mkdir -p ~/moblin-harness/config/moblin/tests
   cp -r upstream/tests ~/moblin-harness/tests
   cp tools/moblin-harness/run_android.py ~/moblin-harness/
   cp tools/moblin-harness/config.example.toml ~/moblin-harness/config/moblin/tests/config.toml
   python -m venv ~/moblin-harness/venv
   ~/moblin-harness/venv/bin/pip install -r upstream/requirements.txt   # PYTHONUTF8=1 on Windows
   ```

3. **A debug build** of NexusIRL on the phone. Release builds never accept settings from a remote
   control; debug builds do only with a marker file that `adb run-as` can create:

   ```sh
   adb shell run-as io.github.mesmerize08.nexusirl touch files/accept-remote-settings-import-for-tests
   ```

   **The tests replace all of the app's settings.** Copy `files`, `no_backup` and `shared_prefs`
   aside with `run-as` first and put them back afterwards (stream keys stay readable on the same
   phone).

4. Point the app at the harness: settings with
   `{"remoteControl":{"server":{"enabled":true,"url":"ws://127.0.0.1:2345"},"password":"1234"}}`
   and `adb reverse tcp:2345 tcp:2345` and `adb reverse tcp:1935 tcp:1935` (RTMP to MediaMTX).
   SRT and SRTLA tests need UDP from the phone to the computer instead: set `tester-ip-address`
   to the computer's Wi-Fi address and allow Python, MediaMTX and ffmpeg in its firewall.

## Run

```sh
cd ~/moblin-harness
XDG_CONFIG_HOME=$PWD/config PATH=/path/to/mediamtx:$PATH \
    venv/bin/python run_android.py --device phone --suites stream,record "Rtmp|Record"
```

## Results (2026-10-26, Galaxy S26 Ultra and the emulator)

| Test | Result |
|---|---|
| SettingsImportWithoutSettingsFile, SceneSwitchMultipleTimes | Pass |
| StreamRtmpToMediaMtx (5 Mbps, at least 10 MB, checked at MediaMTX) | Pass |
| StreamMultiRtmpToMediaMtx | Fails: streaming to several destinations is not ported |
| Record (H.264 and H.265, 1080p to 4K) | Fail on strict checks: no B-frames in recordings; the first video frame 11–13 ms after zero; at 60 fps two frames 0.1 ms apart once every few seconds |
| Scene widget tests | VTuber and PNGTuber widgets are not ported; LUT tests need images the macOS setup makes |
| MicDelay | Assumes iOS microphone IDs and a mic delay setting |
| SRT and SRTLA tests | Not run yet (need the firewall setup above) |

Fixed in NexusIRL because of these runs: settings keys for remote control and YouTube (iOS
interoperability), the remote control status texts and request ordering, an app crash when a
browser closed its web remote control connection mid-response, and the web remote control's
Recordings page.
