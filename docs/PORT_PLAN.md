# Moblin → Android ("Moblin-A"): reverse-engineering findings and port plan

## Context
- **Goal:** port Moblin to Android. Moblin is an iOS IRL streaming app (github.com/eerimoq/moblin, commit `97e26d2`, 2026-10-18).
- **Licence:** **MIT** (© 2023 Erik Moqvist), so a derived port is allowed as long as the copyright notice is kept.
  - Issue #149: the author replied "Feel free to give it a try" and closed the issue as "not planned". There is no official Android port and no known fork.
  - MIT grants no trademark rights. Use a distinct app name and package ID, credited as "based on Moblin".
- **Your decisions:**
  - v1 is **core IRL streaming**.
  - This plan **supersedes `moblin_a_spec.md`**.
  - **Port Moblin's own transport code** to Kotlin.
  - Stay **wire-compatible** with iOS Moblin: settings import, remote control and Moblink.
  - Publish it as a **free, open-source GitHub project that anyone can install**, **independent of any author-run services** (§8).
- **Method:**
  - REA MCP was tried. Its deep providers are unavailable on this Windows host: Ghidra is not installed and needs a newer JDK, and Hopper runs only on macOS.
  - Because the full source is MIT, the analysis was done on source instead: 836 Swift files, ~184k LOC, mapped by three parallel deep-dives (media, app/model/UI, integrations/network). Source is more accurate than binary reverse engineering for this.
  - The analysed upstream source is pinned in this repo as the `upstream/` submodule.

## 1. Key findings that shape the port

1. **v1 needs no native C code.**
   - Moblin's default SRT sender (`Media/HaishinKit/Srt/SrtSender.swift`, 619 LOC) is pure Swift.
   - So are SRTLA (`Media/Srtla/Client`, ~1.1k), RTMP/RTMPS with AMF0 and enhanced-RTMP HEVC (`HaishinKit/Rtmp`, ~2.2k), the MPEG-TS writer and reader (`HaishinKit/Mpeg`, ~3.3k) and adaptive bitrate (ABR) (`Media/AdaptiveBitrate`, ~800).
   - libsrt, librist and libdatachannel are only needed for post-v1 features: ingest servers, RIST, WHIP/WHEP and the "Official" SRT implementation option. → **v1 is pure Kotlin/JVM**, and the transport stack is unit-testable on the host.
2. **There is no audio mixer.**
   - Exactly one audio source feeds the encoder: the mic, or one buffered network source.
   - DSP is limited to gain, mute, channel map and level meter. → Oboe and a C++ mixer (old spec) are unnecessary. `AudioRecord` at 48 kHz is enough.
3. **One host-clock frame timer drives video** (`VideoUnit.swift`, 1126 LOC).
   - Output PTS = `counter/fps + start`, nudged ±1 frame when it drifts more than 30 ms from the host clock.
   - The last frame is re-emitted after 50 ms with no input, and a black frame is sent when there is no source.
   - The camera only *feeds* the compositor; it does not clock it.
   - MPEG-TS PTS are absolute host seconds × 90 kHz.
4. **A scene is one base source plus an ordered list of `VideoEffect`s, rendered once per frame into a pooled buffer.** That buffer fans out to the encoder(s), preview, recorder, remote-control JPEG and snapshots.
   - Effect order (`ModelScene.swift` `sceneUpdatedOn`):
     1. LUTs
     2. Global quick-button effects
     3. Scene widgets (recursive, cycle-guarded)
     4. Draw-on-stream
     5. Poll
   - Widget layout is `x`, `y`, `size` as a % of the canvas, measured from an alignment corner (`VideoEffects/EffectUtils.swift`).
5. **Settings are a single forgiving JSON document** (`Database` in `Various/Settings/Settings.swift`).
   - It is exported as a stored (uncompressed) `.moblinSettings` zip containing `settings.json` plus media folders.
   - Deep links take the form `moblin://?<json>` using the `MoblinSettingsUrl` schema.
   - There is no version field: every key has a default, and unknown or missing keys are ignored. → iOS settings can be imported on Android.
6. **The remote control and Moblink use Swift's synthesized Codable JSON** (`RemoteControl/RemoteControl.swift`, `Moblink/MoblinkProtocol.swift`). The wire format must match exactly — see §4.
7. **The repo's Python system-test harness** (`tests/`, driven by mediamtx and ffmpeg) **controls the app over the remote-control WebSocket**. It has device capability flags in `tests/utils/config.py`.
   - Once the Android app is wire-compatible, that whole harness becomes Android's acceptance suite.
   - In addition, about 70 Swift Testing suites in `MoblinTests/` can be ported as golden tests, e.g. `MpegTsSuite`, `AmfSuite`, `RtmpSuite`, `SrtSenderSuite`, `SrtSuite`, `AdaptiveBitrateSuite`, `RemoteControlSuite`, `MoblinkSuite`, `SettingsSuite` and `TwitchChatSuite`.

## 2. Corrections to `moblin_a_spec.md`

| Old spec | Replace with | Why |
|---|---|---|
| FFmpeg + librtmp for RTMP | Kotlin port of Moblin's RTMP client | Moblin does not use FFmpeg. It avoids FFmpeg's LGPL/GPL obligations and binary size. Enhanced-RTMP HEVC and Opus/FLV come for free. |
| libsrt via NDK in v1 | Kotlin port of `SrtSender` (default) | Moblin's default sender is in-repo Swift, and its pacing, retransmit and drop logic is what is tuned for IRL. libsrt is only needed for the post-v1 ingest server. |
| Oboe + C++ mixer, <10 ms audio | `AudioRecord` (48 kHz, `getTimestamp(TIMEBASE_MONOTONIC)`) | There is no mixer upstream, and audio latency is irrelevant next to the transport buffer. |
| Sub-100 ms SRT latency | SRT latency setting of 500–3000 ms (default 3000) | That is Moblin's default (`defaultSrtLatency`). Sub-100 ms glass-to-glass over bonded cellular is not achievable. |
| `KEY_PRIORITY=1` for real-time | `KEY_PRIORITY=0` (0 = real-time), `KEY_LATENCY=1`, `KEY_OPERATING_RATE` | The old values were wrong. |
| Thermal headroom drives ABR | ABR stays network-driven (SrtFight/Belabox). Thermal is a separate policy (status, fps/resolution step-down) | This matches upstream semantics. The thermal policy is an Android-only addition. |
| Single `app` module, `com.moblin.*` | Multi-module Gradle, own namespace | Pure-JVM modules let the ported tests run on the host. Using "moblin" as the package invites trademark confusion. |
| 80% coverage target | Ported golden suites plus the upstream Python system harness | Behavioural parity is the real bar. |

