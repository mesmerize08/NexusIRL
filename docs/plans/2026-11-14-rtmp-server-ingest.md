# Plan: RTMP server ingest (port of Moblin's RTMP server)

**Status:** proposed, awaiting the maintainer's answers to the open questions (section 10). Based
on reading upstream `97e26d2` and NexusIRL `main` on 2026-11-14; nothing is built yet.

**Goal:** the phone accepts an RTMP publish (from OBS, a GoPro, a drone or another phone) and uses
it as a scene's video source and, optionally, as the audio source, the way Moblin does, with
Moblin-compatible settings.

## 1. What upstream does

**Server** (`upstream/Moblin/Media/RtmpServer/`):

- `RtmpServer.swift`: one listener on `0.0.0.0:<port>`, serial queue. A 3 s timer drops clients
  idle for more than 10 s and restarts a failed listener. A second publisher with the same key
  replaces the first ("Same stream key"). There are no limits on connections, message sizes or
  chunk streams.
- `RtmpServerClient.swift`: version-3 handshake only. A chunk state machine that reads exact
  sizes (csid 0/1 rejected). Acks every window size (2,500,000 by default). Default latency
  2000 ms.
- `RtmpServerChunkStream.swift`:
  - `connect` must use app `/live`. The server replies with WindowAckSize and SetPeerBandwidth
    2.5M, SetChunkSize 1024 and `NetConnection.Connect.Success`.
  - `publish(key)` must match a configured, non-empty key.
  - Video: legacy AVC (7) and HEVC (12), and enhanced RTMP `hvc1`, with a signed 24-bit
    composition time. Audio: **AAC only** ("Only AAC is supported").
- Timestamps:
  - `pts = (ts − zero) + base + cts + latency`, where `base` is the host time at the first
    media message.
  - `zero` is per chunk stream: a quirk that can leave a fixed A/V offset.

**Model** (`ModelRtmpServer.swift`):

- On publish start and stop: toasts, then add or remove the buffered video and audio for the
  stream id, and switch the mic if needed.
- `reloadRtmpServer()` restarts the server when its settings change.
- Each stream is listed as a video source named "<name> (RTMP)".

**Buffered sources** (`HaishinKit/Media/`):

- `DriftTracker` measures fill level against the latency every 20 s and corrects for drift.
- `BufferedVideo` queues frames by PTS and lets the frame timer pick the one for its output time
  (at most 200 queued).
- `BufferedAudio` has syncing and normal modes, at most 300 buffers, outputs zeroed silence in
  gaps, and runs its own output timer.

**Settings** (`SettingsIngests.swift`):

- `rtmpServer {enabled=false, port=1935, streams=[]}`.
- Each stream: `{id, name="My stream", streamKey, latency=2000}`.
- Top level: `ingestsSoftwareVideoDecoding=false`.
- Validators: latency 5–10000 ms, port 1–65535. New keys come from `randomHumanString()`.

**Scenes and mics:**

- Scene keys: `cameraPosition:"RTMP"`, `rtmpCameraId`, `overrideMic`, `micId`.
- Network mics are named "<name> (RTMP)", with id `"<uuid> 0"`. They are inserted at the top of
  the mic list, so auto-switch picks them.

**Tests:**

- Upstream suites: `DriftTrackerSuite` (11 tests), `BufferedVideoSuite` (2),
  `BufferedAudioSuite` (6), and the latency and port validators. There is no RTMP server unit
  suite.
- Harness: `tests/suites/ingests.py`, using port 11935, key `"1"` and `topRight.rtmpServer`.

## 2. What NexusIRL has

- **`transport/rtmp`:**
  - Reusable: AMF0 (with depth and array limits), chunk encoding, FLV constants and bitrate
    stats. The `RtmpStream` client can act as the publisher in tests.
  - `RtmpChunkReader` is not safe for untrusted input: it has no size limits and copies data
    again on every read, which grows quadratically.
- **`media/mux`:**
  - avcC/hvcC/ASC parsers exist.
  - `addNalUnitStartCodes` has no length checks. A length of `0xFFFFFFFC` makes it loop forever.
  - The avcC parser ignores `lengthSizeMinusOne`.
- **`media/pipeline`:**
  - One GL thread with an absolute-schedule frame timer. The camera feeds an OES `SurfaceTexture`.
  - `createSurfaceOverlay` can turn a `Surface` into an overlay, which suits a later
    video-source widget.
  - The pipeline is re-created when the canvas changes. Audio has one source (`AudioRecord`) at
    48 kHz, with no resampler.
