# RTMP ingest, stages 4–5: the RTMP server protocol core and ffmpeg interop — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pure-JVM RTMP server that accepts a publish from OBS, ffmpeg or another phone the way
Moblin's does, delivers the stream's video and audio tags with host timestamps, and survives
hostile clients on the LAN.

**Architecture:** Three layers in `transport/rtmp`, package `server`, each testable alone.
`RtmpChunkDecoder` turns untrusted bytes into whole RTMP messages within strict limits.
`RtmpServerSession` is one connection's protocol (handshake, commands, acks, timestamps, FLV tags)
with no sockets: bytes in, bytes and events out. `RtmpServer` owns the sockets: accept loop,
admission, peer check, deadlines, lockout, newest-publisher-wins, and one serial executor that
delivers events. Nothing is wired into the app yet (stages 6–9).

**Tech Stack:** Kotlin/JVM, `java.net` blocking sockets, existing `Amf0Encoder`/`Amf0Decoder`,
`RtmpCommand`, `encodeChunks`, `FlvTagParser` (stage 3) and `core:clock`.

**Spec:** [docs/plans/2026-11-14-rtmp-server-ingest.md](2026-11-14-rtmp-server-ingest.md),
sections 1, 4, 5, 6, 7 and the decisions in section 10. Upstream sources:
`upstream/Moblin/Media/RtmpServer/RtmpServer.swift`, `RtmpServerClient.swift`,
`RtmpServerChunkStream.swift` @97e26d2.

## Global Constraints

- Ported files start with `// Port of <upstream path> @97e26d2.` and get a row in `docs/PORTING.md`.
- Pure JVM, `transport/rtmp` only; `./gradlew test` runs `checkAndroidApi`: only Java APIs that
  Android 10 (API 29) has.
- Upstream protocol values verbatim: app path `/live`; RTMP version 3; S1 = 8 zero bytes + 1528
  random bytes; S2 = C1 bytes 0–3, 4 zero bytes, C1 bytes 8–1535; replies to `connect` in order
  WindowAckSize 2,500,000, SetPeerBandwidth 2,500,000 (limit type 2, dynamic), SetChunkSize 1024
  (then send with chunk size 1024), `_result`; default window from the client 2,500,000;
  default chunk size 128 both ways.
- Upstream close reasons verbatim where they exist (table in Task 2), except that no reason,
  log line or exception ever contains a stream key.
- Spec §6 bounds (defaults of `RtmpServerLimits`): 8 connections, 4 unauthenticated; handshake 5 s,
  publish 10 s, idle 10 s, read timeout 3 s; chunk stream IDs 2–63, at most 16; message size 64 KiB
  before publish and for commands, 64 KiB audio, 8 MiB video; 16 MiB partial message data per
  connection; 5 failed keys from one address lock it out for 60 s.
- Keys: never empty, compared in constant time (`MessageDigest.isEqual` on UTF-8 bytes).
- Timestamps: zero per client, not per chunk stream (decision 4); 32-bit RTMP timestamps unwrap.
  `dts = (ts − zero) + base + latency`, `pts = dts + compositionTime`, audio `pts = (ts − zero) +
  base + latency`, all in host milliseconds; `base` = host clock (ms) at the client's first media
  message.
- Gate before each commit: `NEXUSIRL_REQUIRE_FFMPEG=1 ./gradlew ktlintCheck test lint :app:licensee assembleDebug assembleRelease -q`
  and `sh tools/check-independence.sh`; commit only when both exit 0 (`if <gate>; then git commit …; fi`).
  Stage explicit paths. Trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A slow or silent client** (slowloris: one byte every 2 s; TCP connect and nothing else):
   closed at the handshake or publish deadline, and never holding one of the 4 unauthenticated
   slots for longer. Pinned in Task 3.
2. **A message length near 16 MiB or many chunk streams with half-sent messages**: closed at the
   size and partial-data limits before the memory is allocated. Pinned in Task 1.
3. **A second publisher with the same key** (OBS reconnects before the old TCP times out): the new
   one wins, the old one is closed with "Same stream key", and the listener sees stop then start.
   Pinned in Task 3.
4. **Timestamps that wrap at 2³² ms or start near the top** (long-running encoders): times keep
   increasing across the wrap. Pinned in Task 2.
5. **A wrong key repeated from one address**: after 5 tries the address is refused for 60 s
   without a handshake, other addresses are unaffected, and no key appears in any log or reason.
   Pinned in Task 3.

