# Review of the NexusIRL pre-release audit

**Reviewed:** `docs/audit/RELEASE_READINESS_AUDIT.md`, against commit `b9eee46` and the pinned upstream Moblin (`97e26d2`).
**Method:** Four independent reviews of the source: security, media and recording, network and transport, release and QA. Every finding was traced in the code. SEC-01 was reproduced with a temporary unit test that planted marker values. No tracked file was changed.

## Status (November 2026)

Every finding below was fixed in **v0.5.0**, except MED-04, which is left as it is in upstream
Moblin (about 52 days of continuous streaming before the sequence number matters).

| Finding | Fixed by |
|---|---|
| SEC-01 | `97ee635` every credential kept out of `settings.json` and plain exports, with migration |
| MED-01 | `603866d`, `49f333a` failed writes are reported; a cut-short file is kept and labelled |
| NET-01 | `dc489cd` bounded web remote workers and a request-head deadline |
| MED-03 | `8f33d31` LUT size and dimensions checked before decoding |
| PERF-01 | `7708fba`, `3717704` GL hand-off deadline; go live, stop and record off the main thread |
| MED-02 | `8a92102` RTMPS handshake timeout |
| SEC-04 | `099e990` message size limits on the local WebSocket servers |
| SEC-03 | `9b8b0c9` the peer must be local too, and unknown interfaces are refused |
| SEC-02 | `bb9f759` loopback means `localhost` or a loopback literal |
| REL-01 | `9c69ed8`, `da03471` releases only from CI-tested commits on main, ffmpeg required, every APK's signer checked |
| QA-01 | `fb890c9` hung ffmpeg killed at its deadline |
| RTMP queue | `f664af8` unsent bytes limited to an eighth of the heap, at most upstream's 100 MB |

## Verdict

**Agree: not ready for a broader public beta.**
- All 13 findings point at real code.
- The audit's version, toolchain and test-count facts are accurate.
- It **understates the credential leak (SEC-01)** and **misses three crash paths**.
- Several severities are too high: NET-01, SEC-02 and MED-04.

## Findings

| ID | Audit | Review | Notes |
|---|---|---|---|
| SEC-01 Credentials in "Export without keys" | High | **High, wider than claimed** | Reproduced. About 20 kinds of upstream credential field leak, not only the OBS password (see below). |
| MED-01 Failed recording reported as saved | High | **Medium** | Confirmed. A full disk publishes an unplayable MP4 and shows "Recording saved". Upstream writes fragmented MP4 and keeps most of the recording; NexusIRL loses all of it. |
| NET-01 Unbounded web remote HTTP workers | High | **Medium** | Confirmed, pre-authentication. Off by default and LAN, hotspot or tailnet only. **Missed:** thread exhaustion throws an uncaught `OutOfMemoryError` that kills the app mid-stream. |
| MED-03 LUT size checked after allocation | Medium | **Medium** | Partly correct. **Missed, and worse:** a small PNG claiming huge dimensions inside a shared `.moblinSettings` passes the byte caps, then runs out of memory on the LUT thread. If that LUT is enabled, the app crashes on every launch. |
| PERF-01 GL handoff waits without a deadline | Medium (hypothesis) | **Medium, confirmed** | Starting and stopping the stream or a recording runs on the main thread. Stopping can block about 8 s even without an EGL stall, which exceeds Android's 5 s "not responding" (ANR) threshold. |
| MED-02 Silent TLS peer leaks a thread | Medium | **Low–Medium** | Confirmed. No `soTimeout` before `startHandshake()`. Trigger: `rtmps://` pointed at a plain RTMP port. |
| SEC-04 WebSocket frame size | Medium (hypothesis) | **Low–Medium** | The CVE was not verified offline. No `maxFrameSize` is set. The Moblink and assistant servers read frames before app-level authentication. |
| SEC-03 "Local only" checks the local address only | Medium | **Low–Medium** | Confirmed. It also fails open when the interface is unknown, and contradicts the privacy policy. Upstream iOS has no restriction at all. |
| SEC-02 `127.` hostname prefix accepted as loopback | Medium | **Low** | Confirmed. Needs an attacker-controlled hostname in a URL the user configured. |
| REL-01 Release workflow gates | Medium | **Medium** | Confirmed. No ffmpeg, so interop tests can skip. `tee` hides `apksigner` failures because the step lacks `pipefail`. The signer is never compared. Any `v*` tag publishes. |
| MED-04 SRT sequence numbers use the 32-bit range | Low | **Low** | Same as upstream. **Correction:** the initial sequence number is below 10,000, not random up to 2³¹, so it takes about 52 days at 5 Mbps. |
| QA-01 ffmpeg fixture timeout | Low | **Low** | Confirmed. **Missed:** a second instance in `FfmpegInteropTest.kt`, where `exitValue()` is called without checking `waitFor`. |

### Not in the audit
- **RTMP queue:** up to 100 MB of video is held on the Java heap per destination before disconnecting. This matches upstream, but on Android two or more stalled multistream destinations can run out of memory, since the app does not request a large heap (`largeHeap` is off).

### Audit inaccuracies
- It says 17 Gradle modules; there are 18.
- Its SRT rollover-within-minutes scenario cannot happen, because of the initial sequence number.
- It does not note that the upgrade path is safe: v0.4.0 used versionCode 6, and the new per-ABI codes are 60–63, or 70–73 after the bump.

### Controls confirmed
- Tink/Keystore secret store
- `FLAG_SECURE` in release
- Backups disabled
- Non-exported `FileProvider`
- Constant-time web token check, with an HttpOnly, SameSite=Strict cookie

## SEC-01: what leaks

These are kept in plain text in `settings.json` and included in "Export without keys":
- OBS password and URL user info
- RealtimeIRL key
- Discord go-live webhook
- Open Streaming Platform URL
- AI and TTS Monster keys (chat and alert widgets)
- Browser widget URLs
- Tesla private key
- Moblin Cloud key
- Saved Wi-Fi passwords
- DJI and GoPro Wi-Fi passwords and RTMP URLs
- Ingest stream keys and URLs
- Deep-link creator URLs and passwords
- Legacy SRTLA relay password
- Saved remote-control URLs and relay bridge IDs

These are already protected: stream URLs, Twitch and Kick tokens, Discord snapshot webhooks, multistream URLs, and the remote control and Moblink passwords.

**Fix direction:**
- Treat credentials by key across the whole JSON tree, not by a list of named fields.
- Strip them on export without keys.
- Move them into the secret store on import, and migrate existing settings on start.

## Recommended order

1. **SEC-01:** whole-tree credential protection, migration of existing settings, and a marker regression test.
2. **Crash paths:** bound the web remote workers with an admission check, total header deadline and `Throwable` handling; set WebSocket frame limits; check LUT image dimensions before decoding, on every import path.
3. **Recording integrity:** count only successful writes, and report a failed `stop()` as a failure.
4. **ANR:** move stream, recording and encoder start/stop off the main thread.
5. **Hardening:**
   - fail-closed release workflow (ffmpeg, `pipefail`, signer digest, CI gate)
   - RTMPS handshake deadline
   - loopback-literal check
   - peer-side local check
   - ffmpeg fixture timeouts

Items 1–3 can be fixed and verified without a phone. After that, re-run the gate on the release candidate, then the audit's device matrix and a field test.