- **`settings`:**
  - `rtmpServer` already round-trips through `extra`.
  - `"RTMP"` maps to the back camera.
  - `streamKey` is already moved into the secret store.
  - Import keeps the local `allowSettingsImport`; `rtmpServer.enabled` needs the same treatment.
- **Security patterns to copy:**
  - `RemoteControlWeb` (bounded pool, peer check, deadlines; audit NET-01, `dc489cd`) and the
    WebSocket message limits (SEC-04, `099e990`).
  - `isAllowedLocalAddress` refuses cellular and checks that the peer is local; `localAddresses()`
    gives the addresses to show as URLs.
- **Remote control:** `RemoteControlStatusTopRight.rtmpServer` exists. `targetSdk` is 36, so no
  local-network permission is needed yet.

## 3. Scope

**MVP:**

1. Moblin's settings keys and defaults, round-tripping with iOS.
2. Publishes to `rtmp://<lan-ip>:<port>/live/<key>` with H.264 or HEVC (legacy and enhanced) and
   AAC. Other codecs end the session with upstream's messages.
3. An RTMP stream as a scene's base source, black until the first frame. The built-in camera is
   released while that scene is selected.
4. Network mics "<name> (RTMP)" (`"<uuid> 0"`), chosen by hand or through the scene's
   `overrideMic`/`micId`.
5. Upstream timing: base plus latency, with `DriftTracker`, `BufferedVideo` and `BufferedAudio`
   ported.
6. Settings and stream screens, RTMP streams in the scene camera picker, a status badge,
   `topRight.rtmpServer`, and connect/disconnect toasts.
7. The hardening in section 6.

**Later:**

- Video-source widget (picture in picture)
- Opus and other audio codecs
- DJI/GoPro hookup
- Decoding only while a stream is used
- SRT(LA), RTSP and RIST ingests on the same buffered path

## 4. Architecture

- **`:transport:rtmp`, package `server/` (pure JVM):**
  - `RtmpServer`: accept loop, admission, registry, newest-publisher-wins, 3 s housekeeping.
  - `RtmpServerClient`: handshake, a blocking chunk state machine that reads exact sizes, AMF
    commands, auth, acks, timestamp mapping.
  - `RtmpServerLimits`.
  - `FlvTagParser`: pure, with typed failures.
  - `RtmpServerTypes`: settings with keys already resolved, and a delegate.
- **`:media:mux`:** a validated `lengthPrefixedToAnnexB` (returns null on bad data), and
  `nalLengthSize` from avcC/hvcC.
- **`:media:pipeline` `ingest/` (pure, JVM-tested):** `DriftTracker`, `BufferedStats`,
  `BufferedVideo<F>`, `BufferedAudio` (with the output timer split out), `IngestJitterBuffer`
  (compressed, keeps the last GOP) and `PcmResampler` (to 48 kHz).
- **`:media:pipeline` (Android):**
  - `IngestVideoDecoder` (async MediaCodec into a `SurfaceTexture`, with a capability check and
    software fallback), `IngestAudioDecoder` and `IngestSource`. A source outlives pipeline
    re-creation.
  - `VideoPipeline`: buffered sources and source selection.
  - `AudioPipeline`: a microphone or buffered source switch.
- **`:settings`:** `SettingsRtmpServer`/`Stream`, `ingestsSoftwareVideoDecoding`, scene
  `rtmpCameraId`, `overrideMic` and `micId`, `SceneCamera.RTMP`, validators.
- **`:app`:** `IngestController` (lifecycle, keys from the secret store, toasts, active sources,
  stats), the server and stream screens, and the scene, mic and remote-control status wiring.
- **Threads, all bounded:**
  - one accept thread;
  - a client pool of at most 8 (`SynchronousQueue`);
  - one serial `rtmp-server` executor;
  - per active source, a video and an audio decoder thread;
  - one audio output thread;
  - the existing GL thread.

## 5. Decoding and timing

- **Timestamp zero:** per client instead of per chunk stream, a recorded deviation (open
  question 4). The 32-bit wrap is handled.
- **Two video stages:** Android cannot hold seconds of decoded frames.
  - Compressed frames wait in the jitter buffer and are fed to the decoder when
    `dts + drift ≤ outputPts + decodeAhead` (150–500 ms, adaptive). At most 6 frames are held
    decoded.
  - `BufferedVideo` selects which decoded output buffer to release to the surface.
  - There is a constant one-frame latch lag.
- **`DriftTracker` fill level:** uses the newest PTS in either stage, so it does not "correct" by
  the jitter depth. A unit test checks this.