---

## File map

| File | Responsibility |
|---|---|
| `transport/rtmp/src/main/kotlin/io/github/mesmerize08/nexusirl/transport/rtmp/server/RtmpServerTypes.kt` | Settings, limits, media events, listener interface |
| `.../server/RtmpChunkDecoder.kt` | Bounded incremental chunk parser |
| `.../server/RtmpServerSession.kt` | One connection's protocol, no sockets |
| `.../server/RtmpServer.kt` | Sockets, admission, deadlines, lockout, registry |
| `transport/rtmp/src/test/kotlin/io/github/mesmerize08/nexusirl/transport/rtmp/server/RtmpChunkDecoderTest.kt`, `RtmpServerSessionTest.kt`, `RtmpServerTest.kt`, `RtmpServerInteropTest.kt` | Tests |
| `docs/PORTING.md` | Rows for the ported files |

---

### Task 1: Types and the bounded chunk decoder

**Files:**
- Create: `.../server/RtmpServerTypes.kt`, `.../server/RtmpChunkDecoder.kt`
- Test: `.../server/RtmpChunkDecoderTest.kt`
- Modify: `docs/PORTING.md`

**Interfaces:**
- Produces, in `RtmpServerTypes.kt`:

```kotlin
data class RtmpServerStream(val id: UUID, val name: String, val streamKey: String, val latencyMs: Int)
data class RtmpServerSettings(val port: Int, val streams: List<RtmpServerStream>)
data class RtmpServerLimits(
    val maxConnections: Int = 8, val maxUnauthenticated: Int = 4,
    val handshakeTimeoutMs: Long = 5_000, val publishTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 10_000, val readTimeoutMs: Int = 3_000,
    val maxChunkStreams: Int = 16, val maxMessageBeforePublish: Int = 64 * 1024,
    val maxCommandMessage: Int = 64 * 1024, val maxAudioMessage: Int = 64 * 1024,
    val maxVideoMessage: Int = 8 * 1024 * 1024, val maxPartialBytes: Int = 16 * 1024 * 1024,
    val maxFailedKeys: Int = 5, val lockoutMs: Long = 60_000,
)
sealed interface RtmpIngestMedia {
    data class VideoConfig(val codec: IngestVideoCodec, val config: ByteArray) : RtmpIngestMedia
    data class VideoFrame(val isKeyFrame: Boolean, val ptsMs: Long, val dtsMs: Long, val data: ByteArray) : RtmpIngestMedia
    data class AudioConfig(val audioSpecificConfig: ByteArray) : RtmpIngestMedia
    data class AudioFrame(val ptsMs: Long, val data: ByteArray) : RtmpIngestMedia
}
interface RtmpServerListener {
    fun onPublishStart(streamId: UUID)
    fun onPublishStop(streamId: UUID, reason: String)
    fun onMedia(streamId: UUID, media: RtmpIngestMedia)
}
class RtmpProtocolException(val reason: String) : Exception(reason)
```

  The ByteArray-holding classes override `equals`/`hashCode` by content, like `FlvTagParser`'s.
- Produces, in `RtmpChunkDecoder.kt`:
  - `class RtmpDecodedMessage(val chunkStreamId: Int, val typeId: Int, val messageStreamId: Long, val timestamp: Long, val isAbsolute: Boolean, val payload: ByteArray)`;
    `timestamp` is the 32-bit value from the header (or extended timestamp), `isAbsolute` true for
    a message begun by a type-0 header.
  - `class RtmpChunkDecoder(limits: RtmpServerLimits, maxMessageSize: (typeId: Int) -> Int)` with
    `fun feed(data: ByteArray, offset: Int, length: Int, onMessage: (RtmpDecodedMessage) -> Unit)`
    (throws `RtmpProtocolException`) and `var chunkSize: Int` (from the client's SetChunkSize, set
    by the session).

