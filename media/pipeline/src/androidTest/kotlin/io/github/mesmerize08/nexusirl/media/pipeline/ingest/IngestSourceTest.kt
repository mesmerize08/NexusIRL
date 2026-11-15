package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class IngestSourceTest {
    private val ended = LinkedBlockingQueue<Pair<String, String>>()
    private val closeables = ArrayList<AutoCloseable>()

    private fun source(name: String = "test"): IngestSource =
        IngestSource(name, LATENCY, softwareDecoding = false, outputChannels = 2, onEnded = { ended.add(name to it) })
            .also { closeables += AutoCloseable { it.close() } }

    private fun sink(): TextureSink = TextureSink().also { closeables += it }

    @After
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
        assertEquals("a source is still counted as decoding", 0, IngestSource.decodingSources)
    }

    /** [units] moved so the first one's PTS is [firstPtsNanos], DTS [dtsOffsetNanos] before PTS. */
    private fun shifted(units: List<IngestVideoAccessUnit>, firstPtsNanos: Long, dtsOffsetNanos: Long = 0): List<IngestVideoAccessUnit> {
        val shift = firstPtsNanos - units.first().ptsNanos
        return units.map { IngestVideoAccessUnit(it.isKeyFrame, it.ptsNanos + shift, it.dtsNanos + shift - dtsOffsetNanos, it.data) }
    }

    private fun IngestSource.add(units: List<IngestVideoAccessUnit>) {
        units.forEach { addVideo(it.isKeyFrame, it.ptsNanos, it.dtsNanos, it.data) }
    }

    private fun sleepUntil(nanos: Long) {
        val millis = (nanos - System.nanoTime()) / 1_000_000
        if (millis > 0) Thread.sleep(millis)
    }

    /** Calls updateVideo every 33 ms until [untilNanos]; returns (output time, rendered pts) of each rendered frame. */
    private fun drive(source: IngestSource, untilNanos: Long, onTick: () -> Unit = {}): List<Pair<Long, Long>> {
        val rendered = ArrayList<Pair<Long, Long>>()
        var next = System.nanoTime()
        while (System.nanoTime() < untilNanos) {
            val now = System.nanoTime()
            if (source.updateVideo(now)) rendered += now to source.lastRenderedPtsNanos
            onTick()
            next += FRAME_NANOS
            sleepUntil(next)
        }
        return rendered
    }

    /** Asserts frames came out in PTS order, each within one frame interval of its PTS (which includes the latency). */
    private fun assertOnTime(rendered: List<Pair<Long, Long>>, expectedPts: List<Long>) {
        val pts = rendered.map { it.second }
        assertEquals("rendered out of order", pts.sorted().distinct(), pts)
        assertTrue("rendered ${pts.size} of ${expectedPts.size}", pts.size >= expectedPts.size * 8 / 10)
        assertTrue("not the stream's frames", pts.all { it in expectedPts })
        val offsets = rendered.map { (output, framePts) -> output - framePts }
        Log.i(TAG, "output - pts (ms): ${offsets.map { it / 1_000_000 }}")
        assertTrue("a frame shown before its time: ${offsets.min()}", offsets.all { it >= -11_000_000 })
        val onTime = offsets.count { it <= FRAME_NANOS }
        assertTrue("only $onTime of ${offsets.size} within one frame interval", onTime >= offsets.size * 8 / 10)
        assertTrue("a frame ${offsets.max() / 1_000_000} ms late", offsets.all { it <= 200_000_000 })
    }

    @Test
    fun videoFramesComeOutInOrderAtTheirTime() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 90)
        val sink = sink()
        val source = source()
        // As the RTMP server stamps them: publisher time + host base + latency.
        val units = shifted(stream.units, System.nanoTime() + LATENCY)
        source.setVideoConfig(stream.codec, stream.config)
        source.attachSurface(sink.surface)
        source.add(units)
        assertFalse(source.hasVideo)
        val rendered = drive(source, units.last().ptsNanos + 500_000_000)
        assertNull("ended", ended.peek())
        assertTrue(source.hasVideo)
        assertOnTime(rendered, units.map { it.ptsNanos })
        assertTrue("sink got ${sink.timestamps.size} of ${rendered.size}", sink.timestamps.size >= rendered.size - 2)
    }

    /**
     * Feeding stops once six decoded frames are held (spec §5). Units already queued when the sixth
     * frame came out may still finish, so the peak can pass six by at most the in-flight cap.
     */
    @Test
    fun feedingStopsAtSixHeldFrames() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 60)
        val sink = sink()
        val source = source()
        // Every unit due for decoding at once (DTS long past), none due for showing yet.
        val units = shifted(stream.units, System.nanoTime() + 10_000_000_000, dtsOffsetNanos = 20_000_000_000)
        source.setVideoConfig(stream.codec, stream.config)
        source.attachSurface(sink.surface)
        source.add(units)
        var most = 0
        val first = drive(source, System.nanoTime() + 1_500_000_000) { most = maxOf(most, source.heldFrames) }
        val held = source.heldFrames
        val queued = source.queuedUnits
        // Then nothing more is fed and nothing grows.
        val second = drive(source, System.nanoTime() + 1_500_000_000) {
            most = maxOf(most, source.heldFrames)
            assertEquals(queued, source.queuedUnits)
        }
        Log.i(TAG, "held $held (at most $most), $queued units still queued")
        assertTrue("rendered ${first + second}", first.isEmpty() && second.isEmpty())
        assertTrue("held only $held", held >= 6)
        assertEquals(held, source.heldFrames)
        assertTrue("held $most", most <= 6 + 16)
        assertTrue(queued > 0)
        assertNull("ended", ended.peek())
    }

    @Test
    fun audioChunksOnGrid() {
        assumeTrue("no AAC encoder", AacTestEncoder.has())
        val audio = AacTestEncoder.encode(48_000, 1, seconds = 2.0, startNanos = 0)
        val source = source()
        source.setAudioConfig(audio.audioSpecificConfig)
        val start = System.nanoTime()
        val firstPts = start + LATENCY
        val gap = firstPts + 800_000_000 until firstPts + 1_100_000_000
        val shift = firstPts - audio.frames.first().second
        val frames = audio.frames.map { (data, pts) -> data to pts + shift }.filter { it.second !in gap }
        val lastPts = frames.last().second
        assertNull("a chunk before any audio arrived", source.audioChunk(start))
        val feeder = Thread {
            // Real time, as a publisher sends: each frame a latency before its PTS.
            for ((data, pts) in frames) {
                sleepUntil(pts - LATENCY)
                source.addAudio(pts, data)
            }
        }.apply { start() }
        closeables += AutoCloseable { feeder.join() }
        val chunkNanos = 1024 * 1_000_000_000L / 48_000
        class Out(val t: Long, val pcm: ShortArray?, val pts: Long)
        val outs = ArrayList<Out>()
        var k = 0L
        while (true) {
            val t = start + k++ * chunkNanos
            if (t > lastPts + 300_000_000) break
            sleepUntil(t)
            val pcm = source.audioChunk(t)
            outs += Out(t, pcm?.copyOf(), source.lastAudioPtsNanos)
        }
        assertNull("ended", ended.peek())
        fun ShortArray.loud() = any { abs(it.toInt()) > 1000 }
        assertTrue(outs.all { it.pcm == null || it.pcm.size == 1024 * 2 })
        val before = outs.filter { it.t < firstPts - 25_000_000 }
        assertTrue("no zeros before data", before.any { it.pcm != null && !it.pcm.loud() })
        assertTrue("sound before data", before.none { it.pcm?.loud() == true })
        val sound = outs.filter {
            it.t in firstPts + 100_000_000..gap.first - 60_000_000 || it.t in gap.last + 100_000_000..lastPts - 60_000_000
        }
        val quiet = sound.filter { it.pcm?.loud() != true }.map { (it.t - firstPts) / 1_000_000 }
        assertTrue("silent at $quiet ms", quiet.isEmpty())
        val inGap = outs.filter { it.t in gap.first + 60_000_000..gap.last - 30_000_000 }
        assertTrue(inGap.size >= 8)
        val loudInGap = inGap.filter { it.pcm?.loud() != false }.map { (it.t - firstPts) / 1_000_000 }
        assertTrue("sound in the gap at $loudInGap ms", loudInGap.isEmpty())
        // On the grid: each chunk played is the one whose time it is.
        val offsets = sound.map { it.t - it.pts }
        Log.i(TAG, "audio output - pts (ms): ${offsets.map { it / 1_000_000 }}")
        assertTrue("chunk early or late: ${offsets.min()}..${offsets.max()}", offsets.all { abs(it) <= 3 * chunkNanos })
        assertTrue(offsets.count { it in -2_000_000..chunkNanos + 2_000_000 } >= offsets.size * 9 / 10)
    }

    /** Audio not consumed for a while (output paused): the first chunk after is the one for its time, not stale. */
    @Test
    fun audioAfterAPauseIsNotStale() {
        assumeTrue("no AAC encoder", AacTestEncoder.has())
        val audio = AacTestEncoder.encode(48_000, 1, seconds = 4.0, startNanos = 0)
        val source = source()
        source.setAudioConfig(audio.audioSpecificConfig)
        val firstPts = System.nanoTime() + LATENCY
        val shift = firstPts - audio.frames.first().second
        val frames = audio.frames.map { (data, pts) -> data to pts + shift }
        val feeder = Thread {
            for ((data, pts) in frames) {
                sleepUntil(pts - LATENCY)
                source.addAudio(pts, data)
            }
        }.apply { start() }
        closeables += AutoCloseable { feeder.join() }
        val chunkNanos = 1024 * 1_000_000_000L / 48_000
        var t = firstPts
        // Played on the grid for a second.
        while (t < firstPts + 1_000_000_000) {
            sleepUntil(t)
            source.audioChunk(t)
            t += chunkNanos
        }
        // Then nothing consumed for a second and a half.
        t += 1_500_000_000
        sleepUntil(t)
        assertNotNull(source.audioChunk(t))
        val age = t - source.lastAudioPtsNanos
        Log.i(TAG, "first chunk after the pause: ${age / 1_000_000} ms old")
        assertTrue("first chunk after the pause ${age / 1_000_000} ms old", age in -3 * chunkNanos..3 * chunkNanos)
        assertNull("ended", ended.peek())
    }

    /**
     * A pipeline re-created while the source decodes: the old surface is detached and released at
     * once, the decoder keeps decoding what it was given, and the new surface gets frames again.
     */
    @Test
    fun detachedSurfaceCanBeReleasedWhileDecoding() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 150)
        val first = TextureSink()
        val source = source()
        val units = shifted(stream.units, System.nanoTime() + LATENCY)
        source.setVideoConfig(stream.codec, stream.config)
        source.attachSurface(first.surface)
        source.add(units)
        drive(source, units[15].ptsNanos).let { assertTrue("rendered ${it.size}", it.isNotEmpty()) }
        source.detachSurface(first.surface)
        assertTrue("not parked on a surface of its own", source.hasParkingSurface)
        first.close()
        // The decoder still busy: units fed now (a tick racing the detach) are decoded meanwhile.
        source.updateVideo(units[40].ptsNanos)
        Thread.sleep(500)
        assertNull("ended after the detach", ended.peek())
        val second = sink()
        source.attachSurface(second.surface)
        assertFalse("the parking surface is kept after re-attaching", source.hasParkingSurface)
        val rendered = drive(source, System.nanoTime() + 1_000_000_000)
        assertNull("ended", ended.peek())
        assertTrue("rendered ${rendered.size} after re-attaching", rendered.size >= 15)
        assertTrue("second sink got ${second.timestamps.size}", second.timestamps.size >= 10)
    }

    /** The longest distance between key frames of [units] (the GOP length). */
    private fun gopNanos(units: List<IngestVideoAccessUnit>): Long =
        units.filter { it.isKeyFrame }.map { it.ptsNanos }.zipWithNext { a, b -> b - a }.maxOrNull() ?: Long.MAX_VALUE

    /**
     * A backlog when a pipeline starts driving the source starts at the newest key frame due, not
     * with seconds of old pictures fast-forwarded: every frame rendered is at most a GOP (and a
     * frame) behind the output time it was rendered at.
     */
    private fun assertNoBacklogPlayed(rendered: List<Pair<Long, Long>>, attachNanos: Long, gopNanos: Long) {
        Log.i(TAG, "after attach: output - pts (ms): ${rendered.map { (it.first - it.second) / 1_000_000 }}")
        assertTrue("rendered only ${rendered.size}", rendered.size >= 10)
        val (firstOutput, firstPts) = rendered.first()
        assertTrue(
            "first frame ${(firstOutput - firstPts) / 1_000_000} ms old (GOP ${gopNanos / 1_000_000} ms)",
            firstOutput - firstPts <= gopNanos + FRAME_NANOS,
        )
        val old = rendered.filter { it.second < attachNanos - gopNanos - FRAME_NANOS }
        assertTrue("${old.size} frames of the backlog rendered", old.isEmpty())
    }

    @Test
    fun backlogBeforeTheFirstAttachIsSkipped() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 180)
        val source = source()
        // Five seconds of units arrived before anything showed the source.
        val units = shifted(stream.units, System.nanoTime() - 5_000_000_000)
        val gop = gopNanos(units)
        assumeTrue("GOP ${gop / 1_000_000} ms", gop <= 2_000_000_000)
        source.setVideoConfig(stream.codec, stream.config)
        source.add(units)
        val sink = sink()
        val attach = System.nanoTime()
        source.attachSurface(sink.surface)
        val rendered = drive(source, attach + 1_000_000_000)
        assertNull("ended", ended.peek())
        assertNoBacklogPlayed(rendered, attach, gop)
    }

    @Test
    fun backlogWhileParkedIsSkipped() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 300)
        val first = TextureSink()
        val source = source()
        val units = shifted(stream.units, System.nanoTime() + LATENCY)
        val gop = gopNanos(units)
        assumeTrue("GOP ${gop / 1_000_000} ms", gop <= 2_000_000_000)
        source.setVideoConfig(stream.codec, stream.config)
        source.attachSurface(first.surface)
        source.add(units)
        drive(source, units[30].ptsNanos).let { assertTrue("rendered ${it.size}", it.isNotEmpty()) }
        // No pipeline drives the source for three seconds (parked): the units become a backlog.
        source.detachSurface(first.surface)
        first.close()
        Thread.sleep(3_000)
        val second = sink()
        val attach = System.nanoTime()
        source.attachSurface(second.surface)
        val rendered = drive(source, attach + 1_000_000_000)
        assertNull("ended", ended.peek())
        assertNoBacklogPlayed(rendered, attach, gop)
    }

    @Test
    fun newConfigMidStreamContinues() {
        // A publisher changing resolution: a new config, then the new stream from its key frame.
        val small = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 45)
        val large = TestEncoder.encode(IngestVideoCodec.H264, 1280, 720, frames = 60)
        val sink = sink()
        val source = source()
        source.attachSurface(sink.surface)
        source.setVideoConfig(small.codec, small.config)
        val lap1 = shifted(small.units, System.nanoTime() + LATENCY)
        source.add(lap1)
        // Switch while the first stream still has frames buffered.
        drive(source, lap1[30].ptsNanos).let { assertTrue("first stream rendered ${it.size}", it.size >= 15) }
        val lap2 = shifted(large.units, System.nanoTime() + LATENCY)
        source.setVideoConfig(large.codec, large.config)
        source.add(lap2)
        val rendered = drive(source, lap2.last().ptsNanos + 500_000_000)
        assertNull("ended", ended.peek())
        assertTrue("a frame of the old stream after the switch", rendered.all { it.second >= lap2.first().ptsNanos })
        assertOnTime(rendered, lap2.map { it.ptsNanos })
    }

    @Test
    fun thirdSourceIsRefused() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 10)
        assertEquals(2, IngestSource.MAX_DECODING)
        val first = source("first")
        val second = source("second")
        val third = source("third")
        first.setVideoConfig(stream.codec, stream.config)
        second.setVideoConfig(stream.codec, stream.config)
        assertEquals(2, IngestSource.decodingSources)
        third.setVideoConfig(stream.codec, stream.config)
        assertEquals("third" to "Too many video sources", ended.poll(1, TimeUnit.SECONDS))
        assertEquals(2, IngestSource.decodingSources)
        // It decodes nothing.
        val sink = sink()
        third.attachSurface(sink.surface)
        third.add(shifted(stream.units, System.nanoTime()))
        assertFalse(third.updateVideo(System.nanoTime() + 1_000_000_000))
        assertEquals(0, third.queuedUnits)
        // A closed source makes room.
        first.close()
        assertEquals(1, IngestSource.decodingSources)
        source("fourth").setVideoConfig(stream.codec, stream.config)
        assertNull(ended.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(2, IngestSource.decodingSources)
    }

    @Test
    fun stopSendingKeepsLastFrame() {
        val stream = TestEncoder.encode(IngestVideoCodec.H264, 640, 360, frames = 60)
        val sink = sink()
        val source = source()
        source.setVideoConfig(stream.codec, stream.config)
        source.attachSurface(sink.surface)
        val lap1 = shifted(stream.units, System.nanoTime() + LATENCY)
        source.add(lap1)
        drive(source, lap1.last().ptsNanos + 300_000_000).let { assertTrue("lap 1 rendered ${it.size}", it.size >= 40) }
        assertTrue(source.hasVideo)
        // The publisher stops sending for 2 s: the last picture stays and nothing grows.
        val held = source.heldFrames
        var most = held
        val paused = drive(source, System.nanoTime() + 2_000_000_000) {
            most = maxOf(most, source.heldFrames)
            assertEquals(0, source.queuedUnits)
        }
        assertTrue("rendered while paused: $paused", paused.isEmpty())
        assertTrue("held $held, then $most", most <= held)
        assertTrue(source.hasVideo)
        assertNull("ended", ended.peek())
        // Data again, from a key frame: frames resume.
        val lap2 = shifted(stream.units, System.nanoTime() + LATENCY)
        assertTrue(lap2.first().isKeyFrame)
        source.add(lap2)
        val rendered = drive(source, lap2.last().ptsNanos + 500_000_000).filter { it.second >= lap2.first().ptsNanos }
        assertNull("ended", ended.peek())
        assertOnTime(rendered, lap2.map { it.ptsNanos })
    }

    @Test
    fun decoderFailureEndsTheSource() {
        val sink = sink()
        val source = source()
        source.attachSurface(sink.surface)
        source.setVideoConfig(IngestVideoCodec.H264, Random(1).nextBytes(64))
        val end = ended.poll(5, TimeUnit.SECONDS)
        assertNotNull("not ended", end)
        val (name, reason) = end!!
        Log.i(TAG, "ended: $reason")
        assertEquals("test", name)
        assertTrue(reason.isNotEmpty())
        // Then it is closed: nothing more, and no longer counted.
        source.addVideo(true, 0, 0, ByteArray(100))
        assertFalse(source.updateVideo(System.nanoTime()))
        assertNull(ended.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(0, IngestSource.decodingSources)
    }

    private companion object {
        const val TAG = "IngestSourceTest"
        const val LATENCY = 500_000_000L
        const val FRAME_NANOS = 33_333_333L
    }
}