- **Format changes:** a new size or codec recreates the decoder. Same size: the new parameter
  sets go in-band.
- **Audio:** MediaCodec AAC, then resampled to 48 kHz and the pipeline's channel count in
  1024-frame chunks, then `BufferedAudio`. An output thread on a host-clock grid feeds the same
  encoders. Gaps become silence.

## 6. Security and limits

- **Off by default.** An import never enables the server: the device keeps its own value (open
  question 1).
- **Binding:** wildcard address. The peer check (`isAllowedLocalAddress`, which refuses cellular)
  runs before any byte is read. Only LAN addresses are shown as URLs.
- **Authentication:**
  - A stream key is required; empty keys never match.
  - Keys are compared in constant time and never logged.
  - A wrong key closes the connection silently.
  - 5 failures from one address lock it out for 60 s.
- **Bounds:**

| Item | Bound |
|---|---|
| Connections | 8 total, 4 unauthenticated |
| Handshake deadline | 5 s |
| Publish deadline | 10 s |
| Idle timeout | 10 s |
| Read timeout | 3 s |
| Chunk streams | csid 2–63, at most 16 |
| Message size | 64 KiB before publish and for commands; 64 KiB audio; 8 MiB video; 16 MiB partial data per connection |
| Jitter buffer | `min(heap/8, 64 MiB)`, at most 1200 frames |
| Video size | within the decoder's capabilities and ≤ 3840×2160 |
| Decoding sources | 2 at once |

- **Failures:** nothing thrown on a client or codec thread escapes; each failure ends that
  session with a reason.
- **Visibility:** a status badge while the server runs, and a note in the UI that RTMP keys
  travel unencrypted on the LAN.

## 7. Tests

- **JVM:**
  - The upstream buffered suites with their vectors copied verbatim.
  - New tests for the jitter-fill drift rule, release accounting, the resampler, the jitter
    buffer, settings round-trip and import behaviour, and validators.
  - `lengthPrefixedToAnnexB`, including the termination case.
  - `FlvTagParser`.
  - Loopback `RtmpServerTest` (bad key/app, same key, timeouts, slowloris, admission limits,
    oversized messages, lockout, threads after stop, no key in logs).
- **ffmpeg interop:** `RtmpServerInteropTest` publishes H.264 with B-frames plus AAC, and HEVC
  (enhanced RTMP), then decodes the collected stream. A wrong key must fail.
- **Instrumented:** decode fixtures through `IngestSource`; pipeline buffered source and
  re-creation; audio buffered mode.
- **Real phones:**
  - OBS over Wi-Fi to the phone, and the phone's own hotspot. Confirm the server can't be reached
    over cellular.
  - 30 minutes live with an RTMP scene, checking thermals and A/V sync.
  - Reconnects, and background behaviour.
  - The upstream harness ingest suites.

## 8. Stages (each mergeable on its own)

1. Pure buffered core and the upstream suites.
2. Settings.
3. Safe parsers.
4. RTMP server protocol core (JVM).
5. ffmpeg interop.
6. Android decoders.
7. Pipeline integration.
8. App wiring.
9. UI.
10. Acceptance and docs (PORTING.md, CHANGELOG, threat model).
11. Later items.

## 9. Risks

- **MediaCodec decoder variance:** buffer counts, in-band parameter changes, HEVC support,
  low-latency mode.
- **Heat:** decoding, compositing and encoding at the same time.
- **A/V sync:** the timestamp zero, the latch lag, resampler delay, the clock domain when
  switching sources.
- **Memory:** `largeHeap` is off.
- **Pipeline re-creation** during an ingest.
- **Background lifetime** of the server.
- **Future `targetSdk` 37:** may need a local-network permission.
- **Harness defaults:** the upstream harness imports `enabled:true` and key `"1"`, which conflicts
  with the hardening.

## 10. Open questions for the maintainer

1. When an imported file sets `rtmpServer.enabled=true`: always keep the device's own value
   (recommended), or allow it after on-device confirmation?
2. Weak stream keys (the harness uses `"1"`): accept them with a warning (recommended), or enforce
   a minimum length?
3. Server lifetime: only while the app is visible, live or recording (recommended for the MVP), or
   whenever enabled, as upstream does?
4. Timestamp zero per client (recommended), or upstream's per-chunk-stream quirk?
5. Switch to RTMP audio automatically on connect, as upstream does with auto-switch?
6. Release the built-in camera while an RTMP scene is selected, as upstream does?
7. At most 2 sources decoding at once in the MVP?
8. Accept enhanced-RTMP Opus audio, which upstream rejects?
9. Is the video-source widget needed in the first release?
