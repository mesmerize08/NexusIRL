# RTMP ingest, stages 6–7: Android decoders and pipeline integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An ingested stream (compressed H.264/HEVC + AAC with host timestamps) becomes a video
source the pipeline can show instead of the camera, and an audio source it can send instead of the
microphone, with Moblin's buffered timing.

**Architecture:** `media/pipeline` gets an `ingest` Android layer on top of stage 1's pure core.
`IngestJitterBuffer` holds compressed video until it is due; `IngestVideoDecoder` (async MediaCodec
into a `Surface`) and `IngestAudioDecoder` (MediaCodec AAC → `PcmResampler` → 1024-frame chunks)
decode; `IngestSource` ties them to `BufferedVideo`/`BufferedAudio` and one `DriftTracker`.
`VideoPipeline` and `AudioPipeline` learn to take an `IngestSource` instead of the camera or the
microphone. The pipeline module stays independent of `transport/rtmp`: the app (stage 8) maps
`RtmpIngestMedia` onto `IngestSource` calls.

**Tech Stack:** Kotlin, Android MediaCodec (async mode), SurfaceTexture/GLES (existing OES
renderer), `core:clock`, stage 1's `ingest` package, `media/mux` (`lengthPrefixedToAnnexB`,
`MpegTsVideoConfig.Avc/Hevc.fromAvcC/fromHvcC`, `nalLengthSize`).

**Spec:** [docs/plans/2026-11-14-rtmp-server-ingest.md](2026-11-14-rtmp-server-ingest.md)
sections 4 (media/pipeline Android), 5 (decoding and timing), 6 (bounds: jitter buffer, video size,
decoding sources), 9 (risks), 10 (decisions 6, 7).

## Global Constraints

- All media times are host nanoseconds (`System.nanoTime()` domain, `HostClock`); the stage-1
  classes take seconds as `Double` — convert with `nanos / 1e9`.
- Spec §5 values: decode-ahead 150–500 ms, adaptive, starting at 300 ms; at most 6 frames held
  decoded; constant one-frame latch lag; `DriftTracker` fill level uses the newest PTS in either
  stage (jitter buffer or decoded), through `BufferedVideo`'s `newestPresentationTimeStamp`
  provider; audio resampled to 48 kHz and the pipeline's channel count in 1024-frame chunks; gaps
  become silence.
- Spec §6 bounds: jitter buffer `min(Runtime.maxMemory() / 8, 64 MiB)` and at most 1200 frames;
  video size ≤ 3840×2160 and within the decoder's capabilities; at most 2 sources decoding at once
  (decision 7).
- A source outlives pipeline re-creation (spec §4): the decoder moves to a new output surface with
  `MediaCodec.setOutputSurface`, it is not recreated.
- Decision 6: while the pipeline shows an ingest source, the built-in camera is released.
- `ingestsSoftwareVideoDecoding` (settings, stage 2) chooses a software decoder; a hardware decoder
  that fails to configure falls back to software once.
- Nothing thrown on a decoder or output thread escapes; a failure ends that source with a reason
  (spec §6 "Failures").
- Ported files start with `// Port of <upstream path> @97e26d2.`; PORTING.md rows.
- Only Android 10 (API 29) APIs; JVM modules are checked by `checkAndroidApi`, the Android module
  by lint.
- Instrumented tests live in `media/pipeline/src/androidTest` (their own test package, so the app
  is not replaced) and run on the emulator **and** the Galaxy S26
  (`ANDROID_SERIAL=<serial> ./gradlew :media:pipeline:connectedDebugAndroidTest`). Never run
  `:app:connectedDebugAndroidTest` on the S26.
- Gate before each commit: `NEXUSIRL_REQUIRE_FFMPEG=1 ./gradlew ktlintCheck test lint :app:licensee assembleDebug assembleRelease -q`
  and `sh tools/check-independence.sh`; commit only when both exit 0. Stage explicit paths.
  Trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A publisher that changes resolution or codec mid-stream** (OBS output change, GoPro mode
   switch): the decoder is recreated for a new codec or a size the running decoder cannot take,
   and the picture continues after the next key frame; no crash, no green frames. Pinned in Task 4.