SurfaceView for the preview and Compose for the UI are kept from the old spec.

## 3. Target architecture

**Platform:** Kotlin 2.x, Jetpack Compose, minSdk **29**, target latest.
- minSdk 29 gives the Opus encoder, `AudioPlaybackCapture`, thermal-status listeners and a sane Camera2.

**Gradle modules** (all pure JVM unless marked Android):

```
:upstream            git submodule eerimoq/moblin pinned (reference, Web/ assets, tests/ harness)
:core:codable        Swift-Codable-compatible JSON for kotlinx.serialization (see §4)
:core:clock          host clock (System.nanoTime), PTS helpers, WrappingTimestamp
:media:mux           AMF0, FLV tags (incl. enhanced RTMP), MPEG-TS writer/reader, NAL/SEI utils, ADTS, OpusHead
:transport:rtmp      RTMP client (handshake, chunking, publish, auth retries, RtmpStreamInfo stats)
:transport:srt       SrtSender port (handshake v5 + streamid ext, pacing, NAK retransmit, drops)
:transport:srtla     SRTLA client (reg1/2/3, keepalive, congestion window, scoring, batching)
:transport:abr       AdaptiveBitrate base + SrtFight (fast/slow/custom) + Belabox
:net        (Android) MultiNetworkManager, WebSocketClient, HttpClient, HttpServer, NsdManager
:settings            Database schema subset, import/export zip, deep-link parser, migrations
:media:pipeline (Android) Camera2, AudioRecord, GL compositor, frame timer, MediaCodec, MediaMuxer
:effects    (Android) GL effect passes + bitmap-backed widgets
:remotecontrol, :moblink, :platforms:{twitch,kick,youtube}, :emotes, :obs
:app        (Android) Model controller, Compose UI, StreamingService (foreground service)
```

**iOS → Android mapping:**

| iOS | Android |
|---|---|
| `processorPipelineQueue` | Single `HandlerThread` "pipeline" (+ its own EGL thread for the GL render) |
| Per-transport `DispatchQueue`s | One single-thread executor or coroutine dispatcher each |
| AVCaptureMultiCamSession, NV12 | Camera2 → `SurfaceTexture` (OES) into the GL compositor; concurrent cameras post-v1 |
| `CVPixelBufferPool` + CoreImage/MetalPetal | EGL offscreen FBO chain (GLES 3.x). Text, image and chat widgets are drawn with `Canvas` into a `Bitmap`, then uploaded as a texture |
| `VTCompressionSession` | `MediaCodec` surface input; `eglPresentationTimeANDROID(pts)`. Live bitrate via `setParameters(PARAMETER_KEY_VIDEO_BITRATE)` |
| ABR DataRateLimits (1.2×) | VBR + `KEY_MAX_BITRATE` where supported; CBR option (behaviour varies by device — test matrix) |
| `AVAudioConverter` AAC/Opus | `MediaCodec` AAC-LC / Opus, re-chunked into 1024/960-sample frames (`AudioEncoderRingBuffer` port) |
| `NWConnection.requiredInterface` | `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR/WIFI/ETHERNET)` + `Network.bindSocket(DatagramSocket)`; keep the callbacks registered so cellular stays up while on Wi-Fi |
| `AVAssetWriter` (fragmented MP4) | `MediaMuxer` MP4 in v1; fragmented MP4 for crash-safety/replay post-v1 |
| UIBackgroundModes audio/voip | Foreground service of type `camera\|microphone\|connectedDevice` plus an ongoing notification (also replaces the Live Activity) |
| `UserDefaults` file + Keychain | JSON file in `filesDir` (same schema, secrets replaced by references); all secrets in `SecretStore` (Keystore + Tink, §9.3) |
| SwiftUI `ObservableObject` sub-models | `AppController` singleton plus many small `StateFlow` holders (mirror `Bitrate`, `Battery`, `StatusTopRight`… to keep recomposition cheap) |

**Timestamp rule:** all PTS are in the `System.nanoTime()` (MONOTONIC) domain.
- Camera `SENSOR_TIMESTAMP`: check `SENSOR_INFO_TIMESTAMP_SOURCE`. If it is REALTIME, subtract the (`elapsedRealtimeNanos` − `nanoTime`) offset.
- Audio PTS come from `AudioRecord.getTimestamp`.
- Port `DriftTracker`, `BufferedVideo` and `BufferedAudio` unchanged when the ingests arrive.

## 4. Wire-compatibility layer (`:core:codable`) — build first, with golden fixtures

It must reproduce Swift `JSONEncoder` defaults:
- Enums with payloads encode as `{"case":{"label":v}}`; payload-less cases as `{"case":{}}`. Use sealed classes plus a custom serializer.
- String raw-value enums are plain strings.
- Unknown enum values fall back to a default.
- UUIDs are uppercase strings.
- `Date` is a Double of seconds since **2001-01-01**.
- `Data` is base64.
- Dictionaries with enum keys become a flat `[k,v,k,v]` array (e.g. `filters`).
- nils are omitted (`explicitNulls=false`).
- Decoding sets `ignoreUnknownKeys` and default values everywhere (mirrors `container.decode(key, Type, default)` in `Common/Various/CommonUtils.swift`).

**Remote control** (`RemoteControl/RemoteControl.swift`, `RemoteControlStreamer.swift`, `RemoteControlAssistant.swift`, `RemoteControlRelay.swift`, `RemoteControlWeb.swift`):
- **Handshake:** `hello{apiVersion:"0.1",authentication{challenge,salt}}` → `identify{streamerId,authentication}` → `identified{result}`.
  - Hash = `b64(SHA256(b64(SHA256(password+salt))+challenge))`.
