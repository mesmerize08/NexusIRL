# Changelog

All notable changes, newest first. The format follows [Keep a Changelog](https://keepachangelog.com),
and versions follow [Semantic Versioning](https://semver.org) (0.x: anything may still change).

## [Unreleased]

### Added
- Twitch alert pop-ups on the streamer's screen (follows, subscriptions, resubscriptions, gift
  subscriptions, channel point rewards, raids, cheers), one at a time.
- Per-stream Twitch alert settings, as in Moblin: which alerts pop up and which appear in chat,
  with a minimum for cheers. Settings imported from iOS keep their choices.

- Twitch stream title and category: shown in the stream's settings, with category search, and
  changed on Twitch after a confirmation. Needs signing in to Twitch again once (one more
  permission, `channel:manage:broadcast`).

### Fixed
- Twitch alerts always appeared in chat, ignoring the alert settings of imported iOS streams.
- The privacy policy described an earlier preview; it now lists every service the app contacts
  and what each receives.

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
