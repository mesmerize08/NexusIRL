# Changelog

All notable changes, newest first. The format follows [Keep a Changelog](https://keepachangelog.com),
and versions follow [Semantic Versioning](https://semver.org) (0.x: anything may still change).

## [Unreleased]

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
