package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.pipeline.AudioEncoderFormat
import io.github.mesmerize08.nexusirl.media.pipeline.AudioEncoderKey
import io.github.mesmerize08.nexusirl.media.pipeline.AudioPipeline
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The audio pipeline sending an [IngestSource]'s audio to its encoders instead of the microphone.
 * The microphone permission is granted, but the emulator's microphone may give silence or nothing
 * (then the pipeline sends its own silence), so no test depends on what it records.
 */
@RunWith(AndroidJUnit4::class)
class AudioPipelineIngestTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ended = LinkedBlockingQueue<String>()
    private val cleanup = ArrayList<() -> Unit>()

    /** The PTS (microseconds) of every chunk queued to the test pipeline's encoder. */
    private val encoderInputs = CopyOnWriteArrayList<Long>()

    /** When ([System.nanoTime]) each of [encoderInputs] was queued. */
    private val encoderInputTimes = CopyOnWriteArrayList<Long>()

    /** Every encoded audio frame with its PTS (microseconds), and the config. */
    private class RecordingSink : MediaSink {
        @Volatile
        var config: MpegTsAudioConfig? = null
        val frames = CopyOnWriteArrayList<Pair<ByteArray, Long>>()

        override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {
            this.config = config
        }

        override fun setVideoConfig(config: MpegTsVideoConfig) {}

        override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) {
            frames += frame to presentationTimeStamp.value * 1_000_000 / presentationTimeStamp.timescale
        }

        override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {}
    }

    @Before
    fun setUp() {
        assumeTrue("no AAC encoder", AacTestEncoder.has())
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        cleanup.asReversed().forEach { it() }
        assertEquals("a source is still counted as decoding", 0, IngestSource.decodingSources)
    }

    private fun source(channels: Int = CHANNELS): IngestSource {
        val source = IngestSource("test", LATENCY, softwareDecoding = false, outputChannels = channels, onEnded = { ended.add(it) })
        cleanup += { source.close() }
        return source
    }

    private fun pipeline(sink: MediaSink, format: AudioEncoderFormat = AudioEncoderFormat.AAC): AudioPipeline {
        val pipeline = AudioPipeline(channels = CHANNELS)
        cleanup += { pipeline.stop() }
        pipeline.encoderInputListener = { _, ptsNanos, _ ->
            encoderInputTimes += System.nanoTime()
            encoderInputs += ptsNanos / 1000
        }
        val encoded = pipeline.addEncoder(AudioEncoderKey(format, 128_000), sink)
        assumeTrue("no $format encoder", encoded.format == format)
        return pipeline
    }

    private fun sleepUntil(nanos: Long) {
        val millis = (nanos - System.nanoTime()) / 1_000_000
        if (millis > 0) Thread.sleep(millis)
    }

    /** [seconds] of a 1 kHz tone, encoded before a test's timeline starts: a slow device takes over a second for it. */
    private fun tone(seconds: Double) = AacTestEncoder.encode(48_000, CHANNELS, seconds = seconds, startNanos = 0)

    /**
     * Sends [audio] (a [tone]) to [source] in real time, as a publisher would: its first frame's PTS
     * is [firstPts], each frame arrives a latency before its PTS, frames in [gap] are not sent.
     * Returns the last PTS sent.
     */
    private fun sendTone(source: IngestSource, firstPts: Long, audio: EncodedAudio, gap: LongRange = LongRange.EMPTY): Long {
        // Frames sent after their output time are stale and dropped, as they should be: the
        // test itself must keep to its schedule.
        val behind = System.nanoTime() - (firstPts - LATENCY)
        assertTrue("the test started sending ${behind / 1_000_000} ms late", behind < LATENCY / 2)
        source.setAudioConfig(audio.audioSpecificConfig)
        val shift = firstPts - audio.frames.first().second
        val frames = audio.frames.map { (data, pts) -> data to pts + shift }.filter { it.second !in gap }
        val feeder = Thread {
            for ((data, pts) in frames) {
                sleepUntil(pts - LATENCY)
                source.addAudio(pts, data)
            }
        }.apply { start() }
        cleanup += { feeder.join() }
        return frames.last().second
    }

    /** The encoder's frames decoded back to 48 kHz chunks with their PTS (nanoseconds). */
    private fun decode(sink: RecordingSink, frames: List<Pair<ByteArray, Long>>): List<Pair<ShortArray, Long>> {
        val chunks = LinkedBlockingQueue<Pair<ShortArray, Long>>()
        val failures = LinkedBlockingQueue<String>()
        val decoder = IngestAudioDecoder(
            "check",
            CHANNELS,
            object : IngestAudioDecoder.Listener {
                override fun onChunk(pcm: ShortArray, ptsNanos: Long) {
                    chunks.add(pcm to ptsNanos)
                }

                override fun onFailed(reason: String) {
                    failures.add(reason)
                }
            },
        )
        try {
            decoder.configure(checkNotNull(sink.config).encode())
            for ((frame, pts) in frames) {
                decoder.queue(frame, pts * 1000)
                Thread.sleep(2)
            }
            val result = ArrayList<Pair<ShortArray, Long>>()
            while (true) {
                result += chunks.poll(1, TimeUnit.SECONDS) ?: break
            }
            assertNull("decoding failed", failures.peek())
            return result
        } finally {
            decoder.close()
        }
    }

    private fun ShortArray.loud() = any { abs(it.toInt()) > 1000 }

    /** The frequency of the first channel's tone, from its rising zero crossings. */
    private fun frequency(chunks: List<ShortArray>): Double {
        val samples = chunks.flatMap { pcm -> (pcm.indices step CHANNELS).map { pcm[it].toInt() } }
        var first = -1.0
        var last = -1.0
        var crossings = 0
        for (i in 1 until samples.size) {
            if (samples[i - 1] < 0 && samples[i] >= 0) {
                val at = i - 1 + samples[i - 1].toDouble() / (samples[i - 1] - samples[i])
                if (first < 0) first = at else crossings++
                last = at
            }
        }
        return crossings * 48_000.0 / (last - first)
    }

    private fun assertStrictlyIncreasing(pts: List<Long>) {
        for (i in 1 until pts.size) {
            assertTrue("pts ${pts[i]} after ${pts[i - 1]} (frame $i)", pts[i] > pts[i - 1])
        }
    }

    /**
     * The PTS (µs) of the first chunk queued to the encoder after [nanos], less the encoder's
     * rounding. Called with the time [AudioPipeline.useIngestAudio] returned: from then on only the
     * ingest output feeds. Its PTS, not the switch time, starts the output to check: the
     * microphone's last chunks before the switch may be timed after it (the emulator's timestamps
     * run ahead under load), and then the gap at the switch would be counted as a hole.
     */
    private fun firstPtsQueuedAfter(nanos: Long): Long {
        val index = encoderInputTimes.indexOfFirst { it >= nanos }
        assertTrue("nothing queued after the switch", index >= 0)
        return encoderInputs[index] - 2
    }

    private fun ingestThreads() = Thread.getAllStackTraces().keys.filter { it.name == "audio-ingest" && it.isAlive }

    @Test
    fun ingestAudioReachesTheEncoder() {
        val sink = RecordingSink()
        val pipeline = pipeline(sink)
        pipeline.start()
        val source = source()
        val audio = tone(2.0)
        val start = System.nanoTime()
        pipeline.useIngestAudio(source, videoDelayNanos = 0)
        val switched = System.nanoTime()
        val firstPts = start + LATENCY
        val lastPts = sendTone(source, firstPts, audio)
        var loudest = -160f
        while (System.nanoTime() < lastPts + 300_000_000) {
            Thread.sleep(20)
            if (System.nanoTime() in firstPts + 200_000_000..lastPts - 200_000_000) loudest = maxOf(loudest, pipeline.levelDbfs)
        }
        pipeline.stop()
        assertTrue("the output thread still runs after stop", ingestThreads().isEmpty())
        assertNull("ended", ended.peek())
        val ingestFrom = firstPtsQueuedAfter(switched)
        val frames = sink.frames.filter { it.second >= ingestFrom }
        val pts = frames.map { it.second }
        assertStrictlyIncreasing(pts)
        val steps = (1 until pts.size).map { pts[it] - pts[it - 1] }
        Log.i(TAG, "${frames.size} frames, steps (µs): ${steps.groupingBy { it }.eachCount()}")
        assertTrue("only ${frames.size} frames", frames.size >= 100)
        val onGrid = steps.count { abs(it - 21_333) <= 2 }
        assertTrue("only $onGrid of ${steps.size} steps are 21.33 ms", onGrid >= steps.size * 9 / 10)
        assertTrue("nothing sent before the source had audio", pts.first() < (firstPts - 100_000_000) / 1000)
        val chunks = decode(sink, frames)
        val before = chunks.filter { it.second < firstPts - 100_000_000 }
        assertTrue("sound before the source had audio", before.isNotEmpty() && before.none { it.first.loud() })
        val tone = chunks.filter { it.second in firstPts + 150_000_000..lastPts - 150_000_000 }
        val quiet = tone.filter { !it.first.loud() }.map { (it.second - firstPts) / 1_000_000 }
        assertTrue("silent at $quiet ms", quiet.isEmpty() && tone.size >= 60)
        val frequency = frequency(tone.map { it.first })
        assertEquals("tone $frequency Hz", 1000.0, frequency, 20.0)
        assertTrue("level meter $loudest dBFS during the tone", loudest > -20f)
    }

    @Test
    fun silenceInGaps() {
        val sink = RecordingSink()
        val pipeline = pipeline(sink)
        pipeline.start()
        val source = source()
        val audio = tone(2.0)
        val start = System.nanoTime()
        pipeline.useIngestAudio(source, videoDelayNanos = 0)
        val switched = System.nanoTime()
        val firstPts = start + LATENCY
        val gap = firstPts + 800_000_000 until firstPts + 1_200_000_000
        val lastPts = sendTone(source, firstPts, audio, gap = gap)
        sleepUntil(lastPts + 300_000_000)
        pipeline.stop()
        assertNull("ended", ended.peek())
        val ingestFrom = firstPtsQueuedAfter(switched)
        val frames = sink.frames.filter { it.second >= ingestFrom }
        val pts = frames.map { it.second }
        assertStrictlyIncreasing(pts)
        val longest = (1 until pts.size).maxOf { pts[it] - pts[it - 1] }
        assertTrue("a $longest µs hole in the output", longest < 25_000)
        val chunks = decode(sink, frames)
        val inGap = chunks.filter { it.second in gap.first + 100_000_000..gap.last - 100_000_000 }
        val loudInGap = inGap.filter { it.first.loud() }.map { (it.second - firstPts) / 1_000_000 }
        assertTrue("sound in the gap at $loudInGap ms", inGap.size >= 8 && loudInGap.isEmpty())
        val sound = chunks.filter {
            it.second in firstPts + 150_000_000..gap.first - 100_000_000 || it.second in gap.last + 150_000_000..lastPts - 150_000_000
        }
        val quiet = sound.filter { !it.first.loud() }.map { (it.second - firstPts) / 1_000_000 }
        assertTrue("silent at $quiet ms", sound.size >= 40 && quiet.isEmpty())
    }

    @Test
    fun switchBackToMicrophone() = switchBackToMicrophone(AudioEncoderFormat.AAC)

    /** Opus is fed in 960-frame chunks, so a chunk overlapping the last one would step its pts back. */
    @Test
    fun switchBackToMicrophoneOpus() = switchBackToMicrophone(AudioEncoderFormat.OPUS)

    /** Each frame starts at or after the end of the one before (1 ms tolerance), and pts strictly increase. */
    private fun assertNoOverlap(pts: List<Long>, frameMicros: Long) {
        assertStrictlyIncreasing(pts)
        for (i in 1 until pts.size) {
            val overlap = pts[i - 1] + frameMicros - pts[i]
            assertTrue("frame $i overlaps the one before by $overlap µs", overlap <= 1_000)
        }
    }

    private fun switchBackToMicrophone(format: AudioEncoderFormat) {
        val sink = RecordingSink()
        val pipeline = pipeline(sink, format)
        pipeline.start()
        Thread.sleep(500)
        assumeTrue("the microphone (or its silence) delivered nothing", sink.frames.size >= 5)
        val source = source()
        val audio = tone(1.5)
        val switched = System.nanoTime()
        pipeline.useIngestAudio(source, videoDelayNanos = 0)
        sendTone(source, switched + LATENCY, audio)
        Thread.sleep(1_200)
        val switchedBack = System.nanoTime()
        pipeline.useIngestAudio(null, videoDelayNanos = 0)
        Thread.sleep(200)
        assertTrue("the output thread still runs on the microphone", ingestThreads().isEmpty())
        Thread.sleep(600)
        pipeline.stop()
        val frameMicros = format.framesPerChunk * 1_000_000L / 48_000
        // What the codec was given (an encoder may count its output times from its first input).
        assertNoOverlap(encoderInputs, frameMicros)
        val pts = sink.frames.map { it.second }
        assertNoOverlap(pts, frameMicros)
        val after = pts.filter { it > switchedBack / 1000 }
        assertTrue("only ${after.size} frames after switching back", after.size >= 20)
        val steps = (1 until pts.size).map { pts[it - 1] to pts[it] - pts[it - 1] }
        val toIngest = steps.filter { it.first in switched / 1000 - 200_000..switched / 1000 + 100_000 }.maxOf { it.second }
        val back = steps.filter { it.first in switchedBack / 1000 - 100_000..switchedBack / 1000 + 300_000 }.maxOf { it.second }
        Log.i(TAG, "$format, microphone ${pipeline.microphoneAvailable}: longest step into ingest $toIngest µs, back $back µs")
        assertTrue("a $back µs hole switching back", back <= 50_000)
        assertTrue("a $toIngest µs hole switching to the source", toIngest <= 100_000)
    }

    @Test
    fun muteApplies() {
        val sink = RecordingSink()
        val pipeline = pipeline(sink)
        pipeline.muted = true
        pipeline.start()
        val source = source()
        val audio = tone(1.0)
        val start = System.nanoTime()
        pipeline.useIngestAudio(source, videoDelayNanos = 0)
        val firstPts = start + LATENCY
        val lastPts = sendTone(source, firstPts, audio)
        var loudest = -160f
        while (System.nanoTime() < lastPts + 200_000_000) {
            Thread.sleep(20)
            loudest = maxOf(loudest, pipeline.levelDbfs)
        }
        pipeline.stop()
        assertNull("ended", ended.peek())
        assertTrue("the source never played", source.lastAudioPtsNanos != 0L)
        assertEquals("level meter while muted", -160f, loudest)
        val chunks = decode(sink, sink.frames.filter { it.second >= start / 1000 })
        assertTrue("only ${chunks.size} chunks", chunks.size >= 40)
        val loud = chunks.filter { pcm -> pcm.first.any { abs(it.toInt()) > 50 } }.map { (it.second - firstPts) / 1_000_000 }
        assertTrue("sound while muted at $loud ms", loud.isEmpty())
    }

    /** The output asks for the chunk of its time minus the video delay, and stamps it with its own time. */
    @Test
    fun videoDelayShiftsTheChunk() {
        val sink = RecordingSink()
        val pipeline = pipeline(sink)
        val asked = CopyOnWriteArrayList<Long>()
        pipeline.useIngestChunks({
            asked += it
            ShortArray(1024 * CHANNELS)
        }, videoDelayNanos = 33_000_000)
        Thread.sleep(1_000)
        pipeline.stop()
        val pts = sink.frames.map { it.second }
        // The encoder may count its output times in samples from the first input: allow its rounding.
        val nearest = asked.map { time -> pts.minOf { abs(it - (time + 33_000_000) / 1000) } }
        val matched = nearest.count { it <= 2 }
        Log.i(TAG, "asked ${asked.size}, ${pts.size} frames, $matched matched, distances (µs) $nearest")
        assertTrue("only ${asked.size} chunks asked for", asked.size >= 35)
        assertTrue("only $matched of ${asked.size} frames at the time asked + 33 ms", matched >= asked.size * 8 / 10)
    }

    /**
     * A source that throws an Error kills the output thread: the microphone (or its silence)
     * takes over at once, and asking for the same source again restarts the output.
     */
    @Test
    fun deadOutputGivesTheMicrophoneBack() {
        val sink = RecordingSink()
        val pipeline = pipeline(sink)
        pipeline.start()
        Thread.sleep(500)
        assumeTrue("the microphone (or its silence) delivered nothing", sink.frames.size >= 5)
        val calls = AtomicInteger()
        val chunks: (Long) -> ShortArray? = {
            if (calls.incrementAndGet() == 1) throw AssertionError("test: the source breaks")
            ShortArray(1024 * CHANNELS)
        }
        val died = System.nanoTime()
        pipeline.useIngestChunks(chunks)
        Thread.sleep(500)
        assertEquals(1, calls.get())
        assertTrue("the dead output thread is still there", ingestThreads().isEmpty())
        assertNoOverlap(encoderInputs, 21_333)
        val pts = sink.frames.map { it.second }
        assertNoOverlap(pts, 21_333)
        val after = pts.filter { it > died / 1000 }
        assertTrue("only ${after.size} frames after the output died", after.size >= 15)
        val steps = (1 until pts.size).filter { pts[it] > died / 1000 - 100_000 }.map { pts[it] - pts[it - 1] }
        assertTrue("a ${steps.max()} µs hole after the output died", steps.max() <= 100_000)
        pipeline.useIngestChunks(chunks)
        Thread.sleep(300)
        assertTrue("the same source again did not restart: ${calls.get()} calls", calls.get() >= 10)
        assertEquals(1, ingestThreads().size)
    }

    @Test
    fun sourceWithOtherChannelsIsRefused() {
        val pipeline = pipeline(RecordingSink())
        val mono = source(channels = 1)
        assertThrows(IllegalArgumentException::class.java) { pipeline.useIngestAudio(mono, videoDelayNanos = 0) }
        assertTrue(ingestThreads().isEmpty())
    }

    private companion object {
        const val TAG = "AudioPipelineIngestTest"
        const val LATENCY = 500_000_000L
        const val CHANNELS = 2
    }
}
