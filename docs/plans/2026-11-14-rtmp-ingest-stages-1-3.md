# RTMP ingest, stages 1–3: buffered core, settings, safe parsers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The pure-JVM foundations of RTMP server ingest: Moblin's drift tracker and buffered
video/audio queues with their upstream test suites, Moblin-compatible `rtmpServer` settings, and
FLV/NAL parsers that are safe on untrusted input.

**Architecture:** Three independent pieces, each in the module that will use it.
`media/pipeline` gets an `ingest` package with generic, Android-free ports of `DriftTracker`,
`BufferedStats`, `BufferedVideo` and `BufferedAudio` (frames are a type parameter, times are
seconds as `Double` like upstream, so the upstream vectors copy verbatim). `settings` models
`rtmpServer`, `ingestsSoftwareVideoDecoding` and the scene keys. `media/mux` and
`transport/rtmp` get bounded parsers that return typed failures instead of throwing or looping.
Nothing here is wired into the app yet (that is stages 6–9).

**Tech Stack:** Kotlin, kotlinx.serialization (`SwiftPreserving` settings), JUnit 5 with
`kotlin.test`.

**Spec:** [docs/plans/2026-11-14-rtmp-server-ingest.md](2026-11-14-rtmp-server-ingest.md)
(sections 1, 2, 4, 6 and the decisions in section 10).

## Global Constraints

- Every ported file starts with `// Port of <upstream path> @97e26d2.` (see `FrameClock.kt`).
- Pure JVM: no `android.*` imports in the `ingest` package, `FlvTagParser` or the NAL helpers.
- Upstream constants verbatim: video queue cap 200, audio queue cap 300, drift samples every
  0.5 s, at most 60 kept, at least 30 to count, adjust at most every 20 s, normal-mode audio resync
  at |delta| > 0.05, video "ahead" threshold 0.01, stats interval 5 s, audio clock limit 0.03 s.
- Settings defaults verbatim: `rtmpServer {enabled=false, port=1935, streams=[]}`; stream
  `{id, name="My stream", streamKey="", latency=2000}`; `ingestsSoftwareVideoDecoding=false`;
  scene `rtmpCameraId` (random UUID when missing), `overrideMic=false`, `micId=""`.
- Validators and messages verbatim from upstream `Common/Various/Validate.swift`: latency 5–10000
  ("Not a number", "Too small", "Too big"); port 1–65535 (same messages).
- Upstream FLV failure messages verbatim (section "FLV messages" in Task 6).
- Decision 1: an import never changes this device's `rtmpServer.enabled`.
- Stream keys are secrets: they never reach `settings.json` and exports without secrets blank them.
- Gate before each commit: `NEXUSIRL_REQUIRE_FFMPEG=1 ./gradlew ktlintCheck test lint :app:licensee assembleDebug assembleRelease -q`
  and `sh tools/check-independence.sh`; commit only when both exit 0. Stage explicit paths only.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A publisher whose timestamps jump backwards or repeat** (OBS restart, oa6 cameras): queues
   must stay ordered and never grow past their caps. Pinned by the cap tests in Tasks 2 and 3.
2. **A NAL length larger than the remaining data, zero, or `0xFFFFFFFC`**: conversion returns
   `null` in bounded time, never loops or throws. Pinned in Task 5.
3. **A truncated or empty FLV body** (1 byte, 4 bytes, extended header without its fourCC): a
   typed failure with upstream's "Got N bytes message, expected >= M", never an index exception.
   Pinned in Task 6.
4. **An iOS file with `rtmpServer.enabled=true` imported over a device that has it off**: stays
   off; and streams, port and latency still import. Pinned in Task 4.
5. **Audio that stops while the output keeps asking** (publisher stalls): the last frame is
   returned flagged as silence, never a stale sound loop. Pinned in Task 3.

---

## File map