2. **A publisher that stops sending** (Wi-Fi drop): the last picture stays, audio becomes silence,
   nothing grows, and decoding resumes at the next key frame when data returns. Pinned in Tasks 6–8.
3. **Pipeline re-created while an ingest source is shown** (resolution change in settings): the
   source keeps decoding into the new pipeline's surface without a restart. Pinned in Task 7.
4. **A decoder that throws or the codec service dying** (`MediaCodec.CodecException`,
   `IllegalStateException`): the source ends with a reason, the pipeline shows black, the app keeps
   running. Pinned in Task 4.
5. **A third source while two decode** (decision 7): it is refused with a reason, not decoded.
   Pinned in Task 6.

---

## File map

| File | Responsibility |
|---|---|
| `transport/rtmp/.../server/RtmpServer.kt` | Task 1: parked budget-leak fix |
| `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/IngestJitterBuffer.kt` | Compressed video waiting to be decoded |
| `.../ingest/PcmResampler.kt` | PCM rate and channel conversion |
| `.../ingest/IngestVideoDecoder.kt` | Async MediaCodec video decoder into a Surface |
| `.../ingest/IngestAudioDecoder.kt` | Async MediaCodec AAC decoder → 48 kHz chunks |
| `.../ingest/IngestSource.kt` | One ingested stream: jitter buffer, decoders, buffered selection, drift |
| `.../VideoPipeline.kt` | Shows an ingest source instead of the camera |
| `.../AudioPipeline.kt` | Sends an ingest source's audio instead of the microphone |
| `media/pipeline/src/test/.../ingest/*Test.kt` | JVM tests |
| `media/pipeline/src/androidTest/.../ingest/*Test.kt` | Instrumented tests |
| `.github/workflows/ci.yml` | Task 4: run the module's instrumented tests on CI's emulator |

---

### Task 1: RtmpServer budget bytes after `execute` (parked from stages 4–5)

**Files:** Modify `transport/rtmp/src/main/kotlin/io/github/mesmerize08/nexusirl/transport/rtmp/server/RtmpServer.kt` (`onMedia`), test in `RtmpServerTest.kt`.

- [ ] **Step 1: Write the failing test** `budgetIsNotLeakedWhenPostingFails`: with a test hook that
  makes the executor's `execute` throw `OutOfMemoryError` once for a media task (add an
  `internal var beforeMediaPost: () -> Unit = {}` called right before `executor.execute`), the
  server-wide queued byte count returns to 0 afterwards and a later message is delivered.
- [ ] **Step 2: Run it and see it fail.** `./gradlew :transport:rtmp:test --tests '*RtmpServerTest*'`
- [ ] **Step 3: Implement:** subtract the bytes (and signal waiters) when `execute` throws, then
  rethrow so `Client.run` closes the session as now.
- [ ] **Step 4: Run it and see it pass; run RtmpServerTest 3× with `--rerun-tasks`.**
- [ ] **Step 5: Gate and commit** ("RTMP server: free the media budget when posting fails").

### Task 2: IngestJitterBuffer (pure JVM)

**Files:** Create `.../ingest/IngestJitterBuffer.kt`; test `media/pipeline/src/test/.../ingest/IngestJitterBufferTest.kt`.

**Interfaces — Produces:**

```kotlin
class IngestVideoAccessUnit(val isKeyFrame: Boolean, val ptsNanos: Long, val dtsNanos: Long, val data: ByteArray)
class IngestJitterBuffer(
    private val maxBytes: Long = minOf(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024),
    private val maxFrames: Int = 1200,
) {
    fun add(unit: IngestVideoAccessUnit)           // in DTS order as received
    fun takeDue(limitDtsNanos: Long): IngestVideoAccessUnit?  // oldest unit with dts <= limit, else null
    fun newestPtsNanos(): Long?
    val size: Int; val bytes: Long
    fun clear()
}
```

  When adding would pass `maxBytes` or `maxFrames`, everything before the newest key frame already
  queued is dropped first (the last GOP is kept); if that is not enough, the whole buffer up to the
  incoming unit is dropped and units are skipped until the next key frame. A non-key unit arriving
  while waiting for a key frame is dropped.

