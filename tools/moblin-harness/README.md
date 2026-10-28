# Moblin's system tests against NexusIRL

Upstream Moblin's Python system-test harness (`upstream/tests`) drives the app over the remote
control protocol: it imports settings, goes live, reads the status and checks what arrives at
MediaMTX or ffmpeg. Because NexusIRL speaks the same protocol, the same tests run against it.
`run_android.py` runs them from Windows, macOS or Linux with these differences from
`python -m tests.test`:

- No check for macOS tools (qrtool, ltc-tools, lsof, tcpdump); tests that need them fail.
- NexusIRL's web remote control needs its access token: the imported settings set a known test
  token, requests to the phone's web remote control send it, and browser pages open it with the
  `?t=` login link. The chat bot's snapshot source URL gets the link too.
- A browser's request reaches the phone by another route than the harness's own connection, so
  a status read right after it may not see it yet; the bitrate check then polls again instead of
  failing on the missing entry.

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
5. For the chat suite, `adb reverse tcp:6967 tcp:6967` (the emote images it serves). For the web
   remote control over USB, `adb forward tcp:1180 tcp:1180` and `adb forward tcp:1181 tcp:1181`
   and the `phoneusb` device in the example config; its browser test needs
   `venv/bin/python -m playwright install --only-shell chromium`.

## Run

```sh
cd ~/moblin-harness
XDG_CONFIG_HOME=$PWD/config PATH=/path/to/mediamtx:$PATH \
    venv/bin/python run_android.py --device phone --suites stream,record "Rtmp|Record"
```

## Results (2026-10-26, Galaxy S26 Ultra and the emulator)

| Test | Result |
|---|---|
| SettingsImportWithoutSettingsFile, SceneSwitchMultipleTimes | Pass (scene switching also on the S26) |
| StreamRtmpToMediaMtx (5 Mbps, at least 10 MB, checked at MediaMTX) | Pass |
| StreamMultiRtmpToMediaMtx (2026-10-28: three RTMP destinations, each at least 10 MB at MediaMTX, status "x3") | Pass |
| Record (H.264 and H.265, 1080p30 to 4K, and 1080p60) | Pass, including the strict timing, B-frame and audio checks |
| Scene widget tests (2026-10-27): map, image with cube and PNG LUT | Pass; VTuber and PNGTuber widgets are not ported |
| MicDelay | Assumes iOS microphone IDs and a mic delay setting |
| SRT and SRTLA tests | Not run yet (need the firewall setup above) |
| Chat (2026-10-27): ChatEmotes (150 messages at 10 per second, 100 emotes each fetched once) and all 22 Twitch and YouTube event tests | Pass; the alerts and highlights show in chat |
| ChatBot tests (2026-10-27): mute and unmute, not allowed, snapshot from a web remote control to Discord | Pass; the two scene tests switch to a screen capture scene, which Android does not have |
| WebRemoteControlLive (go live and end from a browser, 5 Mbps at MediaMTX) | Pass |
| WebRemoteControlSnapshot | Fails by design: NexusIRL's `/snapshot` is JPEG, upstream's HEIC |

Fixed in NexusIRL because of these runs: settings keys for remote control and YouTube (iOS
interoperability), the remote control status texts and request ordering, an app crash when a
browser closed its web remote control connection mid-response, the web remote control's
Recordings page, frame and audio timing (upstream's frame timer), B-frames and mono audio in
recordings, and two crashes in the camera and preview set-up. On 2026-10-27: chat and Twitch
alerts forwarded by an assistant, a crash on emotes without a name, a crash when a stream was
stopped within a moment of starting (Android's foreground service rule), and remote control
status reporting live (and the bitrate) from the moment a stream starts, as upstream. Then: the
chat bot, quick buttons missing after an import, "None" camera scenes (status and black picture),
images in settings archives, widget LUTs, the map dot's size, and Discord snapshot uploads.
