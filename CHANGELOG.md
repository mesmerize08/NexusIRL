# Changelog

All notable changes, newest first. The format follows [Keep a Changelog](https://keepachangelog.com),
and versions follow [Semantic Versioning](https://semver.org) (0.x: anything may still change).

## [Unreleased]

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