- [ ] **Step 1: Failing tests:** `takesInDtsOrderWhenDue` (3 units, `takeDue` with limits below,
  between and above their dts); `overflowKeepsTheLastGop` (maxFrames 10: K,P×5,K,P×5 → after the
  12th add the buffer starts at the second K and holds 6); `overflowWithoutKeyFrameWaitsForNext`
  (maxFrames 4, K + P×5 then P, K, P → the P after overflow is dropped, the K and following P are
  kept); `byteBound` (maxBytes 1000, 400-byte units: never more than 1000 bytes held);
  `newestPts` (B-frames: newest is the largest pts, not the last added).
- [ ] **Step 2: Run them and see them fail.** `./gradlew :media:pipeline:testDebugUnitTest --tests '*IngestJitterBufferTest*'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Gate and commit** ("Ingest: jitter buffer for compressed video").

### Task 3: PcmResampler (pure JVM)

**Files:** Create `.../ingest/PcmResampler.kt`; test `.../ingest/PcmResamplerTest.kt`.

**Interfaces — Produces:**

```kotlin
class PcmResampler(private val inputRate: Int, private val inputChannels: Int, private val outputChannels: Int, private val outputRate: Int = 48_000, private val framesPerChunk: Int = 1024) {
    /** [pcm] interleaved 16-bit, [frames] frames, the first one at [ptsNanos]. Emits whole chunks with their pts. */
    fun process(pcm: ShortArray, frames: Int, ptsNanos: Long, emit: (chunk: ShortArray, ptsNanos: Long) -> Unit)
    fun reset()
}
```

  Linear interpolation; stereo → mono averages, mono → stereo duplicates; chunk pts =
  pts of its first output frame, computed from the input pts and sample position (not accumulated),
  so it does not drift. A pts jump of more than 100 ms from the expected next input pts resets the
  phase (a gap or a new stream).

- [ ] **Step 1: Failing tests:** `sameRatePassesThrough` (48 kHz mono in, identical samples out in
  1024-frame chunks, pts every 21.333 ms); `upsample44100` (a 1 kHz sine at 44.1 kHz → 48 kHz:
  frequency preserved within 1 % by zero-crossing count, chunk count = floor(frames × 48000/44100 /
  1024)); `stereoToMono` (L=1000, R=3000 → 2000); `monoToStereo`; `ptsDoesNotDrift` (10 minutes of
  44.1 kHz in 1024-sample reads: last chunk pts within 1 ms of the exact value); `gapResets` (a 500 ms
  pts jump → the next chunk's pts follows the new input pts).
- [ ] **Step 2–4:** red, implement, green (`--tests '*PcmResamplerTest*'`).
- [ ] **Step 5: Gate and commit** ("Ingest: PCM resampler to 48 kHz").

### Task 4: IngestVideoDecoder (Android) and module instrumented tests on CI

**Files:** Create `.../ingest/IngestVideoDecoder.kt`; test `media/pipeline/src/androidTest/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/ingest/IngestVideoDecoderTest.kt`; modify `.github/workflows/ci.yml` (ui-tests script: `./gradlew :app:connectedDebugAndroidTest :media:pipeline:connectedDebugAndroidTest --console=plain`).

**Interfaces:**
- Consumes: `IngestVideoAccessUnit` (Task 2); `MpegTsVideoConfig.Avc.fromAvcC`, `.Hevc.fromHvcC`,
  `nalLengthSize`, `lengthPrefixedToAnnexB` (media/mux).
- Produces:

```kotlin
enum class IngestVideoCodec(val mimeType: String) { H264("video/avc"), H265("video/hevc") }
class IngestVideoDecoder(
    private val name: String,
    private val softwareDecoding: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** A decoded frame waits in the decoder: release it with [release] (render or drop). */
        fun onFrame(frame: DecodedFrame)
        fun onFormat(width: Int, height: Int)
        /** The decoder ended (codec error, size over 3840×2160); the source should end. */
        fun onFailed(reason: String)
    }
    class DecodedFrame(val ptsNanos: Long, internal val index: Int, internal val generation: Int)
    fun configure(codec: IngestVideoCodec, config: ByteArray, surface: Surface)  // config = avcC or hvcC
    fun setSurface(surface: Surface)              // setOutputSurface; pipeline re-created
    fun canQueue(): Boolean                       // an input buffer is free
    fun queue(unit: IngestVideoAccessUnit)        // converts to Annex-B with the config's NAL length size
    fun release(frame: DecodedFrame, render: Boolean)  // idempotent; ignores frames of an older generation
    val heldFrames: Int                           // decoded frames not yet released
    fun close()
}
```

  Async mode (`setCallback` with a `Handler` on a dedicated `HandlerThread` named
  "ingest-video-<name>"). Configure at 1920×1080 with `KEY_MAX_WIDTH`/`KEY_MAX_HEIGHT`
  3840×2160 and csd from the config (H.264: csd-0 SPS, csd-1 PPS; HEVC: csd-0 VPS+SPS+PPS, each with
  a 4-byte start code), `KEY_LOW_LATENCY` 1 on API 30+. Decoder choice: with `softwareDecoding`, the
  first decoder whose `isSoftwareOnly` is true (API 29: name starts with "c2.android." or
  "OMX.google."); otherwise the default, falling back to software once if `configure` or `start`
  throws. A new `configure` with a different codec or config recreates the codec (new generation);
  the same config again is ignored. An output size over 3840×2160 → `onFailed("Video size W×H is
  too large")`. Every MediaCodec call is guarded; exceptions become `onFailed(<class>: <message>)`.

- [ ] **Step 1: Failing instrumented tests** (encode a test pattern with a `MediaCodec` encoder in the
  test — H.264 and HEVC when the device has an encoder, `assumeTrue` otherwise — collect its avcC/hvcC
  and length-prefixed access units with their pts, then decode them):
  `decodesH264IntoSurface` (60 frames in → 60 `onFrame` with the same pts in order; releasing with
  render=true makes a `SurfaceTexture` receive 60 frames); `decodesHevc`; `softwareDecoderWhenAsked`
  (`softwareDecoding = true` → the decoder's name is a software decoder);
  `newConfigRecreates` (H.264 at 640×360, then a new config at 1280×720 → `onFormat(1280, 720)` and
  frames continue; old-generation frames released after the switch do not throw);
  `badDataFails` (random bytes as config → `onFailed`, no exception in the test thread);
  `surfaceSwitch` (after 30 frames, `setSurface` to a second SurfaceTexture → the next frames arrive
  there).
- [ ] **Step 2: Run them on the emulator and see them fail.**
  `ANDROID_SERIAL=emulator-5554 ./gradlew :media:pipeline:connectedDebugAndroidTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them on the emulator and on the S26; both pass** (S26 serial from `adb devices`).
- [ ] **Step 5: Gate and commit** ("Ingest: video decoder, and the pipeline's device tests on CI").

### Task 5: IngestAudioDecoder (Android)

**Files:** Create `.../ingest/IngestAudioDecoder.kt`; test `.../androidTest/.../ingest/IngestAudioDecoderTest.kt`.

**Interfaces:**
- Consumes: `PcmResampler` (Task 3).
- Produces:

```kotlin
class IngestAudioDecoder(private val name: String, private val outputChannels: Int, private val listener: Listener) {
    interface Listener {
        fun onChunk(pcm: ShortArray, ptsNanos: Long)   // 1024 frames, 48 kHz, outputChannels
        fun onFailed(reason: String)
    }
    fun configure(audioSpecificConfig: ByteArray)   // sample rate and channels from the ASC
    fun queue(aac: ByteArray, ptsNanos: Long)       // raw AAC frame (no ADTS)
    fun close()
}
```

  Async MediaCodec "audio/mp4a-latm" with csd-0 = the ASC; the decoder's output format gives the
  real rate and channel count for the resampler. Input frames that find no free input buffer are
  queued (at most 50; older ones dropped with a count logged once per second). Same failure rule
  as Task 4.

- [ ] **Step 1: Failing instrumented tests** (encode 2 s of a 1 kHz sine with a MediaCodec AAC
  encoder at 44.1 kHz stereo and at 48 kHz mono in the test): `decodesAndResamples` (chunks are
  1024 frames of `outputChannels`, pts strictly increasing by 21.33 ms ± 1 ms, total duration within
  50 ms of 2 s, the tone's frequency within 2 %); `badConfigFails`.
- [ ] **Step 2–4:** red on the emulator; implement; green on the emulator and the S26.
- [ ] **Step 5: Gate and commit** ("Ingest: AAC decoder to 48 kHz chunks").

### Task 6: IngestSource

**Files:** Create `.../ingest/IngestSource.kt`; test `.../androidTest/.../ingest/IngestSourceTest.kt` (+ a JVM test for the pure parts if split out: `IngestDecodeSchedule` below).

**Interfaces:**
- Consumes: Tasks 2–5; stage 1 `BufferedVideo`, `BufferedAudio`, `BufferedAudioClock`, `DriftTracker`.
- Produces:

```kotlin
class IngestSource(
    val name: String,
    latencyNanos: Long,
    softwareDecoding: Boolean,
    private val outputChannels: Int,
    private val clock: HostClock = HostClock.system,
    private val onEnded: (reason: String) -> Unit,
) {
    fun setVideoConfig(codec: IngestVideoCodec, config: ByteArray)
    fun addVideo(isKeyFrame: Boolean, ptsNanos: Long, dtsNanos: Long, lengthPrefixed: ByteArray)
    fun setAudioConfig(audioSpecificConfig: ByteArray)
    fun addAudio(ptsNanos: Long, aac: ByteArray)
    /** Video output (called by VideoPipeline on its GL thread). */
    fun attachSurface(surface: Surface)          // first attach or after pipeline re-creation
    fun updateVideo(outputNanos: Long): Boolean  // feeds the decoder, selects and renders the frame for this output time; true if a new frame was rendered
    val hasVideo: Boolean                        // a frame has been rendered at least once
    /** Audio output (called by AudioPipeline's output thread). */
    fun audioChunk(outputNanos: Long): ShortArray?  // 1024 frames at 48 kHz, zeros in gaps, null before the first chunk
    fun close()
    companion object { const val MAX_DECODING = 2 }
}
class IngestDecodeSchedule(initialAheadNanos: Long = 300_000_000, minAheadNanos: Long = 150_000_000, maxAheadNanos: Long = 500_000_000) {
    val aheadNanos: Long
    fun onLate()            // a frame was decoded after its output time: +50 ms (max)
    fun onTick(nowNanos: Long)  // −10 ms per second without a late frame (min)
}
```

  Video path: `addVideo` → jitter buffer; `updateVideo(t)` feeds the decoder while
  `canQueue()`, `heldFrames < 6` and the next unit's `dts + drift ≤ t + aheadNanos`; decoded frames
  go into `BufferedVideo<DecodedFrame>` (pts seconds, `onRelease` = `release(frame, render = false)`,
  `newestPresentationTimeStamp` = max(jitter buffer newest, decoded newest)); the frame
  `updateFrame(t)` returns is released with `render = true`. A frame selected after its pts + 1 frame
  interval counts as late (`onLate`). Audio path: AAC → `IngestAudioDecoder` → `BufferedAudio`
  (manualOutput = true) → `audioChunk(t)`: `getFrame(t)`, zeros when `silent`. One `DriftTracker`
  for both. The decoding-source registry: a process-wide count; the third concurrent source's
  `setVideoConfig` calls `onEnded("Too many video sources")` and decodes nothing. Any decoder
  failure → `onEnded(reason)` once, then the source is closed.

- [ ] **Step 1: Failing tests.** JVM `IngestDecodeScheduleTest`: starts at 300 ms; `onLate` ×5 →
  500 (clamped); 40 s of `onTick` without late → 150 (clamped). Instrumented `IngestSourceTest`
  (encoded fixtures as in Tasks 4–5, timestamps offset to "now + 500 ms"): `videoFramesComeOutInOrderAtTheirTime`
  (drive `updateVideo` every 33 ms → rendered frames in pts order, each within one frame interval of
  its pts + latency); `heldDecodedFramesNeverExceedSix`; `audioChunksOnGrid` (chunks for 2 s, zeros
  before data and in a 300 ms gap); `thirdSourceIsRefused`; `stopSendingKeepsLastFrame` (no data for
  2 s → `updateVideo` keeps returning false, no growth, `hasVideo` stays true; data again from a key
  frame → frames resume); `decoderFailureEndsTheSource` (corrupt config → `onEnded` once).
- [ ] **Step 2–4:** red (emulator); implement; green on the emulator and the S26.
- [ ] **Step 5: Gate and commit** ("Ingest: a source that decodes and times an ingested stream").

### Task 7: VideoPipeline shows an ingest source

**Files:** Modify `media/pipeline/src/main/kotlin/io/github/mesmerize08/nexusirl/media/pipeline/VideoPipeline.kt`; test `.../androidTest/.../ingest/VideoPipelineIngestTest.kt`.

**Interfaces — Produces on `VideoPipeline`:**

```kotlin
/** Shows [source] instead of the camera (the camera is released); null goes back to the camera given. */
fun showIngestSource(source: IngestSource?, cameraIdWhenNull: String? = null)
```

  On the GL thread: create an OES texture + `SurfaceTexture` sized to the canvas (as
  `createSurfaceOverlay` does), `source.attachSurface(Surface(texture))`, stop the camera
  (`showCamera` false while black); each frame-timer tick calls `source.updateVideo(outputNanos)`
  before composing; when the source's texture has a new frame, `updateTexImage` and draw it with the
  OES renderer (aspect-fit into the canvas, black bars) where the camera would be; black until
  `hasVideo`. Widgets, LUTs and effects apply unchanged. A new `VideoPipeline` given the same source
  re-attaches it (spec: the source outlives the pipeline). `showIngestSource(null, id)` releases the
  texture and starts camera `id`.

- [ ] **Step 1: Failing instrumented tests** (pipeline at 640×360, an encoder sink collecting
  frames, the source fed with encoded fixtures): `ingestPictureReachesTheEncoder` (after 1 s, encoded
  frames' decoded content is not black — read back via `takeSnapshot` and check average luminance >
  0.2); `blackUntilFirstFrame`; `pipelineRecreationKeepsTheSource` (stop pipeline A, start B with the
  same source → frames continue in B within 1 s); `backToCamera` (on the emulator with
  `-camera-back none`: no camera → black, no crash).
- [ ] **Step 2–4:** red (emulator); implement; green on the emulator and the S26.
- [ ] **Step 5: Gate and commit** ("Video pipeline: show an ingest source instead of the camera").

### Task 8: AudioPipeline sends an ingest source's audio

**Files:** Modify `.../AudioPipeline.kt`; test `.../androidTest/.../ingest/AudioPipelineIngestTest.kt`.

**Interfaces — Produces on `AudioPipeline`:**

```kotlin
/** Sends [source]'s audio to the encoders instead of the microphone; null goes back to the microphone. */
fun useIngestAudio(source: IngestSource?)
```

  An output thread ("audio-ingest") ticks on a `BufferedAudioClock(48000, 1024)` grid against the
  host clock and feeds `source.audioChunk(t) ?: silence` to the same encoders the microphone feeds,
  with the same gain, mute and level meter; while it runs, microphone captures are read but not
  fed (switching back is instant). The output pts follow the grid, strictly increasing (a step back
  of the clock repeats nothing: if the grid gives a pts ≤ the last one, that tick is skipped).

- [ ] **Step 1: Failing instrumented tests:** `ingestAudioReachesTheEncoder` (an AAC encoder sink
  receives frames whose pts increase by 21.33 ms, decoded content has the fixture's tone);
  `silenceInGaps`; `switchBackToMicrophone` (no gap larger than 50 ms in encoder pts across the
  switch); `muteApplies`.
- [ ] **Step 2–4:** red (emulator); implement; green on the emulator and the S26.
- [ ] **Step 5: Gate and commit** ("Audio pipeline: send an ingest source's audio"), and update the
  spec's status line (stages 6–7 done) and PORTING.md.

---

## After these tasks

Stage 8: app wiring (`IngestController`, server lifecycle, keys from the secret store, scenes with
`cameraPosition` "RTMP", network mics, remote-control status), then stage 9 UI.
