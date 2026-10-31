# Changelog

All notable changes, newest first. The format follows [Keep a Changelog](https://keepachangelog.com),
and versions follow [Semantic Versioning](https://semver.org) (0.x: anything may still change).

## [Unreleased]

### Added
- Kick sign-in, as in Moblin (Streams → edit → Sign in to Kick): you sign in on Kick's own page
  inside the app, then messages typed in NexusIRL and chat bot answers go to Kick chat too, and the
  stream title and category can be changed. Kick's session is kept encrypted and never exported;
  the sign-in page's web data is deleted afterwards. (Kick's official login needs a secret an
  open-source app cannot keep, so this uses Kick's website session, as Moblin does.)
  The chat bot's `!moblin stream title` and `stream category` change Kick too when the selected
  stream is signed in there (Moblin changes Twitch only).
- Kick alerts, as in Moblin: subscriptions, gifted subscriptions, rewards, hosts and Kicks pop up
  on screen and show in chat, and bans and timeouts show in chat, each switchable per stream
  (Streams → edit → Kick alerts), with a minimum for Kicks. Read from Kick's public chat
  connection, no sign-in needed. Moblin's Kick alert settings import from iOS.

### Changed
- Releases have an APK for each processor type (`arm64-v8a`, `armeabi-v7a`, `x86_64`) besides the
  universal one, about a third of its size. Each has its own version code (10 × the release's plus
  1, 2 or 3; 0 for universal), so F-Droid can offer the same, signed by us.

## [0.4.0] - 2026-10-29

### Added
- Multi streaming, as in Moblin: an RTMP stream can also go to more RTMP servers at once (for
  example Twitch and YouTube), from the same encoder (Streams → edit → Multi streaming). Each
  destination reconnects on its own, and their stream keys are kept like the stream's own.
- Chat bot (Settings → Chat → Bot), as in Moblin: you and your moderators run `!moblin` commands
  from chat to switch scenes, mute and unmute, zoom, turn the torch and effects on and off, take
  snapshots, and start or stop the stream or change its Twitch title and category. Each command
  has Moblin's permissions (moderators, subscribers, everyone, cooldown, chat answers), and
  aliases such as `!screen` work. Moblin's chat bot settings import from iOS.
- Snapshots upload to Discord, as in Moblin: set a webhook per stream (Streams → edit → Discord),
  with a separate one for chat bot snapshots and an "only when live" switch. The webhook links are
  kept in the encrypted secret store and left out of exports without keys.
- The chat bot's snapshot command can take the picture from another phone's web remote control
  (Settings → Chat → Bot → `!moblin snapshot`), as in Moblin.
- Chat and Twitch alerts forwarded by a remote control assistant (Moblin's `chatMessages` and
  `twitchEventSubNotification` requests) show in chat, the chat widget and alert toasts, as in
  Moblin. Messages from SOOP and Open Streaming Platform are shown too.
- Bonding status: while live over SRTLA, the main screen shows each network's share of the data
  (for example "60% Cellular, 40% WiFi"), as in Moblin. Remote controls get it too, with each
  path's round-trip time, and the bitrate as numbers (Moblin's newer `bitrateInfo` and
  `bondingInfo`).

### Changed
- Build tools updated: Android Gradle Plugin 9.4, Gradle 9.8, Kotlin 2.4 and compile SDK 37
  (Android 17), with newer AndroidX libraries (Compose BOM 2026.09, core 1.19, lifecycle 2.11,
  activity 1.13). The app still targets Android 16.
- Zooming sends remote controls at most two updates a second, as Moblin now does.

### Fixed
- Starting or stopping a recording or stream held up the video for up to a tenth of a second, so
  60 fps recordings could miss frames at the start. Encoders now start and stop beside the video
  instead of in its way.
- Importing a `.moblinSettings` file now brings its images along (image widgets and LUT files), and
  exports include them, as in Moblin. Before, image widgets from iOS stayed empty.
- Image widgets apply their LUT effects from Moblin settings.
- A scene whose camera is "None" shows black behind its widgets, as in Moblin, instead of the last
  camera.
- The map widget's position dot is the same size on every map, as in Moblin.
- After importing settings without quick buttons, mute, torch and effects did nothing until the
  app was restarted. An import now adds the missing buttons (all off, as Moblin) and applies them.
- Remote control: the camera status says "None" for a scene without a camera, as in Moblin.
- A crash when a stream was stopped within a moment of starting (for example two quick taps, or
  a remote control going live and ending straight away).
- A crash when chat showed an emote without a name.
- Remote control: the status reports live, with the bitrate, from the moment a stream starts, as
  in Moblin, and the web remote control's Live switch turns off when the stream ends.

## [0.3.0] - 2026-10-27

### Added
- Encrypted settings exports: exporting with stream keys can encrypt the file with a passphrase
  (AES-256-GCM, PBKDF2 key). NexusIRL imports it after asking for the passphrase; the plain
  export for iOS Moblin is still there, behind a warning.

### Changed
- Video frames go out on an exact time grid, like Moblin's frame timer, and audio times follow
  the sample count: every frame is exactly one frame time after the previous one, so players and
  servers see perfectly even timing.
- Recordings use B-frames (better quality for the same size), like Moblin's.
- Built-in microphones record and stream in mono unless **Prefer stereo** is on (microphone
  picker), as in Moblin; remote control assistants now see the real channel count.
- Ready for F-Droid: the APK has no Google-encrypted dependency block, the app builds with any JDK
  17 or newer and without the upstream submodule, and `fdroid/` holds the build recipe (checked
  with F-Droid's scanner and build tools).

### Fixed
- Switching between the back and front cameras failed with "Camera session configuration failed"
  or "Camera error 2": the next camera opened before the previous one had closed. Cameras now take
  turns, and quick taps end on the last camera chosen.
- A camera error stayed on screen after the camera worked again.
- Back from the LUTs or Widgets screen opened with a quick button went to Scenes instead of the
  main screen.
- The preview could still be drawn into its surface after the screen had released it (logged as
  "Surface failed to disconnect").
- With the Portrait button and a landscape stream, the picture was sideways when holding the phone
  upright; it now stays upright, like Moblin, and the stream shows a landscape crop of it.
- Two crashes: when the screen turned off while the preview was being set up, and when a quick
  scene switch restarted the camera while it was still opening.
- Recordings could start with a few milliseconds of audio before the picture and lose the last
  frames on stop.

## [0.2.1] - 2026-10-26

### Added
- Web remote control: the Recordings page works (list, download, delete and thumbnails of the
  app's recordings), as in Moblin.
- Stream logs: the app keeps a per-second log of the last 10 streams (frame rate, bitrate,
  connection quality per network, state changes), included in Share diagnostics, so streams far
  from a computer can be analysed. No keys, picture or sound.

### Fixed
- The app crashed when a browser closed its connection to the web remote control in the middle
  of a response; anyone on the same network could trigger it, even without the access link.
- Remote control and YouTube settings used different names from iOS Moblin, so an iOS
  remote control setup or YouTube video IDs were not imported, and NexusIRL's were not read by
  iOS. Settings saved by earlier versions are converted when loaded.
- The remote control status now shows the measured bitrate with the total sent and the uptime in
  Moblin's format ("5.0 Mbps (12.3 MB)", "1m 23s"); assistants showed the encoder's target before.
- Remote control requests take effect before they are answered, as in Moblin, so a status read
  right after "go live" shows the stream starting.

## [0.2.0] - 2026-10-25

### Added
- Twitch alert pop-ups on the streamer's screen (follows, subscriptions, resubscriptions, gift
  subscriptions, channel point rewards, raids, cheers), one at a time.
- Per-stream Twitch alert settings, as in Moblin: which alerts pop up and which appear in chat,
  with a minimum for cheers. Settings imported from iOS keep their choices.
- Twitch stream title and category: shown in the stream's settings, with category search, and
  changed on Twitch after a confirmation. Needs signing in to Twitch again once (one more
  permission, `channel:manage:broadcast`).
- Self-hosted remote control relay (`server/relay`: Moblin's open-source relay with automatic
  HTTPS) and a guide to every way of connecting a remote control (`docs/remote-control.md`).

### Changed
- The remote control assistant URL's path (a relay's bridge ID) is stored encrypted, left out of
  exports without keys and removed from logs, like stream keys. Existing settings are converted
  on start.

### Fixed
- Twitch alerts always appeared in chat, ignoring the alert settings of imported iOS streams.
- The privacy policy described an earlier preview; it now lists every service the app contacts
  and what each receives.
- Closing the camera logged a warning with a stack trace each time.
- An import confirmation said "1 stream(s)".
- While a remote control showed the preview, the stream lost one frame every second (the preview
  was read back at full size on the render thread). Found with an iOS Moblin assistant.
- The Twitch sign-in dialog now says to come back to the app: after approving on the phone,
  Twitch's page can stay blank.

### Tested
- Live on Twitch from a Galaxy S26 Ultra (RTMPS, H.264), a Huawei P30 Pro on Android 10, and
  interop with iOS Moblin (remote control assistant, settings import). See
  [docs/release-notes/v0.2.0.md](docs/release-notes/v0.2.0.md).

## [0.1.1] - 2026-10-22

### Added
- UI smoke tests on an emulator in CI (every screen opens; adding a stream never shows its key),
  and unit tests for the camera clock fix found on the Galaxy S26 Ultra.
- "Share diagnostics" on the main screen: a text report for bug reports (app and phone details,
  status, cameras, encoders, networks, settings without secrets, the app's recent log), with
  stream keys, passwords and tokens removed. Shared only when the user chooses to.

## [0.1.0] - 2026-10-22

First test release. See [docs/release-notes/v0.1.0.md](docs/release-notes/v0.1.0.md) for what
works, what was tested and known limitations.
