# Contributing

Thanks for helping. NexusIRL is a free Android port of [Moblin](https://github.com/eerimoq/moblin);
the plan, phases and design decisions are in [docs/PORT_PLAN.md](docs/PORT_PLAN.md).

## Reporting problems

Use the issue templates. Say which phone, Android version and app version, and how to reproduce
it. Never post stream keys, URLs containing keys, tokens or passwords. Report security problems
privately (see [SECURITY.md](SECURITY.md)).

## Code

- Build with JDK 17 and the Android SDK (platform 36): `./gradlew test assembleDebug`.
- Before a pull request: `./gradlew ktlintCheck test lint :app:licensee assembleDebug` and
  `sh tools/check-independence.sh`.
- Code ported from Moblin starts with a header naming the upstream file and commit, e.g.
  `// Port of Moblin/Media/Srtla/Client/SrtlaClient.swift @97e26d2`; add a row to
  [docs/PORTING.md](docs/PORTING.md).
- Stay wire-compatible with iOS Moblin (settings files, remote control, Moblink).
- Only permissive licences (MIT, BSD, Apache-2.0); no GPL code and no Google Play Services in the
  main build (F-Droid). The licence check in CI enforces the first part.
- No dependency on services run by the Moblin author or by this project.
- New dependencies: regenerate `gradle/verification-metadata.xml` as described in
  [docs/RELEASING.md](docs/RELEASING.md) and review the diff.

Contributions are under the MIT licence of this repository.
