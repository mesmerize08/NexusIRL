# NexusIRL

A free, open-source IRL live-streaming app for Android, ported from
[Moblin](https://github.com/eerimoq/moblin) (iOS, MIT License).

> **Status: early development (Phase 0 — foundations).** It cannot stream yet. See
> [docs/PORT_PLAN.md](docs/PORT_PLAN.md) for the full plan and phase gates.

**Not affiliated with or endorsed by the Moblin project.**

## Goals

- **Core IRL streaming:**
  - SRT, SRTLA bonding (cellular + Wi-Fi + Ethernet) and RTMP(S)
  - adaptive bitrate
  - scenes and widgets
  - Twitch, Kick and YouTube chat
- **Wire-compatible with iOS Moblin:** import `.moblinSettings`, interoperate with Moblin remote
  control and Moblink relays.
- **Independent:** works without any service run by the Moblin author or by this project. Any
  remote-control relay is self-hosted (see the plan, §8).
- **FOSS-clean:** no Google Play Services, Firebase or analytics, so it is eligible for F-Droid and
  works on de-Googled phones.
- **Secure by default:**
  - stream keys and tokens are encrypted with an Android Keystore-backed key
  - secrets are redacted from logs
  - local control servers are off by default and authenticated (see the plan, §9)

## Repository layout

| Path | Contents |
|---|---|
| `app/` | Android app (Jetpack Compose) |
| `core/codable/` | Swift-`Codable`-compatible JSON for wire compatibility with iOS Moblin |
| `core/clock/` | Host clock, `MediaTime` and timestamp helpers |
| `core/secrets/` | `SecretStore` (Keystore + Tink) and `Redactor` |
| `upstream/` | Moblin source as a git submodule, pinned for reference, test vectors and the system-test harness |
| `docs/` | Port plan, threat model, porting tracker |

## Building

Requirements: JDK 17 and the Android SDK (platform 36).

```sh
git clone --recurse-submodules <this repo>
./gradlew test assembleDebug
```

## Credits and license

MIT, see [LICENSE](LICENSE). This project contains code ported from Moblin, © 2023 Erik Moqvist,
under the MIT License; see [NOTICE](NOTICE). Every ported file names its upstream source in a
header comment, and [docs/PORTING.md](docs/PORTING.md) tracks the mapping.

Security issues: see [SECURITY.md](SECURITY.md).