- [ ] **Step 1: Write the failing tests** in `RtmpChunkDecoderTest`, building input with the existing
  `encodeChunks(type, chunkStreamId, RtmpMessage(...), maximumSize)`:
  - `decodesMessageSplitIntoChunks`: a 300-byte AMF0 command message encoded with chunk size 128
    (one type-0 chunk + type-3 continuations) fed one byte at a time → exactly one message with the
    same type, message stream ID, timestamp and payload.
  - `typeOneAndTwoHeadersAreDeltas`: type 0 (ts 1000) then type 1 (ts 40) then type 2 (ts 40) on one
    chunk stream → timestamps 1000, 40, 40 with `isAbsolute` true, false, false; the type-1 and
    type-2 messages keep the type-0 message stream ID; type 2 keeps the type-1 length and type.
  - `extendedTimestamp`: timestamp 0x1_0000_00 (> 0xFFFFFF) on type 0 and its type-3 continuation
    chunks → the full value, and continuation chunks consume the repeated 4 bytes.
  - `twoAndThreeByteBasicHeadersAreRejected`: csid 0 → "Two bytes basic header is not implemented";
    csid 1 → "Three bytes basic header is not implemented".
  - `tooManyChunkStreams`: messages on csids 2..18 (17 streams) → the 17th throws
    "Too many chunk streams".
  - `oversizedMessageRejectedBeforeAllocation`: a type-0 header announcing 16,777,215 bytes of type
    9 with a `maxMessageSize` of 8 MiB → throws "Message of 16777215 bytes is too large" after only
    the 12 header bytes were fed (no payload sent).
  - `partialDataLimit`: with `maxPartialBytes = 1000`, 9 chunk streams each with a 600-byte message
    of which 128 bytes were sent → throws "Too much incomplete data" when the total of incomplete
    payloads would pass 1000.
  - `zeroLengthMessageRejected`: a type-0 header with length 0 → "Unexpected data" (upstream).
  - `chunkSizeApplies`: after `chunkSize = 4096`, a 5000-byte message encoded with 4096 decodes.

- [ ] **Step 2: Run them and see them fail.** `./gradlew :transport:rtmp:test --tests '*RtmpChunkDecoderTest*'`

- [ ] **Step 3: Implement** the types and the decoder: a state machine that reads exact sizes from
  an internal buffer (no copying of the whole input on each call; `RtmpChunkReader`'s quadratic
  copying is what this replaces for the server), keeps per-chunk-stream header state, reads the
  extended timestamp again on type-3 chunks when the stream's last header had one (upstream
  `extendedTimestampPresentInType3`), checks the announced length against `maxMessageSize(typeId)`
  as soon as the header is read, and allocates a message's payload array only then.

- [ ] **Step 4: Run the tests and see them pass.**

- [ ] **Step 5: Gate and commit** ("RTMP server: bounded chunk decoder").

### Task 2: The session protocol

**Files:**
- Create: `.../server/RtmpServerSession.kt`
- Test: `.../server/RtmpServerSessionTest.kt`
- Modify: `docs/PORTING.md`

**Interfaces:**
- Consumes: Task 1 types and decoder; `FlvTagParser`, `FlvVideoTag`, `FlvAudioTag`,
  `FlvParseException`; `RtmpCommand`, `encodeChunks`, `RtmpMessage`, `uint32Payload`,
  `Amf0Decoder`; `HostClock`.
- Produces:

```kotlin
class RtmpServerSession(
    private val streams: () -> List<RtmpServerStream>,
    private val limits: RtmpServerLimits,
    private val clock: HostClock,
    private val send: (ByteArray) -> Unit,
    private val events: Events,
    private val random: java.util.Random = java.security.SecureRandom(),
) {
    interface Events {
        /** The key matched [stream]; the server decides who wins. */
        fun onPublish(stream: RtmpServerStream)
        fun onWrongKey()
        fun onMedia(media: RtmpIngestMedia)
        /** Exactly once; no feed after it does anything. */
        fun onClosed(reason: String)
    }
    enum class State { HANDSHAKE, CONNECTED, PUBLISHING, CLOSED }
    val state: State
    val stream: RtmpServerStream?
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size)
    fun close(reason: String)
}
```

  Session messages go out through `send`, split with `encodeChunks` at the session's outgoing chunk
  size (128, then 1024 after the connect reply). `feed` never throws: protocol errors become
  `close(reason)`.

**Behaviour** (port of `RtmpServerClient.swift` and `RtmpServerChunkStream.swift`):