- **Keepalive:** app-level `ping`/`pong` every 30 s.
- `twitchStart` token: AES-GCM combined (nonce‖ct‖tag), key = SHA256(password).
- **Relay paths:** `/bridge/control/{id}`, `/bridge/data/{id}/{conn}`, `/streamer/{id}`.
- **Web control:** HTTP on port 80 plus WS on port+1. Serve the upstream-built `Moblin/RemoteControl/Web/` assets unchanged, together with the generated `/js/config.mjs` and the routes `/recordings.json`, `/recordings/*`, `/thumbnails/*` and `/snapshot`.

**Moblink:**
- The streamer runs a WS server on 7777 and advertises NSD `_moblink._tcp` with TXT `name`.
- It sends `startTunnel(address,port)`. The relay opens a UDP listener on Wi-Fi plus a UDP socket bound to cellular, and forwards payloads unchanged.

**Golden fixtures:**
- Taken from the upstream `RemoteControlSuite`/`MoblinkSuite`, the web frontend TS types (`WebRemoteControlFrontend/`) and the Python `moblin_assistant` client used by `tests/utils/moblin.py`.

**Ports** (`Various/Network/Ports.swift`) — keep the same defaults:

| Service | Port |
|---|---|
| Remote control assistant | TCP 2345 |
| Moblink | TCP 7777 |
| RTMP server | TCP 1935 |
| SRT | UDP 4000 |
| SRTLA | UDP 5000 |
| RIST | UDP 6500 |
| WHIP | TCP 8310 |
| HTTP proxy | TCP 8450 |

## 5. Phased execution plan

> **Progress (2026-10-19):**
> - Phase 0 is done, including the app icon, the licence check, the signed-release workflow,
>   F-Droid metadata text, the privacy policy and the in-app licences screen. The licences
>   screen uses the licensee report bundled in the APK instead of AboutLibraries, which avoids
>   a runtime dependency.
>   ktlint and Gradle dependency verification run in CI (detekt dropped as redundant for now).
> - Phase 1 is done. Its gate was met with ffmpeg/libsrt as the reference receivers instead of
>   mediamtx:
>   - The CLI streams over RTMP, SRT and SRTLA (via the ported SRTLA relay).
>   - Recordings decode strictly.
>   - ABR reacts correctly under a shaped bottleneck.
>   - SRT AES encryption is included.
> - Phase 2 is in progress. Verified on the emulator:
>   - capture → encode → SRT/SRTLA/RTMP at 720p30
>   - SRTLA bonding over cellular + Wi-Fi, with failover
>   - automatic reconnect
>   - encoder probe (hardware preferred; HEVC offered only with a hardware encoder, otherwise H.264)
>   - thermal status with a critical warning
>   - microphone selection with auto-switch to external mics
>
>   Still needs a physical phone: the 60-minute soak, A/V sync, thermal behaviour and OEM encoder
>   quirks.


Each phase ends with an exit gate. Each Kotlin file carries a header comment naming its upstream Swift source (`// Port of Moblin/Media/Srtla/Client/SrtlaClient.swift @97e26d2`). A `PORTING.md` table maps Swift files to Kotlin files so upstream changes can be tracked later.

### Phase 0 — Bootstrap
1. Create the repo in `E:\github\moblin-a` with `git init`.
2. Add upstream as a submodule at `upstream/`, pinned to `97e26d2`.
3. Move this plan to `docs/PORT_PLAN.md` and mark `moblin_a_spec.md` as superseded.
4. Set up the Gradle version catalog, the module skeleton from §3, ktlint/detekt, and CI with GitHub Actions running `./gradlew test lint assembleDebug`.
5. Add `NOTICE` with the Moblin MIT text.
6. Build `:core:codable` plus its golden tests, and `:core:clock`.

**Gate:** codable golden tests pass.

### Phase 1 — Pure-JVM transport core (`:media:mux`, `:transport:*`)
Port the following, keeping all constants verbatim:
- AMF0 and FLV, including enhanced RTMP `hvc1` and Opus FourCC.
- RTMP client: C0/C1/C2, chunk size 8192, csids 2/3/4/8/9, publish sequence, Adobe/needauth auth, the 100 MB unacked disconnect, and RTT/in-flight stats.
- MPEG-TS writer:
  - PIDs 0/4095/256/257
  - PCR on audio every ≤100 ms, PAT/PMT every 2 s
  - AUD + SPS/PPS(+VPS)
  - the **one-frame video delay with audio front-padding**
  - 7×188 payloads
- `SrtSender`:
  - induction/conclusion handshake, HSREQ `0x00010503`/flags `0xBF`, streamid ext type 5 with word byte-reversal
  - ≥2 ms pacing; audio-first NAK retransmit, once per RTT
  - drops at latency×1.5 (in flight) and ×1.25 (unsent); EWMA send rate; 5 s timeouts
- SRTLA client:
  - packet types `0x9000…0x9212`; the reg2→NGP→reg1→reg2→reg3 group flow
  - 1 s keepalive with 8-byte timestamp; window 20k (min 1k, max 60k), −100 per NAK, +1/+29 per ack
  - score = `window/(inFlight+1)` × priority phase-in between 10k–20k; batching flushes at >15 packets or 15 ms
- ABR:
  - SrtFight presets: Fast PIF 200 / 0.9 / spike 50 / min decrease 250k / step 100k; Slow 500 / 0.95 / 100 / 100k / 25k; Custom.
  - Belabox (belacoder) thresholds.
  - Start at 1 Mbps; transport cap `max(t+1M, 1.7t)`; tick every 20 ms (Belabox) or 200 ms (others).

Port the matching `MoblinTests` suites as JUnit tests. Add a JVM CLI (`tools/push`) that streams an ffmpeg-generated H.264/AAC elementary stream.

**Gate:**
- The CLI streams to mediamtx over RTMP and SRT, and over SRTLA through a test receiver on localhost.
- `ffprobe` shows clean timestamps.
- ABR reacts correctly under `tests/utils/lossy_udp_relay.py`.

