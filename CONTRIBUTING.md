# Contributing to NexusIRL

Thanks for helping. NexusIRL is a free Android port of [Moblin](https://github.com/eerimoq/moblin).
The architecture, phases and design decisions are in [docs/PORT_PLAN.md](docs/PORT_PLAN.md); read
its first sections before larger changes.

## Reporting problems

- Use the issue templates, and say which phone, Android version and app version, and how to
  reproduce it.
- Attach the file from **Share diagnostics** (bottom of the main screen). It contains no stream
  keys, passwords or tokens, but check it before posting.
- Never post stream keys, URLs that contain keys, tokens or passwords.
- Report security problems privately; see [SECURITY.md](SECURITY.md).

## Development setup

- JDK 17, the Android SDK (platform 37) and Git; Android Studio is optional.
- Clone with submodules: `git clone --recurse-submodules https://github.com/mesmerize08/NexusIRL.git`.
  `upstream/` is Moblin's source, pinned for reference; do not edit it.
- `./gradlew installDebug` installs a debug build on a connected phone. Debug and release builds
  are signed differently, so switching between them needs an uninstall (which deletes the app's
  settings; export them first).

## Tests

| Level | Command | Notes |
|---|---|---|
| Unit and golden tests | `./gradlew test` | Includes vectors from Moblin's own tests. Interoperability tests against libsrt and an RTMP server run when `ffmpeg` (with libsrt) is on the `PATH`, and are skipped otherwise; with `NEXUSIRL_REQUIRE_FFMPEG=1`, as in CI, they fail instead. |
| Lint, style, licences | `./gradlew ktlintCheck lint :app:licensee` and `sh tools/check-independence.sh` | The last one fails on any reference to services run by the Moblin author. |
| Device checks | [tools/device-checks](tools/device-checks/README.md) | Every main-screen control and a camera stress test on a real phone, with its own settings. |
| UI tests | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` | Always set `ANDROID_SERIAL`: without it Gradle also runs them on connected phones, where they change the app's settings. |
| System tests | [tools/moblin-harness](tools/moblin-harness/README.md) | Moblin's Python harness against a phone or the emulator. |

CI runs everything except the system tests and device checks on every push and pull request, and also builds the release variant; all of it must pass. Its GitHub Actions are pinned to commits (Dependabot proposes updates).

## Code guidelines

- Follow the existing style (`./gradlew ktlintFormat` fixes most of it) and keep comments to the
  *why*.
- Code ported from Moblin starts with a header naming the upstream file and commit, for example
  `// Port of Moblin/Media/Srtla/Client/SrtlaClient.swift @97e26d2`, and gets a row in
  [docs/PORTING.md](docs/PORTING.md).
- Stay compatible with iOS Moblin: settings keys and values, the remote control and Moblink
  protocols. Settings classes keep unknown keys, so iOS-only settings survive a round trip.
- Secrets (stream keys, tokens, passwords) go through `SettingsStore`/`SecretStore`, are never
  logged, and are shown only as `scheme://host`.
- Only permissive licences (MIT, BSD, Apache-2.0); no GPL code and no Google Play Services in the
  main build, so it stays eligible for F-Droid. CI checks licences.
- No dependency on services run by the Moblin author or by this project.
- New or updated dependencies: regenerate `gradle/verification-metadata.xml` as described in
  [docs/RELEASING.md](docs/RELEASING.md) and review the diff.
- Test-only behaviour belongs in debug builds and must not be reachable in release builds (see
  `TestHostActivity` and the remote settings import marker for examples).

## Commits and pull requests

- One logical change per commit, with a message that says what changed and why; mention how it was
  checked (tests, phone, emulator).
- Add user-visible changes to the `Unreleased` section of [CHANGELOG.md](CHANGELOG.md).
- Fill in the pull request template. Pull requests need the CI checks (build, UI tests, CodeQL and
  gitleaks) to pass before merging.

Contributions are under the MIT licence of this repository.