| Input | Result |
|---|---|
| C0+C1 (1537 bytes), version ≠ 3 | close "Only version 3 is supported, not X" |
| C0+C1 valid | send S0, S1, S2 (Global Constraints); wait for C2 (1536 bytes, ignored) |
| `connect` without string `tcUrl` | close "Stream URL missing" |
| `tcUrl` not a URI | close "Invalid stream URL" |
| `tcUrl` path ≠ `/live` | close "Not a camera path" |
| `connect` valid | the four replies (Global Constraints); `_result` on the request's chunk stream and message stream ID, transaction ID echoed, command object null, one argument: object `level`="status", `code`="NetConnection.Connect.Success", `description`="Connection succeeded."; state CONNECTED |
| `createStream` | `_result`, transaction ID echoed, null, argument number 1 |
| `FCPublish`, `FCUnpublish`, `deleteStream`, `releaseStream`, other commands, AMF0 data | ignored |
| `publish` without arguments | close "Missing publish argument" |
| `publish` key not a string | close "Stream key not a string" |
| `publish` key matches no stream with a non-empty key | `events.onWrongKey()`, close "Stream key not configured" |
| `publish` key matches | `onStatus` (transaction ID echoed, null, object `level`="status", `code`="NetStream.Publish.Start", `description`="Start publishing."); state PUBLISHING; `events.onPublish(stream)` |
| AMF0 decode failure | close "AMF-0 decode error <message>" |
| SetChunkSize body ≠ 4 bytes | close "Not 4 bytes chunk size" |
| SetChunkSize value outside 1..16,777,215 | close "Invalid chunk size N" |
| WindowAckSize body ≠ 4 bytes | close "Not 4 bytes window acknowledgement size" |
| bytes received since the last ack > the client's window | send Acknowledgement (type 3, csid 2) with the total received & 0xFFFFFFFF |
| audio or video before PUBLISHING | dropped |
| audio/video in PUBLISHING | `FlvTagParser`; `FlvParseException` → close with its reason; `SequenceEnd` → close "Stream ended"; `Ignored` dropped; config/frames → `events.onMedia` with timestamps per Global Constraints |
| other message types | ignored |

  Message size limits passed to the decoder: before PUBLISHING every type gets
  `maxMessageBeforePublish`; after it audio `maxAudioMessage`, video `maxVideoMessage`, everything
  else `maxCommandMessage`. Timestamps: each chunk stream keeps upstream's running media timestamp
  (absolute on type 0, plus the delta otherwise); the session unwraps it to 64 bits per chunk stream
  (a step backwards by more than 2³¹ means a wrap) and subtracts the client's zero, taken from the
  first audio or video message on any chunk stream.