| File | Responsibility |
|---|---|
| `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/DriftTracker.kt` | Port of `DriftTracker.swift` |
| `.../ingest/BufferedStats.kt` | Port of `BufferedStats.swift` |
| `.../ingest/BufferedVideo.kt` | Port of `BufferedVideo.swift`, generic over the frame |
| `.../ingest/BufferedAudio.kt` | Port of `BufferedAudio.swift` selection, generic over the frame |
| `.../ingest/BufferedAudioClock.kt` | Port of `BufferedAudio.output()`'s PTS grid, no timer |
| `media/pipeline/src/test/kotlin/.../ingest/UpstreamDriftTrackerSuiteTest.kt` etc. | Upstream suites and new tests |
| `settings/src/main/kotlin/io/github/mesmerize08/nexusirl/settings/SettingsIngests.kt` | `SettingsRtmpServer`, `SettingsRtmpServerStream`, validators, `randomHumanString` |
| `settings/.../Database.kt`, `settings/.../SettingsScene.kt` | New fields |
| `app/src/main/kotlin/io/github/mesmerize08/nexusirl/settings/SettingsStore.kt` | Import keeps `rtmpServer.enabled` |
| `media/mux/src/main/kotlin/io/github/mesmerize08/nexusirl/media/mux/nal/NalUnits.kt` | `lengthPrefixedToAnnexB`, fixed `addNalUnitStartCodes` |
| `media/mux/.../ts/MpegTsConfigs.kt` | `nalLengthSize` on `Avc`/`Hevc` |
| `transport/rtmp/src/main/kotlin/io/github/mesmerize08/nexusirl/transport/rtmp/server/FlvTagParser.kt` | Typed parsing of RTMP audio/video message bodies |
| `docs/PORTING.md` | New rows for each ported file |

---

### Task 1: DriftTracker and BufferedStats with the upstream suite

**Files:**
- Create: `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/DriftTracker.kt`
- Create: `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/BufferedStats.kt`
- Test: `media/pipeline/src/test/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/UpstreamDriftTrackerSuiteTest.kt`
- Test: `media/pipeline/src/test/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/BufferedStatsTest.kt`
- Modify: `docs/PORTING.md` (rows for both files)