### Phase 2 — Android capture → encode → send (`:media:pipeline`, `:net`)
- **Camera:** Camera2 with lens enumeration across physical and logical multi-cameras.
- **Compositor:** a GL compositor with a frame timer that ports the `makePresentationTimeStamp` drift rule, black-frame and gap-filler behaviour, and the monotonic-PTS drop.
- **Rendering fan-out:** render into the encoder surface, the SurfaceView preview and a low-fps JPEG.
- **Video encoder:**
  - MediaCodec H.264 (Baseline/Main/High) and HEVC (Main/Main10)
  - keyframe interval 2 s, B-frames off by default (when on, apply the DTS −0.15 s offset)
  - live bitrate changes from ABR
- **Audio:** `AudioRecord` with mic selection (built-in, USB and BT via `setPreferredDevice`), gain, mute, level meter (peak dBFS over 50 ms windows every 200 ms) and AAC/Opus encoding.
- **Network:** `MultiNetworkManager` exposes per-transport `Network` objects to SRTLA; it replaces `NWPathMonitor`.
- **Background:** `StreamingService` foreground service, a wake lock, and a thermal listener for status.

**Gate:**
- A phone streams 1080p30 H.264 over bonded SRTLA (cellular + Wi-Fi) for 60 min without A/V drift.
- Pulling a SIM or Wi-Fi recovers within 5 s.
- Bitrate follows the ABR under induced loss.

### Phase 3 — App shell, settings and camera controls (`:settings`, `:app`)
- **Settings:** port the `Database` subset needed for v1:
  - streams (all `SettingsStream` video/audio/SRT/RTMP/ABR fields and `connectionPriorities`)
  - scenes, widgets, quick buttons, zoom presets, bitrate presets, `show`, mics, remote control, Moblink, chat
- **Settings import/export:** `.moblinSettings` zip in both directions, clipboard JSON, and a `moblin://` intent filter handled by the `MoblinSettingsUrl` parser.
  - Ignore or map iOS-only fields: camera uniqueIDs, `recordingPath` bookmarks, SF Symbols → Material icon map.
- **UI** (Compose, mirroring `MainView`):
  - SurfaceView preview, left and right status stacks (each toggled by a `SettingsShow` flag), and a paged quick-button control bar with the stream button.
  - Gestures: tap-to-focus, long-press for auto focus, pinch zoom.
  - Portrait/landscape lock.
  - Settings tree: Streams (URL, Video, Audio, SRT, RTMP, Recording), Scenes, Display, Camera, Audio and Import/Export, including the "show all settings" gating.
- **Camera controls:** zoom presets per lens, manual focus/ISO/exposure/WB, torch with strength (API 33+), stabilization, lens switching.
- **Media capture:** recording with a separate encoder pair to MediaMuxer, and snapshots (ring of the last 3 frames, every 3 s).
- **Localization:** script `Common/Localizable.xcstrings` (2093 keys, 26 locales) → `values-xx/strings.xml`.

**Gate:** importing a real iOS export reproduces its streams, scenes and quick buttons, and all three stream protocols can be started from the UI.

### Phase 4 — Scenes and core widgets (`:effects`)
- **Layout:** port the `EffectUtils` layout math and the effect-ordering rules.
- **v1 widgets:**
  - Text: format placeholders, timers and stopwatch, location/speed
  - Image, QR code, Map (MapLibre, user-configurable style URL, §8.1)
  - Chat
  - Browser: offscreen WebView, plus a `moblin.js` bridge with the same JS API (`publish`, `subscribe(chat|speechToText)`, `message`) so existing overlays keep working
- **v1 global effects:** LUT (3D texture, `.cube`), grayscale, sepia, pixellate, 4:3, CRT, whirlpool and pinch as GLSL passes.
- **Scene switching:** blur transition and atomic effect-list swap on the next frame.

**Gate:** the upstream harness suites `scenes.py` and `browser_widget.py` pass (after Phase 6).

### Phase 5 — Chat and platforms (`:platforms:*`, `:emotes`, `:obs`)
- **Twitch:**
  - Anonymous IRC-over-WS chat read.
  - Helix send/moderation, viewers, title/category.
  - EventSub WS (follow, sub/notification, raid, cheer, redemptions, ad break); backoff up to 120 s.
  - OAuth via the **Device Code flow**, using **your own Twitch client ID**, with minimal scopes and revoke on sign-out (§8.1, §9.5).
- **Kick:** Pusher WS (public key from `KickPusher.swift`); login via WebView with the `session_token` cookie as Bearer; v1/v2 APIs.
- **YouTube:** innertube `get_live_chat` polling (≥200 ms) with no auth needed; AppAuth-Android with **your own Google OAuth client** for the Data API.
- **Emotes:** BTTV, FFZ (via BTTV) and 7TV.
- **OBS:** obs-websocket v5 (auth hash as above).
- **UI:** chat overlay, viewer counts and alert toasts.

**Gate:** the harness `chat.py` suite passes (it includes the Twitch EventSub mock), and live chat works on all three platforms.

### Phase 6 — Remote control and Moblink (`:remotecontrol`, `:moblink`)
- **Remote control:** streamer client, assistant server (port 2345, single streamer), relay client, web remote control server, the 1 s status loop and preview JPEGs.
- **Moblink:**
  - The relay comes first, because Android phones make ideal relays.
  - Then the streamer side, which feeds tunnels into the SRTLA remotes with `relayId` priorities.

**Gate:**
- Interop both ways with an iOS Moblin device as assistant, streamer and relay.
- Run the upstream harness against Android: add an Android device entry with the right `Capability` flags to `tests/config.toml`, then pass `stream`, `settings`, `scenes`, `mic`, `record`, `web_remote_control`, `chat` and `macros` (as implemented).
- Pass `just test-stability --duration 1` (1 h).

### v1 release gate
- All of the above.
- A 12-hour `test-stability` soak on 3 reference devices: Pixel (Tensor), Samsung S-series (Snapdragon) and a mid-range Exynos/MediaTek.
- Thermal behaviour at 1080p60.
- Play Store policy review for foreground-service types and background location.