- [ ] **Step 1: Write the failing tests** in `RtmpServerSessionTest`, with a `FakeHostClock`, a
  recording `Events`, a `send` that collects bytes, and helpers that build client bytes from
  `encodeChunks` + `RtmpCommand.encode()` and parse the server's replies with the existing
  `RtmpChunkReader` (trusted input in tests):
  - `handshakeRepliesAsUpstream`: S0 = 3; S1 starts with 8 zero bytes and is 1536 bytes; S2 bytes
    0–3 equal C1 bytes 0–3, bytes 4–7 are 0, bytes 8–1535 equal C1's.
  - `wrongVersionCloses`: C0 = 6 → closed with "Only version 3 is supported, not 6".
  - `connectRepliesInOrder`: message types 5, 6, 1, then a `_result` whose argument object has
    `code` "NetConnection.Connect.Success", window 2,500,000, bandwidth limit byte 2, chunk size 1024.
  - `connectWrongAppCloses`: `tcUrl` "rtmp://host/app" → "Not a camera path"; missing → "Stream URL missing".
  - `publishWithKeyStarts`: connect, createStream (`_result` with number 1), publish "key-1" for a
    stream with key "key-1" → `onStatus` with "NetStream.Publish.Start", state PUBLISHING,
    `onPublish` with that stream.
  - `wrongKeyClosesWithoutTheKey`: publish "nope" → `onWrongKey` once, closed with exactly
    "Stream key not configured"; the reason does not contain "nope".
  - `emptyKeysNeverMatch`: a stream with key "" and publish "" → wrong key.
  - `mediaBeforePublishDropped`: a valid AVC sequence header before publish → no `onMedia`.
  - `videoTimestamps`: base clock 5,000 ms; latency 2,000; first video message timestamp 100 with
    composition time 40 → `VideoFrame(dtsMs = 7_000, ptsMs = 7_040)`; a type-1 message 33 ms later
    → dts 7_033.
  - `zeroIsPerClient`: audio on csid 8 at timestamp 1,000 first, then video on csid 9 at timestamp
    1,000 → both get the same host time (upstream's per-chunk-stream zero would not).
  - `timestampsUnwrap`: video at 0xFFFF_FF00, then a type-1 delta of 0x200 → dts increases by 0x200
    (no jump back by 2³²).
  - `ackAfterWindow`: client WindowAckSize 1,000, then 1,500 bytes of messages → one Acknowledgement
    with sequence = total bytes received.
  - `sequenceEndCloses`: an enhanced-RTMP HEVC sequence-end tag → closed "Stream ended".
  - `parserFailureCloses`: an Opus audio tag (codec 9) → closed with
    "Unsupported audio codec exHeader. Only AAC is supported."
  - `limitsSwitchAtPublish`: a 70,000-byte video message before publish → closed (too large);
    after publish the same message is accepted.
  - `closeIsReportedOnce`: two protocol errors in one `feed` → `onClosed` once.

- [ ] **Step 2: Run them and see them fail.** `./gradlew :transport:rtmp:test --tests '*RtmpServerSessionTest*'`

- [ ] **Step 3: Implement `RtmpServerSession`.** Key comparison:
  `MessageDigest.isEqual(candidate.toByteArray(UTF_8), stream.streamKey.toByteArray(UTF_8))`
  over all streams with a non-empty key (no early exit).

- [ ] **Step 4: Run the tests and see them pass.**

- [ ] **Step 5: Gate and commit** ("RTMP server: session protocol, as upstream").

### Task 3: The socket server

**Files:**
- Create: `.../server/RtmpServer.kt`
- Test: `.../server/RtmpServerTest.kt`
- Modify: `docs/PORTING.md`

**Interfaces:**
- Consumes: Tasks 1–2; `SerialExecutor`/`ThreadSerialExecutor`, `HostClock`.
- Produces:

```kotlin
class RtmpServer(
    settings: RtmpServerSettings,
    private val listener: RtmpServerListener,
    private val acceptPeer: (local: InetAddress, remote: InetAddress) -> Boolean,
    private val limits: RtmpServerLimits = RtmpServerLimits(),
    private val clock: HostClock = HostClock.system,
    private val log: (String) -> Unit = {},
) {
    fun start()               // binds 0.0.0.0:port; throws IOException if the port is taken
    fun stop()                // closes everything; joins the accept thread; listener gets onPublishStop for each publisher with reason "Server stop"
    val port: Int             // the bound port (settings.port, or the real one when it was 0)
    fun connectedStreamIds(): Set<UUID>
    fun numberOfClients(): Int
}
```

  All listener calls happen on one `ThreadSerialExecutor("rtmp-server")`, in order. A client's
  socket is read on a worker from a `ThreadPoolExecutor(0, maxConnections, 30 s, SynchronousQueue)`;
  a connection beyond `maxConnections`, or beyond `maxUnauthenticated` not yet publishing, is closed
  at once. `acceptPeer` runs before any byte is read; refused peers are closed. A housekeeping task
  every 3 s closes clients past their deadline: no complete handshake within `handshakeTimeoutMs`
  ("Handshake timeout"), not publishing within `publishTimeoutMs` ("Publish timeout"), nothing
  received for `idleTimeoutMs` ("Receive timeout", upstream). Socket `soTimeout` is
  `readTimeoutMs`; a read timeout only triggers a deadline check, not a close. A publish whose
  stream ID is already publishing closes the older client with "Same stream key" (listener:
  `onPublishStop(old, "Same stream key")` then `onPublishStart(new)`). `onWrongKey` counts against
  the peer address; at `maxFailedKeys` within `lockoutMs` the address is refused at accept until
  `lockoutMs` after its last failure. Log lines name addresses and reasons, never keys.

- [ ] **Step 1: Write the failing tests** in `RtmpServerTest`, binding port 0 on 127.0.0.1 peers
  (`acceptPeer = { _, _ -> true }` unless stated), with short limits where time matters, and
  publishing with the module's own `RtmpStream` client (`rtmp://127.0.0.1:<port>/live/<key>`) or
  raw sockets:
  - `rtmpStreamPublishesAndMediaArrives`: `RtmpStream` publishes 30 H.264 frames + AAC from
    `Ffmpeg`-free synthetic data (an avcC built from a fixed SPS/PPS and length-prefixed frames) →
    `onPublishStart(id)`, a `VideoConfig`, 30 `VideoFrame`s with increasing dts, an `AudioConfig`,
    `AudioFrame`s; closing the client → `onPublishStop(id, …)`.
  - `wrongKeyIsRefused`: `RtmpStream` with key "nope" never connects; no `onPublishStart`.
  - `lockoutAfterFiveWrongKeys`: 5 raw publishes with a wrong key from 127.0.0.1 (limits
    `lockoutMs` 1,000) → a 6th connection is closed before the server sends S0; after 1.2 s it gets
    a handshake again.
  - `sameKeyNewestWins`: two publishers with one key → the first is disconnected, events
    stop("Same stream key") then start, `connectedStreamIds()` holds the ID once.
  - `silentClientTimesOut`: a raw socket that connects and sends nothing, `handshakeTimeoutMs` 500
    → closed within 4 s (housekeeping period plus deadline).
  - `slowlorisTimesOut`: a raw socket sending one handshake byte every 300 ms, `handshakeTimeoutMs`
    1,000 → closed within 5 s.
  - `unauthenticatedLimit`: `maxUnauthenticated` 2 → the third idle connection is closed at once
    while two are open; a publishing client does not count.
  - `refusedPeerIsClosed`: `acceptPeer = { _, _ -> false }` → the connection is closed without S0.
  - `stopClosesEverythingAndThreadsEnd`: after `stop()`, `onPublishStop(id, "Server stop")` for the
    publisher, and no thread whose name starts with "rtmp-server" is alive 2 s later.
  - `keyNeverLogged`: a `log` collector over a wrong-key and a right-key publish → no line
    contains either key.

- [ ] **Step 2: Run them and see them fail.** `./gradlew :transport:rtmp:test --tests '*RtmpServerTest*'`

- [ ] **Step 3: Implement `RtmpServer`** (accept thread named "rtmp-server-accept", workers
  "rtmp-server-client-N", housekeeping on the serial executor's `scheduleRepeating`).

- [ ] **Step 4: Run the tests and see them pass**, then run them five times
  (`--rerun-tasks`) to check they are not flaky.

- [ ] **Step 5: Gate and commit** ("RTMP server: sockets, admission, deadlines and lockout").

### Task 4: ffmpeg interop (stage 5)

**Files:**
- Create: `.../server/RtmpServerInteropTest.kt`
- Modify: `docs/plans/2026-11-14-rtmp-server-ingest.md` (status line: stages 4–5 done),
  `docs/PORTING.md` if a row needs a note

**Interfaces:**
- Consumes: `RtmpServer` (Task 3), `lengthPrefixedToAnnexB` and `nalLengthSize` (stage 3), the
  `Ffmpeg` test fixtures in `media/mux` (`Ffmpeg.available`, `Ffmpeg.run`, `Ffmpeg.start`).

- [ ] **Step 1: Write the tests** (skipped without ffmpeg via `assumeTrue(Ffmpeg.available, …)`, as
  `RtmpInteropTest` does; `NEXUSIRL_REQUIRE_FFMPEG=1` makes CI fail instead):
  - `ffmpegPublishesH264WithBFramesAndAac`: ffmpeg `-re -f lavfi testsrc2 … -c:v libx264 -bf 2
    -g 30 -c:a aac -f flv rtmp://127.0.0.1:<port>/live/<key>` for 3 s → `onPublishStart`; the
    collected video config and frames, converted with `lengthPrefixedToAnnexB` (SPS/PPS from the
    avcC prepended), written to `video.h264` decode with `ffmpeg -v error -xerror -f h264 -i … -f
    null -`; at least 80 frames; some frames have `ptsMs != dtsMs` (B-frames); dts never
    decreases; an `AudioConfig` and at least 100 `AudioFrame`s.
  - `ffmpegPublishesEnhancedRtmpHevc`: the same with `-c:v libx265` (enhanced RTMP `hvc1`) →
    `VideoConfig(H265, …)`, frames decode as `-f hevc`.
  - `ffmpegWithWrongKeyIsRefused`: ffmpeg publishing to a wrong key exits non-zero and the listener
    sees no `onPublishStart`.
  If the installed ffmpeg cannot write enhanced-RTMP HEVC, the HEVC test is skipped with a message
  naming the ffmpeg version (`assumeTrue`), never failed.

- [ ] **Step 2: Run them.** `NEXUSIRL_REQUIRE_FFMPEG=1 ./gradlew :transport:rtmp:test --tests '*RtmpServerInteropTest*'`
  Expected: pass (they test the server built in Tasks 1–3). A failure here is a server bug: fix it
  in the owning file with a unit test that reproduces it first.

- [ ] **Step 3: Gate and commit** ("RTMP server: ffmpeg interop tests").

---

## After these tasks

Write the next plan (stages 6–7: Android decoders and pipeline integration) from the same spec.