**Interfaces:**
- Produces:
  - `enum class DriftTrackerMedia { AUDIO, VIDEO }`
  - `class DriftTracker(name: String, log: (String) -> Unit = {})` with
    `fun addMedia(media: DriftTrackerMedia, targetFillLevel: Double)`,
    `fun getDrift(): Double`, `fun setDrift(drift: Double)`,
    `fun update(media: DriftTrackerMedia, outputPresentationTimeStamp: Double, newestPresentationTimeStamp: Double)`.
    Not thread-safe (callers use one thread, like upstream's queue).
  - `class BufferedStats` with `fun incrementDuplicated()`, `fun incrementDropped(count: Int)`,
    `fun getStats(presentationTimeStamp: Double): Pair<Int, Int>?` (duplicated, dropped).

- [ ] **Step 1: Write the failing tests.** Port all 11 tests of
  `upstream/MoblinTests/Moblin/Media/HaishinKit/Media/DriftTrackerSuite.swift` one to one, same
  names in camelCase, same `drive` helper (step 0.6, audio then video each tick, record the drift
  whenever it changes) and same expectations, e.g.:

```kotlin
@Test
fun sustainedJumpAdjustsToTarget() {
    val tracker = createTracker()
    var adjustments = drive(tracker, from = 0.0, to = 50.0, video = { it + 1.0 })
    adjustments += drive(tracker, from = 50.0, to = 150.0, video = { it })
    assertEquals(1, adjustments.size)
    assertTrue(abs(tracker.getDrift() - 1.0) < 0.01)
}
```

  `BufferedStatsTest`: `getStats` returns null before 5 s have passed since the last report and
  when nothing was counted; after `incrementDuplicated()` twice and `incrementDropped(3)`,
  `getStats(5.1)` is `2 to 3` and an immediate `getStats(11.0)` is null (counters reset).

- [ ] **Step 2: Run them and see them fail.**
  Run: `./gradlew :media:pipeline:testDebugUnitTest --tests '*ingest*'`
  Expected: compilation fails (`DriftTracker` unresolved).

- [ ] **Step 3: Implement both classes** as straight ports of `DriftTracker.swift` and
  `BufferedStats.swift` (an `ArrayDeque<Double>` for fill levels; `estimateDeviations` returns the
  25th and 75th percentile of `sorted().map { it + drift - target }` at indices `n/4` and `3n/4`).
  `log` gets upstream's debug text.

- [ ] **Step 4: Run the tests and see them pass.** Same command. Expected: 11 + the stats tests pass.

- [ ] **Step 5: Gate and commit** ("Ingest: port DriftTracker and BufferedStats with the upstream suite").

### Task 2: BufferedVideo

**Files:**
- Create: `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/BufferedVideo.kt`
- Test: `media/pipeline/src/test/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/BufferedVideoTest.kt`
- Modify: `docs/PORTING.md`

**Interfaces:**
- Consumes: `DriftTracker`, `DriftTrackerMedia`, `BufferedStats` (Task 1).
- Produces: `class BufferedVideo<F>(name: String, update: Boolean, latency: Double, driftTracker: DriftTracker?, presentationTimeStamp: (F) -> Double, onReady: () -> Unit = {}, onRelease: (F) -> Unit = {}, log: (String) -> Unit = {})`
  with `fun appendFrame(frame: F)`, `fun updateFrame(outputPresentationTimeStamp: Double, forceUpdate: Boolean = false)`,
  `fun latestFrame(): F?`, `fun setLatestFrame(frame: F?)`, `fun numberOfFrames(): Int`.
  `onReady` fires once, when initial buffering completes (upstream `streamVideoBufferedVideoReady`).
  `onRelease` gets every frame that leaves the object without becoming the latest one, and the
  replaced latest frame (Android will return decoder output buffers through it). The constructor
  calls `driftTracker?.addMedia(VIDEO, latency)`.

- [ ] **Step 1: Write the failing tests.** The two upstream `BufferedVideoSuite` tests with their
  vectors (frames are `Double` PTS; latency 0.1; a fresh `DriftTracker`):
  `appendOutOfOrderEarlyFrame` (append 2.0, 3.0, 1.0 → 3 frames; `updateFrame(1.5)` → 2 left)
  and `appendFramesInReverseOrder` (3.0, 2.0, 1.0; `updateFrame(2.5)` → 1 left). New tests:
  - `capOf200DropsOldest`: append 250 frames at 0.0, 0.01 … ; `updateFrame(0.0)` leaves at most
    200 queued and `onRelease` got every dropped frame.
  - `readyFiresOnceAfterFirstConsumedFrame`: append 1.0, 1.033; `updateFrame(0.5)` → not ready;
    `updateFrame(1.0)` → ready once; `updateFrame(1.033)` → still once.
  - `noUpdateWithoutUpdateFlag`: `update = false`, `updateFrame(5.0)` consumes nothing;
    `updateFrame(5.0, forceUpdate = true)` does.
  - `latestReplacedIsReleased`: two consumed frames → the first is passed to `onRelease`.

- [ ] **Step 2: Run them and see them fail.** `./gradlew :media:pipeline:testDebugUnitTest --tests '*BufferedVideoTest*'`

- [ ] **Step 3: Implement `BufferedVideo<F>`** as a port of `BufferedVideo.swift`: insertion keeps
  PTS order (insert after the last frame with a smaller PTS, else first); `hasBestBuffer` as
  upstream (next frame ahead by `> 0`, and either a candidate exists or ahead by `> 0.01`);
  drift-tracker update after initial buffering with the newest queued (else latest) PTS.

- [ ] **Step 4: Run the tests and see them pass.**

- [ ] **Step 5: Gate and commit** ("Ingest: port BufferedVideo").

### Task 3: BufferedAudio and its output clock

**Files:**
- Create: `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/BufferedAudio.kt`
- Create: `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/BufferedAudioClock.kt`
- Test: `media/pipeline/src/test/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/UpstreamBufferedAudioSuiteTest.kt`
- Test: `media/pipeline/src/test/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/BufferedAudioClockTest.kt`
- Modify: `docs/PORTING.md`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `data class BufferedAudioOutput<F>(val frame: F, val silent: Boolean)`. `silent` means "play
    silence for this frame's duration". It replaces upstream zeroing the last buffer in place.
  - `class BufferedAudio<F>(name: String, latency: Double, manualOutput: Boolean, driftTracker: DriftTracker?, presentationTimeStamp: (F) -> Double, log: (String) -> Unit = {})`
    with `fun appendFrame(frame: F)`, `fun getFrame(outputPresentationTimeStamp: Double): BufferedAudioOutput<F>?`,
    `fun numberOfFrames(): Int`. The constructor calls `driftTracker?.addMedia(AUDIO, latency)`.
    No timer inside; with `manualOutput = false` the drift tracker is updated as upstream.
  - `class BufferedAudioClock(sampleRate: Int, frameLength: Int, log: (String) -> Unit = {})` with
    `fun next(clockSeconds: Double): Double`: upstream `output()`'s
    `start + frameLength × counter / sampleRate`, the start taken from the first call, moved one
    frame back or forward when more than 0.03 s from the clock.

- [ ] **Step 1: Write the failing tests.** All six upstream `BufferedAudioSuite` tests
  (`processNormal`, `processGap`, `processDriftChange`, and the four `oa6…` tests, with the 26
  oa6 timestamps copied verbatim), latency 0.1, `manualOutput = true`. Upstream's identity check
  `getSampleBuffer(t) === buffers[i]` becomes `assertSame(buffers[i], audio.getFrame(t)?.frame)`
  (frames are distinct objects, e.g. `class Frame(val pts: Double)`, so repeats of one PTS are
  told apart). In `processGap`, the ten repeats of `buffers[2]` must also have `silent == true`,
  and a normally consumed frame has `silent == false`. New tests:
  - `capOf300DropsOldest`: 400 frames → after one `getFrame` at the first PTS at most 300 queued.
  - `emptyBeforeFirstFrame`: `getFrame(1.0)` on an empty object is null.

  `BufferedAudioClockTest` (48000 Hz, 1024 frames, period 0.021333…):
  - `followsGrid`: calls at clock `10.0 + k × period` return exactly `10.0 + k × period`.
  - `stepsBackWhenAhead`: after the first call at 10.0, a call at clock 10.0 + period − 0.05
    returns 10.0 (one frame back, upstream "Adjust PTS back in time").
  - `stepsForwardWhenBehind`: a call at clock 10.0 + period + 0.05 returns 10.0 + 2 × period.

- [ ] **Step 2: Run them and see them fail.** `./gradlew :media:pipeline:testDebugUnitTest --tests '*BufferedAudio*'`

- [ ] **Step 3: Implement both classes** as ports of `BufferedAudio.swift`: the syncing and normal
  modes (`isSyncingWithOutput` starts true; syncing breaks on the first frame ahead by `> 0`
  and leaves syncing once a candidate exists; normal mode never skips unless the candidate is
  more than 0.05 from the output, which re-enters syncing); the 300 cap; when nothing is
  consumed, return the latest frame with `silent = true`.

- [ ] **Step 4: Run the tests and see them pass.**

- [ ] **Step 5: Gate and commit** ("Ingest: port BufferedAudio and its output clock").

### Task 4: RTMP server settings

**Files:**
- Create: `settings/src/main/kotlin/io/github/mesmerize08/nexusirl/settings/SettingsIngests.kt`
- Modify: `settings/src/main/kotlin/io/github/mesmerize08/nexusirl/settings/Database.kt` (two fields after `torchLevel`)
- Modify: `settings/src/main/kotlin/io/github/mesmerize08/nexusirl/settings/SettingsScene.kt` (`SettingsScene`: three fields)
- Modify: `app/src/main/kotlin/io/github/mesmerize08/nexusirl/settings/SettingsStore.kt:60-71` (`import`)
- Test: `settings/src/test/kotlin/io/github/mesmerize08/nexusirl/settings/SettingsIngestsTest.kt`
- Test: `app/src/test/kotlin/io/github/mesmerize08/nexusirl/settings/SettingsStoreTest.kt` (new tests)
- Modify: `docs/PORTING.md`

**Interfaces:**
- Produces:
  - `@Serializable data class SettingsRtmpServerStream(val id: SUuid = UUID.randomUUID(), val name: String = "My stream", val streamKey: String = "", val latency: Int = 2000, override var extra)`
    with `val camera: String get() = "$name (RTMP)"` (upstream `rtmpCamera(name:)`) and
    `val latencySeconds: Double`.
  - `@Serializable data class SettingsRtmpServer(val enabled: Boolean = false, val port: Int = 1935, val streams: List<SettingsRtmpServerStream> = emptyList(), override var extra)`.
    Both `SwiftPreserving` with `SwiftPreservingSerializer`, like `SettingsMics`.
  - `object IngestValidators { fun latency(value: String): String?; fun port(value: String): String? }`
    returning upstream's messages or null.
  - `fun randomHumanString(): String`: 15 `SecureRandom` bytes, Base64, with `+`, `/` and `=` removed.
  - `Database.rtmpServer: SettingsRtmpServer`, `Database.ingestsSoftwareVideoDecoding: Boolean`.
  - `SettingsScene.rtmpCameraId: SUuid = UUID.randomUUID()`, `SettingsScene.overrideMic: Boolean = false`, `SettingsScene.micId: String = ""`.

- [ ] **Step 1: Write the failing tests** in `SettingsIngestsTest`:
  - `defaultsMatchUpstream`: `Database.fromJson("{}")` has `rtmpServer == SettingsRtmpServer()`
    with enabled false, port 1935, no streams; `ingestsSoftwareVideoDecoding` false.
  - `iosRtmpServerRoundTrips`: parse
    `{"rtmpServer":{"enabled":true,"port":11935,"streams":[{"id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","name":"GoPro","streamKey":"1","latency":500,"futureKey":7}]},"ingestsSoftwareVideoDecoding":true}`;
    fields read as given; `toJson()` contains `"futureKey":7` and the uppercase id.
  - `sceneKeys`: `{"scenes":[{"name":"Drone","cameraPosition":"RTMP","rtmpCameraId":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","overrideMic":true,"micId":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11 0"}]}`
    reads into the three fields and writes back the same values; `cameraPosition` stays `"RTMP"`.
  - `validators`: latency `"abc"` → "Not a number", `"4"` → "Too small", `"10001"` → "Too big",
    `"5"` and `"10000"` → null; port `"0"` → "Too small", `"65536"` → "Too big", `"1935"` → null.
  - `randomHumanStringShape`: 100 values, each 20 characters of `[A-Za-z0-9]`, all distinct.
  - `streamCamera`: `SettingsRtmpServerStream(name = "GoPro").camera == "GoPro (RTMP)"`.

  In `SettingsStoreTest`:
  - `importKeepsRtmpServerOff`: a fresh store; import
    `{"rtmpServer":{"enabled":true,"port":11935,"streams":[{"name":"A","streamKey":"fakekey-bbbbbbbbbbbb"}]}}`;
    `rtmpServer.enabled` stays false, port 11935 and one stream named "A" are imported. Then turn
    it on with `update`, import `{}`: still on.
  - `rtmpStreamKeyIsASecret`: after that import the settings file does not contain
    `fakekey-bbbbbbbbbbbb`, and `exportJson()` (no secrets) has `"streamKey":""`.

- [ ] **Step 2: Run them and see them fail.**
  `./gradlew :settings:test --tests '*SettingsIngestsTest*' :app:testDebugUnitTest --tests '*SettingsStoreTest*'`

- [ ] **Step 3: Implement** the new file and fields, and in `SettingsStore.import` keep
  `current.rtmpServer.enabled` next to the existing `allowSettingsImport` line (comment: decision 1
  in the plan).

- [ ] **Step 4: Run the tests and see them pass.** Also run `./gradlew :settings:test :app:testDebugUnitTest`
  to see that existing round-trip tests still pass (the keys moved out of `extra`).

- [ ] **Step 5: Gate and commit** ("Settings: RTMP server ingest settings, as upstream").

### Task 5: Safe NAL-unit conversion

**Files:**
- Modify: `media/mux/src/main/kotlin/io/github/mesmerize08/nexusirl/media/mux/nal/NalUnits.kt:193-204`
- Modify: `media/mux/src/main/kotlin/io/github/mesmerize08/nexusirl/media/mux/ts/MpegTsConfigs.kt` (`Avc`, `Hevc` companions)
- Test: `media/mux/src/test/kotlin/io/github/mesmerize08/nexusirl/media/mux/LengthPrefixedToAnnexBTest.kt`

**Interfaces:**
- Produces:
  - `fun lengthPrefixedToAnnexB(data: ByteArray, nalLengthSize: Int = 4): ByteArray?` in
    `NalUnits.kt`: a new array with a 4-byte start code before each NAL unit; null when a length
    is 0, runs past the end, leaves trailing bytes shorter than a length field, or when
    `nalLengthSize` is not 1, 2 or 4. Linear time; lengths are read as unsigned `Long`.
  - `addNalUnitStartCodes(data)` keeps its in-place behaviour for 4-byte lengths but stops at a
    length that runs past the end (it is not used for untrusted input).
  - `MpegTsVideoConfig.Avc.nalLengthSize(avcC: ByteArray): Int?` (byte 4 `& 0x03` + 1; null when
    shorter than 5 bytes or the value is 3) and `MpegTsVideoConfig.Hevc.nalLengthSize(hvcC: ByteArray): Int?`
    (byte 21 `& 0x03` + 1; null when shorter than 22 bytes or the value is 3).

- [ ] **Step 1: Write the failing tests:**
  - `convertsTwoUnits`: `[0,0,0,2, 0x65,1, 0,0,0,1, 0x41]` → `[0,0,0,1, 0x65,1, 0,0,0,1, 0x41]`.
  - `twoByteLengths`: `[0,2, 0x65,1]` with size 2 → `[0,0,0,1, 0x65,1]`.
  - `hugeLengthIsRejected`: `[0xFF,0xFF,0xFF,0xFC, 1,2,3]` → null, and the call returns
    (wrap it in `assertTimeoutPreemptively(Duration.ofSeconds(1))`).
  - `zeroLengthRejected`, `truncatedUnitRejected` (`[0,0,0,5, 1,2]`), `trailingBytesRejected`
    (`[0,0,0,1, 0x41, 0,0]`), `badLengthSizeRejected` (size 3) → null.
  - `addNalUnitStartCodesStopsAtBadLength`: `[0xFF,0xFF,0xFF,0xFC, 1,2,3]` returns within 1 s.
  - `avcCLengthSize`: an avcC whose byte 4 is `0xFF` → 4, `0xFD` → 2, `0xFE` → null; 4-byte input → null.
  - `hvcCLengthSize`: a 23-byte hvcC whose byte 21 is `0x0F` → 4, `0x0D` → 2.

- [ ] **Step 2: Run them and see them fail.** `./gradlew :media:mux:test --tests '*LengthPrefixedToAnnexBTest*'`

- [ ] **Step 3: Implement** the functions above.

- [ ] **Step 4: Run the tests and see them pass**, plus `./gradlew :media:mux:test` for the existing suites.

- [ ] **Step 5: Gate and commit** ("Mux: bounded length-prefixed to Annex-B conversion").

### Task 6: FlvTagParser

**Files:**
- Create: `transport/rtmp/src/main/kotlin/io/github/mesmerize08/nexusirl/transport/rtmp/server/FlvTagParser.kt`
- Modify: `transport/rtmp/src/main/kotlin/io/github/mesmerize08/nexusirl/transport/rtmp/Flv.kt` (constants: `VIDEO_CODEC_HEVC = 12`, packet types `SEQUENCE_END = 2`, `CODED_FRAMES_X = 3`)
- Test: `transport/rtmp/src/test/kotlin/io/github/mesmerize08/nexusirl/transport/rtmp/server/FlvTagParserTest.kt`
- Modify: `docs/PORTING.md`

**Interfaces:**
- Produces (port of `processMessageAudio`/`processMessageVideo` in
  `upstream/Moblin/Media/RtmpServer/RtmpServerChunkStream.swift`, parsing only):

```kotlin
enum class IngestVideoCodec { H264, H265 }

sealed interface FlvVideoTag {
    data class SequenceHeader(val codec: IngestVideoCodec, val config: ByteArray) : FlvVideoTag
    /** [data] is length-prefixed NAL units, as sent. */
    data class Frame(val isKeyFrame: Boolean, val compositionTimeMs: Int, val data: ByteArray) : FlvVideoTag
    data object SequenceEnd : FlvVideoTag
    /** Not an error: upstream logs and drops it. */
    data class Ignored(val reason: String) : FlvVideoTag
}

sealed interface FlvAudioTag {
    data class AacConfig(val audioSpecificConfig: ByteArray) : FlvAudioTag
    data class AacFrame(val data: ByteArray) : FlvAudioTag
    data class Ignored(val reason: String) : FlvAudioTag
}

/** A failure ends the session with [reason] (upstream `stopInternal(reason:)`). */
class FlvParseException(val reason: String) : Exception(reason)

object FlvTagParser {
    fun parseVideo(body: ByteArray): FlvVideoTag      // throws FlvParseException
    fun parseAudio(body: ByteArray): FlvAudioTag      // throws FlvParseException
}
```

  Data arrays are copies (the caller reuses its message buffer). `IngestVideoCodec` lives in this
  file; later stages import it from here.

**FLV messages** (verbatim from upstream; `N`, `M`, `X` are decimal):

| Input | Result |
|---|---|
| video body < 2 bytes | throw "Got N bytes message, expected >= 2" |
| legacy header, codec not 7 or 12 | throw "Unsupported video codec X" |
| legacy, packet type 0 | `SequenceHeader(codec, body[5..])`; body < 5 → throw "Got N bytes message, expected >= 5" |
| legacy, packet type 1, body ≤ 9 bytes | `Ignored` ("Dropping short packet") |
| legacy, packet type 1 | `Frame(frameType == 1, signed 24-bit body[2..4], body[5..])` |
| legacy, other packet type | `Ignored` |
| extended header (bit 7), body < 5 | throw "Got N bytes message, expected >= 5" |
| extended, frame type not 1 or 2 | throw "Unsupported video frame type X" |
| extended, packet type not 0, 1, 2, 3 or 6 | throw "Unsupported video packet type X" |
| extended, fourCC `avc1` | throw "Unsupported fourCC avc1." |
| extended, other unknown fourCC | throw "Unsupported fourCC X" (X = the 32-bit value) |
| extended `hvc1`, packet 0 | `SequenceHeader(H265, body[5..])` |
| extended `hvc1`, packet 1 | ≤ 9 bytes → `Ignored`; else `Frame(key, signed 24-bit body[5..7], body[8..])` |
| extended `hvc1`, packet 2 | `SequenceEnd` (the caller stops with "Stream ended") |
| extended `hvc1`, packet 3 | `Frame(key, 0, body[5..])` |
| extended `hvc1`, packet 6 | `Ignored` |
| audio body < 2 bytes | throw "Got N bytes message, expected >= 2" |
| audio codec (`body[0] >> 4`) in {4, 5, 6, 7, 8, 12, 13} | throw "Failed to parse audio settings C" (C = `body[0]`) |
| audio codec known but not 10 | throw "Unsupported audio codec NAME. Only AAC is supported." with NAME from upstream's case names: 0 `pcm`, 1 `adpcm`, 2 `mp3`, 3 `pcmle`, 9 `exHeader`, 11 `speex`, 14 `mp3_8k`, 15 `device` |
| AAC, `body[1] == 0` | `AacConfig(body[2..])` |
| AAC, `body[1] == 1` | `AacFrame(body[2..])`; empty payload → `Ignored` |
| AAC, other | `Ignored` |

- [ ] **Step 1: Write the failing tests**, one per table row (plus `negativeCompositionTime`:
  legacy NAL with cts bytes `FF FF FE` → `-2`, and `dataIsCopied`: changing the input after
  parsing does not change `Frame.data`). Name them after the row, e.g.
  `extendedAvc1IsRejectedWithUpstreamMessage`, and assert the exact message string.

- [ ] **Step 2: Run them and see them fail.** `./gradlew :transport:rtmp:test --tests '*FlvTagParserTest*'`

- [ ] **Step 3: Implement `FlvTagParser`** with explicit length checks before every index.

- [ ] **Step 4: Run the tests and see them pass**, plus `./gradlew :transport:rtmp:test`.

- [ ] **Step 5: Gate and commit** ("RTMP: FLV tag parser for the server, safe on untrusted input").

---

## After these tasks

Update the plan's status line in `docs/plans/2026-11-14-rtmp-server-ingest.md` to say stages 1–3
are done, and write the next plan (stage 4, the RTMP server protocol core) from the same spec.