### Post-v1 roadmap (in priority order)
1. **Ingest sources**, routed through ported `BufferedVideo`/`BufferedAudio`/`DriftTracker`:
   - RTMP server (pure Kotlin port, `RtmpServer/*`), RTSP client.
   - SRT(LA) server via an **NDK libsrt build** with Moblin's patches (the `srtlaPatches` option lives in `eerimoq/SrtSwift`'s libsrt fork).
   - SRT pull client.
   - Video-source widgets and concurrent cameras.
2. **Device integrations:**
   - **DJI BLE:** FFF4/FFF5 characteristics; frame `0x55,len,0x04,crc8` (init 0xEE, poly 0x31) + crc16 (init 0x496C, poly 0x1021); pair PIN "mbln".
   - GoPro BLE: Open GoPro protobuf.
   - Workout GATT sensors.
   - Wear OS companion (same type/data messages as `WatchProtocol.swift`).
3. **RIST** (librist NDK, BSD-2) and **WHIP/WHEP** (libdatachannel NDK, MPL-2.0).
4. **Effects:**
   - Face, blur and privacy effects (ML Kit face detection), background removal (ML Kit selfie segmentation).
   - Alerts, replay buffer (fragmented MP4), scoreboards, wheel of luck, PNG/VTuber.
   - Speech-to-text, TTS and translation (ML Kit).
   - Macros and auto scene switcher.
5. **Low-priority integrations:** Tesla (protobufs regenerate with protoc-kotlin), cat printer, SOOP/OSP, screen capture (MediaProjection), external display (`Presentation`), Android TV/USB UVC cameras.

## 6. Risks and open items
- **MediaCodec variance:**
  - Dynamic bitrate is ignored, or only applied at the next IDR, on some encoders.
  - HEVC encoders are missing on low-end SoCs.
  - Mitigation: a codec capability probe at startup, a per-device quirks table, and the device test matrix.
- **Camera timestamp base** differs per OEM: unify the clocks and verify with the harness `audio_video_sync.py`.
- **Cellular kept alive while on Wi-Fi:** some OEMs kill the network request in the background. Mitigation: the foreground service plus `NET_CAPABILITY_NOT_RESTRICTED` handling, tested per OEM.
- **Third-party services owned by eerimoq:** fully addressed in §8. The app depends on none of them by default.
- **Upstream moves fast** (daily commits): pin the commit, and periodically diff `upstream/` with `PORTING.md` to schedule re-syncs.

## 7. Verification (end to end)
1. **Unit tests:** `./gradlew test` runs the ported MoblinTests golden suites (mux, RTMP, SRT, SRTLA, ABR, codable, settings, remote control, Moblink, chat parsers) on the JVM.
2. **Transport bench:** the Phase 1 JVM CLI streams to `mediamtx` (config at `upstream/tests/utils/mediamtx.yml`). Validate with `ffprobe -show_packets` (monotonic PTS/DTS, PCR cadence) and introduce loss with `upstream/tests/utils/lossy_udp_relay.py`.
3. **Device tests:** `./gradlew connectedCheck` covers the pipeline (frame timer cadence, encoder config, multi-network binding).
4. **System acceptance:**
   - Use the upstream Python harness (`upstream/tests`, `just test --device <android>`) through the Android app's wire-compatible remote-control server.
   - Stability soak with `just test-stability`.
   - A/V sync with `tests/utils/audio_video_sync.py` and timecodes.
5. **Interop:** an iOS Moblin assistant controls the Android streamer, an Android Moblink relay bonds for an iOS streamer, and an iOS `.moblinSettings` file imports on Android.
6. **Field tests:** bonded cellular + Wi-Fi IRL walk tests on the 3 reference devices, checking the bitrate/RTT graphs in the status overlay.
7. **Independence check (CI):** a test fails the build if any author-owned host appears in the app module's code or resources: `mys-lang.org`, `moblin.app`, `api.moblin.app`, `moblinremote.com`, `eerimoq.github.io`. The only exceptions are attribution text in About/NOTICE and the user-selectable relay preset (§8.2).

## 8. Independence from author-owned services and open-source distribution

