<img src="fastlane/metadata/android/en-US/images/icon.png" alt="NexusIRL icon" width="112" align="right">

# NexusIRL

[![CI](https://github.com/mesmerize08/NexusIRL/actions/workflows/ci.yml/badge.svg)](https://github.com/mesmerize08/NexusIRL/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/mesmerize08/NexusIRL?include_prereleases&label=release)](https://github.com/mesmerize08/NexusIRL/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3ddc84.svg)

**A free, open-source IRL live-streaming app for Android**, ported from
[Moblin](https://github.com/eerimoq/moblin) for iOS. It bonds mobile data, Wi-Fi and Ethernet with
SRTLA, adapts its bitrate to the network, and works with iOS Moblin's settings files, remote
control and Moblink relays.

No accounts, ads, analytics or tracking. It needs no server run by its developers, and its
settings and stream keys stay encrypted on your phone.

> **Status: early test releases (0.x).** Streaming, scenes, chat and remote control are tested on
> real phones, live on Twitch and alongside iOS Moblin. Bonding over real mobile networks and
> Moblink between phones are still being field tested. See [what has been tested](#tested-devices).
>
> Not affiliated with or endorsed by the Moblin project.

## Features

| Area | What works today |
|---|---|
| **Streaming** | SRT with AES encryption, SRTLA bonding (mobile data + Wi-Fi + Ethernet), RTMP and RTMPS including enhanced RTMP for HEVC. H.264 or H.265 video up to 4K, AAC audio. |
| **Adaptive bitrate** | Moblin's IRL algorithms (fast and slow) and BELABOX's, driven by the network. |
| **Recording** | MP4 recording while streaming or on its own, and snapshots. |
| **Camera** | All back and front lenses, zoom presets, tap to focus, manual focus, exposure, ISO and white balance, torch, stabilization, portrait and landscape streams. |
| **Scenes and widgets** | Scenes with text (timers, location, speed), image, QR code, map, chat and browser widgets; LUTs and effects (grayscale, sepia and others). |
| **Chat and alerts** | Twitch, Kick and YouTube chat with BTTV, FFZ and 7TV emotes. Signed in to Twitch: send messages, on-screen alerts for follows, subs, raids and cheers, and stream title and category. Kick alerts for subscriptions, gifts, rewards, hosts and Kicks. Signed in to Kick: send messages and change the stream title and category. |
| **Remote control** | Control the phone from iOS Moblin, from another Android phone, or from a browser (protected by an access link), on the same Wi-Fi, over Tailscale or through [your own relay](server/relay/README.md). |
| **Moblink** | Lend this phone's mobile data to a Moblin or NexusIRL streamer, or bond other phones' data into your stream. |
| **Settings** | Import and export iOS Moblin `.moblinSettings` files and `moblin://` setup links, always with a confirmation. |
| **Privacy and security** | Stream keys and tokens encrypted with a hardware-backed key, removed from logs and screenshots; local control servers off by default; **Share diagnostics** for bug reports without secrets. |

Not ported yet: ingest servers (RTMP, SRT and RIST input), WHIP, face and background effects,
VTuber widgets and YouTube sign-in. The
[port plan](docs/PORT_PLAN.md) lists everything with its status.

**Known limitation:** recordings on the phone become playable only when recording stops normally.
If the app is closed by force, crashes or the battery runs out while recording, that recording is
lost (the stream itself is not affected). Crash-safe recording is planned.

## Install

1. Download the APK from [Releases](https://github.com/mesmerize08/NexusIRL/releases):
   `NexusIRL-<version>-arm64-v8a.apk` for practically every phone from the last several years,
   `armeabi-v7a` for older 32-bit phones, `x86_64` for Chromebooks and emulators, or
   `NexusIRL-<version>-universal.apk` if unsure.
2. Optionally check it against `SHA256SUMS` from the same release.
3. Open it on the phone and allow your browser or file manager to install apps when Android asks.

Android 10 or newer is needed. Updates install over the previous version and keep your settings.
[Obtainium](https://github.com/ImranR98/Obtainium) can follow the releases for you.

Release APKs are signed with this certificate (SHA-256), which `apksigner verify --print-certs`
shows:

```
00179e11f876bea2cf2189b9c04c91190cb58c6e17f56c7792e989a0038e6329
```

## Tested devices

| Phone | Android | Tested |
|---|---|---|
| Samsung Galaxy S26 Ultra (Snapdragon) | 17 | Live on Twitch over RTMPS; 60-minute SRTLA soak without drift or gaps; both orientations; remote control from iOS Moblin |
| Huawei P30 Pro (Kirin 980) | 10 | Install and update from the published APKs; settings import from iOS Moblin; 1080p30 H.265 stream |
| Android emulator | 14 and 16 | UI tests on every change; Moblin's system-test harness |

Moblin's own system-test harness runs against NexusIRL too; see
[tools/moblin-harness](tools/moblin-harness/README.md) for what passes.

## Documentation

| For | Document |
|---|---|
| Remote control (browser, Tailscale, relay) | [docs/remote-control.md](docs/remote-control.md) |
| Field testing bonding away from home | [docs/testing/field-test.md](docs/testing/field-test.md) |
| Privacy | [docs/privacy-policy.md](docs/privacy-policy.md) |
| Security design and reporting | [docs/threat-model.md](docs/threat-model.md), [SECURITY.md](SECURITY.md) |
| Plan, phases and decisions | [docs/PORT_PLAN.md](docs/PORT_PLAN.md) |
| Upstream file mapping | [docs/PORTING.md](docs/PORTING.md) |
| Releases and dependency updates | [docs/RELEASING.md](docs/RELEASING.md), [CHANGELOG.md](CHANGELOG.md) |
| Contributing | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Build from source

Requirements: JDK 17, the Android SDK (platform 36) and Git.

```sh
git clone --recurse-submodules https://github.com/mesmerize08/NexusIRL.git
cd NexusIRL
./gradlew test assembleDebug          # unit tests and a debug APK
./gradlew installDebug                # install on a connected phone
```

The full check that CI runs on every change:

```sh
./gradlew ktlintCheck test lint :app:licensee assembleDebug
sh tools/check-independence.sh
```

Unit tests include interoperability tests against reference implementations (libsrt and an RTMP
server through ffmpeg); they run when `ffmpeg` is on the `PATH` and are skipped otherwise. Every
dependency is pinned by checksum in `gradle/verification-metadata.xml`.

## Project structure

| Path | Contents |
|---|---|
| `app/` | The Android app: Jetpack Compose UI, stream session, controllers |
| `media/pipeline/` | Camera2 capture, OpenGL compositor and frame timer, MediaCodec encoders, MP4 recording |
| `media/mux/` | MPEG-TS muxer, FLV/AMF0, NAL and SEI utilities |
| `transport/srt/` | Moblin's SRT sender with AES encryption, SRTLA client and server |
| `transport/rtmp/` | RTMP and RTMPS client with enhanced RTMP |
| `transport/abr/` | Adaptive bitrate algorithms |
| `effects/` | Scene effects and widget rendering |
| `settings/` | iOS-compatible settings document, import and export, setup links |
| `chat/` | Twitch, Kick and YouTube chat, Twitch sign-in, alerts, and the Twitch and Kick APIs |
| `remotecontrol/` | Moblin remote control protocol: streamer, assistant and web remote control |
| `moblink/` | Moblink protocol: relay and streamer |
| `net/` | Multi-network handling for bonding |
| `core/` | Swift-compatible JSON, clocks, secret storage and redaction, WebSockets |
| `server/` | Self-hosted remote control relay and an SRTLA test receiver |
| `tools/` | Desktop streaming CLI (`push`), SRTLA test receiver, Moblin harness runner |
| `upstream/` | Moblin's source as a pinned submodule: reference, test vectors and system tests |

## Contributing and support

Bug reports and pull requests are welcome; please read [CONTRIBUTING.md](CONTRIBUTING.md) first.
For a bug report, tap **Share diagnostics** at the bottom of the main screen and attach the file:
it contains no stream keys, passwords or tokens. Report security problems privately as described
in [SECURITY.md](SECURITY.md).

## Credits and license

NexusIRL is MIT licensed; see [LICENSE](LICENSE). It contains code ported from
[Moblin](https://github.com/eerimoq/moblin), © 2023 Erik Moqvist, under the MIT License; see
[NOTICE](NOTICE). Each ported file names its upstream source, and [docs/PORTING.md](docs/PORTING.md)
tracks the mapping. Thanks to the Moblin project, whose design, protocols and tests made this port
possible.