**Requirement (from you):** a public, free, open-source GitHub project that anyone can download and run, and that works with **zero dependency on services operated by eerimoq or by us**. Every network service is either:
- (a) the streaming platform itself (Twitch, Kick, YouTube),
- (b) local or peer-to-peer (LAN, NSD, the phone's own servers),
- (c) a community service the user can turn off, or
- (d) a server the user self-hosts, which this repo provides.

### 8.1 Inventory of upstream external dependencies and how each is handled

The inventory comes from grepping the upstream source for author-owned hosts, plus the integration deep-dive.

| Upstream dependency | Owner | Used for | Android port decision |
|---|---|---|---|
| `wss://moblin.mys-lang.org/moblin-remote-control-relay` (+ `assistant.html`) | eerimoq (server is **MIT, Go**: `eerimoq/moblin-remote-control-relay`) | NAT traversal between remote-control streamer and assistant | **Default off / empty URL.** See §8.2. Ship a Dockerfile + deploy guide (`server/relay/`) built from the MIT relay. The relay URL is a user setting. |
| `api.moblin.app` (`ModelMoblinWebsite.swift`: `/streamers/live`, challenge) | eerimoq (`moblin_website`, **no licence**) | Listing live streamers on moblin.app; uses **iOS App Attest**, so it is unusable from Android anyway | **Drop.** No equivalent. |
| Moblin Cloud (`ModelMoblinCloud.swift`, `moblin.app/dashboard`: tips, wheel of luck, song requests) | eerimoq (closed) | Viewer interaction dashboard | **Drop in v1.** It is only a `RemoteControlStreamer` pointed at a URL. Keep the generic "connect to custom remote-control server" ability, so a future open-source dashboard can plug in. |
| Twitch OAuth: client ID `qv6bnocuwapqigeqjoamfhif0cv2xn`, redirect `https://mys-lang.org/auth` | eerimoq's Twitch app | Helix, EventSub, chat send | **Register the project's own Twitch app.** Use the **Device Code Grant flow** (public client: no secret, no redirect URI, no web server). The user enters a code at twitch.tv/activate. The client ID is public and committed, and forks can override it in `gradle.properties`. Fallback: WebView with a `http://localhost` redirect intercepted in-app (the same pattern as upstream's WebView fallback), which also needs no server. |
| YouTube/Google OAuth client ID `863735387781-…` | eerimoq's Google project | Data API: broadcasts, viewer count | **Own Google Cloud project** with an Android OAuth client, which is bound to the package name + signing SHA-1. Forks and self-builds need their own client, so allow a client-ID override in settings. **Chat needs no OAuth** (innertube polling), so YouTube chat works for everyone. Make OAuth features optional. The `youtube` scope needs Google app verification (privacy policy + homepage on GitHub Pages) before more than 100 users can use it. |
| Kick | Kick | Chat (Pusher public key), API via WebView `session_token` cookie | Keep. No author dependency. Optionally add Kick's official OAuth later. |
| `moblin.app` links (chat-bot docs, wizard, go-live notification text, About), `discord.moblin.app`, `eerimoq.github.io/.../privacy-policy` | eerimoq | Help and docs links | Point to **our** GitHub repo/Pages docs and our privacy policy. Credit Moblin and eerimoq on the About screen with a link to the upstream repo (attribution only). |
| StoreKit in-app purchases (alternate icons, `Store`) | Apple | Monetization | **Drop.** The app is free; an optional donation link in README only. |
| `decapi.me` (Twitch avatars) | Third party | Avatars | Replace with Helix `users` → `profile_image_url` (already authenticated). |
| `raw.githubusercontent.com/id3adeye/kickicons` | Third party | Kick badge images | Bundle in-app if the licence allows, otherwise draw simple vector badges. No runtime fetch. |
| BTTV / FFZ / 7TV | Community | Emotes | Keep, with per-provider toggles (upstream already has them). |
| `pool.ntp.org` (TrueTime) | Public pool | Timecodes only | Keep as a user-editable default. |
| Map widget (Apple MapKit) | Apple | Map overlay | **MapLibre** with a user-configurable style URL. Default to a free no-key provider (e.g. OpenFreeMap). No Google Maps API key in the repo. |
| Weather (WeatherKit, post-v1) | Apple | Text widget | Open-Meteo (free, no key, open source). |
| Mobcam OBS plugin | eerimoq (**GPL-2.0**) | PC side of Mobcam | Implement only the wire protocol from MIT Moblin code. Never copy mobcam source. |

### 8.2 Remote control without any hosted relay
The remote control works with no cloud at all, in four tiers (documented in-app and in `docs/remote-control.md`):
1. **Same LAN / hotspot:** the assistant phone or PC runs the assistant server on port 2345 and the streamer dials `ws://<ip>:2345`. The built-in **web remote control** is served by the Android app itself (HTTP 80/81, NSD `_http._tcp`), so any browser on the LAN can control it with no server.
2. **Over the internet without a server:** recommend Tailscale or ZeroTier (both have free Android clients), then dial the assistant's tailnet IP. This needs no code from us; we only document it.
3. **Self-hosted relay:** `server/relay/` holds the MIT Go relay, vendored or submoduled, with its licence. It includes a `Dockerfile`, a `docker-compose.yml` with Caddy for automatic TLS, and one-click guides (Fly.io/Render free tier, any VPS). Users paste their relay URL into settings.
4. **Optional preset:** a settings dropdown may list eerimoq's public relay as an opt-in preset, labelled "community relay run by the Moblin author". Include it only if eerimoq agrees (§8.5); otherwise ship no preset.

The wire protocol stays identical to upstream, so the Android app also interoperates with iOS Moblin and with `eerimoq/moblin_assistant` (Python, MIT) on any of these paths.

### 8.3 Reuse eerimoq's existing open-source Android and server code
- **`eerimoq/Moblink`** (MIT): an existing **Android Kotlin Moblink relay app**. Reuse or adapt its relay and network-binding code for Phase 6 instead of writing it from scratch. This also gives a ready interop peer for testing.
- **`eerimoq/moblin-remote-control-relay`** (MIT, Go): the self-host relay for §8.2.
- **`eerimoq/moblin_assistant`** (MIT, Python) and **`eerimoq/systest_moblin`** (MIT): used by the system-test harness and pulled in through the `upstream/tests` requirements.
- **`eerimoq/srt`** (MPL-2.0 fork with the SRTLA patches), **`eerimoq/librist`** (BSD-2) and **`eerimoq/libdatachannel`** (MPL-2.0): the source forks for the post-v1 NDK builds.

### 8.4 Open-source project and distribution setup (added to Phase 0)
- **Repo licence: MIT** (the same as upstream).
  - `LICENSE` holds our copyright; `NOTICE` reproduces Moblin's MIT notice and lists bundled third-party licences.
  - An `About → Open source licences` screen is generated with the AboutLibraries Gradle plugin.
  - File headers on ported files cite the upstream path and commit.
- **Licence guardrails:**
  - Allowed: MIT/BSD/Apache, and MPL-2.0 for libsrt/libdatachannel (publish any changes to MPL files).
  - **No GPL/AGPL code in the app.** BELABOX `srtla` (AGPL) and mobcam (GPL) may be used as **external test tools only**, never linked.
  - No FFmpeg.
  - A CI licence check runs (Gradle `licensee` plugin).
- **FOSS-clean build:** **no Google Play Services/Firebase/ML Kit/Google Maps** in the main build, so the app is eligible for **F-Droid** and runs on de-Googled phones (GrapheneOS, LineageOS).
  - Location uses `LocationManager`, not FusedLocation.
  - Post-v1 face/segmentation effects use MediaPipe Tasks (Apache-2.0, bundled models) or a separate `gms` product flavour, never the default build.
- **Secrets:** none in the repo. Twitch Device Flow and Google Android OAuth clients have no secret. Optional per-build overrides go through `local.properties`.
- **Releases:**
  - A GitHub Actions workflow builds signed APKs (the signing key lives in repo secrets) on tags and attaches them to GitHub Releases, with `arm64-v8a` plus a universal APK.
  - Obtainium-friendly naming.
  - Add F-Droid metadata (`fastlane/metadata/android/...`).
  - Google Play is optional later. It needs the privacy policy and a foreground-service-type declaration video.
- **Project hygiene:** README (credits, a "not affiliated with Moblin" disclaimer, build instructions), CONTRIBUTING, issue templates, and GitHub Pages for the docs, privacy policy and OAuth verification pages.
- **Name:** pick a distinct app name and `applicationId`, e.g. `io.github.<you>.<name>`. "Moblin-A" is a working title, and using "Moblin" in the name needs eerimoq's OK (§8.5).

### 8.5 Courtesy contact with the author (recommended, not blocking)
Open a GitHub Discussion or comment on issue #149 announcing the port and asking about:
1. Use of the "Moblin" name (or a "for Moblin" style name).
2. Whether the public relay may be offered as an opt-in preset.
3. Whether eerimoq would link the project.

None of the plan depends on the answers. The defaults above assume "no".

### 8.6 Plan changes driven by this section
- **Phase 0:** add §8.4 (licence files, AboutLibraries, licensee check, FOSS-clean dependency rule, release workflow, F-Droid metadata, Pages site with privacy policy) and the independence CI test (§7 item 7).
- **Phase 3:** settings import maps the iOS `remoteControl.relay` URL as-is, but **does not** enable it automatically. Also drop the `moblinCloud`, `iconImage` and StoreKit fields.
- **Phase 4:** the Map widget uses MapLibre.
- **Phase 5:** Twitch auth uses the Device Code flow with our own client ID. YouTube chat needs no auth; YouTube OAuth is optional with a client override. Twitch avatars come from Helix.
- **Phase 6:** base the Moblink relay on `eerimoq/Moblink`. Add `server/relay/` (Docker + Caddy + guide) and `docs/remote-control.md` (the LAN, Tailscale and self-host tiers).
- **v1 release gate:** add these checks:
  - A fresh install with networking limited to the streaming endpoint, the platform APIs and LAN (author domains blocked in a test VPN/firewall) passes the full gate.
  - An F-Droid build-recipe dry run (`fdroid build`) succeeds.

## 9. Security guardrails for stream keys and credentials

### 9.1 What upstream does, and why the port must do better
- **Stream URLs, and the keys inside them, are plain JSON.** That covers the RTMP key in the path, the SRT `streamid` and `passphrase` query, and WHIP bearer headers. They sit in `settings` (`Library/SimpleStorage/settings`), and only Twitch/Kick/YouTube tokens go to the Keychain (`Various/Keychain.swift`). The `.moblinSettings` export contains every key in cleartext.
- **The web remote control (HTTP 80, WS 81) has no authentication.** Anyone on the same Wi-Fi, for example venue or hotel Wi-Fi, can go live, stop, switch scenes and fetch snapshots. (Its `getSettings` returns only names and IDs, so keys don't leak through it.)
- **Remote `importSettings` can overwrite all settings**, including stream URLs, from a remote assistant.
- **The pure-Swift `SrtSender` (our v1 port target) has no SRT encryption.** Passphrases work only via the libsrt "Official" path (`SrtSocketOption.swift`).
- The remote-control password hash is unsalted-per-session SHA-256 sent over `ws://` on LAN, so it can be brute-forced offline if sniffed.

### 9.2 Secret inventory (treated as `Secret` everywhere)
- **Stream credentials:** stream URL path and query (RTMP key, SRT `streamid`/`passphrase`, RIST secret), RTMP auth user/password, WHIP/WHEP auth headers, multi-stream destination URLs.
- **Platform credentials:** Twitch/YouTube access and refresh tokens, Kick `session_token` cookie.
- **App passwords:** OBS WebSocket password, remote-control and Moblink passwords, relay URLs with IDs.
- **Integration secrets:** Discord webhook URLs, OpenAI-compatible API key, TTS Monster key, RealtimeIRL key.
- **Device secrets:** Wi-Fi passwords sent to DJI/GoPro, Tesla private key.

### 9.3 Storage (`:core:secrets`, Phase 0)
- **`SecretStore` design:**
  - A non-exportable AES-256-GCM key in the **Android Keystore**, StrongBox-backed when available.
  - Secrets are encrypted with **Tink AEAD** (Apache-2.0, FOSS-safe). Don't use the deprecated `EncryptedSharedPreferences`.
  - Stored in a separate file from settings.
- **Split stream URLs:** the settings JSON holds the URL with its secret parts replaced by a reference, e.g. `rtmp://live.twitch.tv/app/{secret:UUID}`. The real value is injected only in memory when the stream starts.
- **iOS import:** importing an iOS settings file moves every detected secret into `SecretStore` and then scrubs the plaintext.
- **No backups:** set `android:allowBackup="false"` and `dataExtractionRules` to exclude everything. Keystore keys are device-bound anyway, so a restored backup would be unreadable.
- **Optional biometric gate:** the Keystore key can require `setUserAuthenticationRequired` + **BiometricPrompt** to *reveal or edit* a stream key. Streaming never needs the prompt.

### 9.4 Input and display
- Secret fields use `KeyboardType.Password` / `visualTransformation` masking with a reveal eye. The IME gets `IME_FLAG_NO_PERSONALIZED_LEARNING` and autocorrect off, so keyboards don't learn keys.
- **Paste helper:** a "Paste stream key" button parses a full URL and separates the key automatically.
- **`FLAG_SECURE`** on every screen that can show a secret: stream URL/key editors, deep-link/QR creator, settings import preview, OAuth screens. This blocks screenshots, screen recording, casting and recents thumbnails.
- **Nothing on screen leaks keys:**
  - The status overlay and the stream itself show the stream **name** and `scheme://host` only, never the path or query.
  - Errors are redacted the same way.
  - Upstream's stealth mode is kept.
- **Clipboard:** copying a secret sets `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) and clears the clip after 60 s.

### 9.5 Transport security
- **Plaintext warnings:**
  - RTMP sends the key in cleartext. Show a "key sent unencrypted" badge and suggest RTMPS when the platform supports it.
  - RTMPS uses the system trust store with full hostname verification. There is no "trust all" option; only a user-added CA via Android's user CA mechanism, if they choose.
- **SRT encryption in v1:** implement SRT AES encryption in the Kotlin `SrtSender`:
  - KMREQ/KMRSP in the conclusion handshake
  - PBKDF2-HMAC-SHA1 key derivation (salt, 2048 iterations)
  - RFC 3394 key wrap
  - AES-CTR payload, 128/192/256-bit
  - Interop-tested against libsrt/mediamtx with a passphrase. Until that lands, a passphrase URL is rejected with a clear error, never silently ignored.
  - This adds about 400 LOC to Phase 1 and gets golden tests from libsrt captures.
- **Network policy:** `network_security_config` sets `cleartextTrafficPermitted=false` for HTTP. Allowed exceptions: user-entered streaming schemes, LAN-only local servers, and `localhost` for the WebView OAuth fallback.
- **OAuth:**
  - Twitch uses the Device Code flow; YouTube uses AppAuth with PKCE. Tokens never leave `SecretStore`.
  - **Request only the scopes the enabled features need.** Upstream requests about 30 Twitch scopes up front; ask incrementally instead.
  - Signing out revokes the token at the provider (Twitch `/oauth2/revoke`, Google revoke).
- **Kick login:** the WebView uses its own data directory and has no JavaScript interfaces. Its cookies are cleared once `session_token` is captured into `SecretStore`.

### 9.6 Local servers and remote control
Applies to the web remote control, the remote-control assistant, Moblink, and the ingest servers.
- **All local servers are off by default**, listen **only on LAN/Wi-Fi/hotspot interfaces** (never cellular), and show a persistent status indicator while running.
- **Web remote control gets authentication without forking the upstream frontend:**
  - Enabling it generates a random 128-bit access token, shown as a URL + QR (`http://<ip>/?t=<token>`).
  - The HTTP server requires the token as a cookie or query.
  - It serves the generated `/js/config.mjs`, which already sets `websocketPort`, with the token embedded for the WS URL.
  - The WS server rejects upgrades without the token.
  - A "Regenerate token" button invalidates existing sessions.
- **Remote-control assistant and Moblink passwords** keep the upstream hash protocol (needed for iOS interop), with these additions:
  - minimum password strength, and a generated strong default
  - rate limiting and lockout on `wrongPassword` (5 attempts, then 60 s back-off per IP)
  - a recommendation, in the UI and docs, to use `wss://` (self-hosted relay with TLS) or Tailscale over the internet
- **Dangerous remote requests need on-device confirmation:** `importSettings`, `setStream`, and `whip` with a custom URL. They are **disabled by default** behind a "Allow remote settings import" toggle.
- **Ingest servers:** each one requires a stream key/passphrase. Default bind is LAN-only.

### 9.7 Settings export, deep links and QR codes
- **Export defaults to "without secrets"**: they are stripped, and the file is iOS-compatible.
- With secrets you have two choices:
  - **(a) Encrypted export (Android-only):** the zip is wrapped in AES-256-GCM with a passphrase-derived key (Argon2id or PBKDF2-SHA256 at ≥600k iterations).
  - **(b) Plaintext, iOS-compatible export:** only behind an explicit warning dialog.
- **Incoming `moblin://` links, QR scans and imported files are untrusted input:**
  - Validate them strictly.
  - Show a diff-style confirmation of exactly what will be added or changed, including "this link contains a stream key".
  - Never auto-start streaming.
  - Never overwrite an existing stream without "Merge/Create new" confirmation (upstream behaviour, kept).
  - Reject imports while live.
- The deep-link/QR creator warns that the QR contains the key and is shown under `FLAG_SECURE`.

### 9.8 Logs, diagnostics and WebViews
- **One redaction layer** (`Redactor`) is applied to every logger sink: logcat, the in-app log, remote-control `log` events and log export.
  - URLs are reduced to `scheme://host/***`.
  - Known secret patterns (Bearer, oauth:, `streamid=`, `passphrase=`, Discord webhook paths) are masked.
  - Every `SecretStore` value is masked by exact match.
- **No analytics, telemetry or crash-reporting SDKs.** Crash logs stay local and are redacted.
- **Browser-widget WebViews (user-supplied URLs):**
  - The `moblin.js` bridge exposes only chat and speech-to-text messages, never settings or secrets.
  - `allowFileAccess=false` and `allowContentAccess=false`, with no `file://` URLs.
  - Safe Browsing is not forced, which keeps the build FOSS-clean.

### 9.9 Supply chain and project security
- **Build integrity:** Gradle dependency verification (`verification-metadata.xml`) and version catalog pinning, with Dependabot/Renovate keeping them current.
- **CI scanning:** CodeQL (Kotlin) and **gitleaks** on every PR, plus the licence check (§8.4).
- **Releases:**
  - Signed with a key held only in GitHub Actions secrets.
  - SHA-256 checksums are published.
  - Reproducible builds are targeted for F-Droid verification.
- **Disclosure:** `SECURITY.md` with a private vulnerability-reporting channel (GitHub Security Advisories).
- **Threat model:** `docs/threat-model.md` covers device theft, a hostile LAN, a malicious QR/deep link, a malicious browser-widget page, a sniffed remote-control session and leaked screenshots or streams. Each guardrail above maps to a threat.

### 9.10 Where this lands in the phases and how it's verified
- **Phase 0:** `:core:secrets`, `Redactor`, backup rules, network security config, CI gitleaks/CodeQL, `SECURITY.md` and the threat model.
- **Phase 1:** SRT encryption in `SrtSender`, plus its interop test.
- **Phase 3:** masked fields, FLAG_SECURE, the biometric reveal, the secret-splitting migration on iOS import, and export modes.
- **Phase 5:** minimal OAuth scopes and token revoke.
- **Phase 6:** the web-control token, lockout, LAN-only binding and remote-import confirmation.
- **Tests:**
  - **Unit:**
    - The redactor masks every inventory type.
    - Export without secrets contains no `SecretStore` values: scan the output JSON for each value.
    - iOS import scrubs plaintext.
    - The SRT KM handshake matches libsrt vectors.
  - **Instrumented:** FLAG_SECURE is set on the secret screens; the settings file on disk contains no secrets after import (adb pull + grep).
  - **System:**
    - Web control without the token gets 401/close.
    - Wrong-password lockout triggers.
    - A deep link with a key shows the confirmation and does not start the stream.
    - A packet capture of an SRT-with-passphrase stream shows encrypted payloads.
  - **v1 release gate adds:** all of the above, plus a manual checklist review of `docs/threat-model.md`.
