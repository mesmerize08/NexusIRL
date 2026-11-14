package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BufferedVideoTest {
    private fun create(update: Boolean = true, onReady: () -> Unit = {}, onRelease: (Double) -> Unit = {}) = BufferedVideo<Double>(
        name = "",
        update = update,
        latency = 0.1,
        driftTracker = DriftTracker(""),
        presentationTimeStamp = { it },
        onReady = onReady,
        onRelease = onRelease,
    )

    @Test
    fun appendOutOfOrderEarlyFrame() {
        val video = create()
        video.appendFrame(2.0)
        video.appendFrame(3.0)
        video.appendFrame(1.0)
        assertEquals(3, video.numberOfFrames())
        video.updateFrame(1.5)
        assertEquals(2, video.numberOfFrames())
    }

    @Test
    fun appendFramesInReverseOrder() {
        val video = create()
        video.appendFrame(3.0)
        video.appendFrame(2.0)
        video.appendFrame(1.0)
        assertEquals(3, video.numberOfFrames())
        video.updateFrame(2.5)
        assertEquals(1, video.numberOfFrames())
    }

    @Test
    fun capOf200DropsOldest() {
        val released = mutableListOf<Double>()
        val video = create(onRelease = { released.add(it) })
        val frames = (0 until 250).map { it * 0.01 }
        frames.forEach { video.appendFrame(it) }
        video.updateFrame(0.0)
        assertTrue(video.numberOfFrames() <= 200)
        assertEquals(200, video.numberOfFrames())
        // Every dropped frame except the one that became the latest frame is released.
        val latest = video.latestFrame()
        assertEquals(frames[49], latest)
        assertEquals(frames.subList(0, 49), released)
    }

    @Test
    fun readyFiresOnceAfterFirstConsumedFrame() {
        var ready = 0
        val video = create(onReady = { ready += 1 })
        video.appendFrame(1.0)
        video.appendFrame(1.033)
        video.updateFrame(0.5)
        assertEquals(0, ready)
        video.updateFrame(1.0)
        assertEquals(1, ready)
        video.updateFrame(1.033)
        assertEquals(1, ready)
    }

    @Test
    fun noUpdateWithoutUpdateFlag() {
        val video = create(update = false)
        video.appendFrame(1.0)
        video.updateFrame(5.0)
        assertEquals(1, video.numberOfFrames())
        assertNull(video.latestFrame())
        video.updateFrame(5.0, forceUpdate = true)
        assertEquals(0, video.numberOfFrames())
        assertEquals(1.0, video.latestFrame())
    }

    @Test
    fun latestReplacedIsReleased() {
        val released = mutableListOf<Double>()
        val video = create(onRelease = { released.add(it) })
        video.appendFrame(1.0)
        video.appendFrame(2.0)
        video.updateFrame(1.0)
        assertEquals(1.0, video.latestFrame())
        assertTrue(released.isEmpty())
        video.updateFrame(2.0)
        assertEquals(2.0, video.latestFrame())
        assertEquals(listOf(1.0), released)
    }

    private class Frame(val pts: Double)

    /** Counts releases per frame object, so a double release shows up. */
    private class Releases {
        val counts = IdentityHashMap<Frame, Int>()

        fun release(frame: Frame) {
            counts[frame] = (counts[frame] ?: 0) + 1
        }
    }

    private fun createFrames(
        releases: Releases,
        newest: (() -> Double?)? = null,
        latency: Double = 0.1,
        tracker: DriftTracker? = DriftTracker(""),
    ) = BufferedVideo<Frame>(
        name = "",
        update = true,
        latency = latency,
        driftTracker = tracker,
        presentationTimeStamp = { it.pts },
        onRelease = releases::release,
        newestPresentationTimeStamp = newest,
    )

    /** 60 s of output at 30 fps, with the publisher's frames arriving only 0.2 s ahead of the output. */
    private fun driveWithShortQueue(newest: ((Double) -> Double?)?): DriftTracker {
        val tracker = DriftTracker("")
        var output = 0.0
        val video = createFrames(Releases(), newest?.let { provider -> { provider(output) } }, latency = 2.0, tracker = tracker)
        var next = 0
        for (tick in 0..60 * 30) {
            output = tick / 30.0
            while (next / 30.0 <= output + 0.2) {
                video.appendFrame(Frame(next / 30.0))
                next += 1
            }
            video.updateFrame(output)
        }
        return tracker
    }

    @Test
    fun newestPresentationTimeStampProviderDrivesTheDriftTracker() {
        // The decoder stage holds the rest of the latency: the stream is on target, the drift stays.
        val tracker = driveWithShortQueue { output -> output + 2.0 }
        assertEquals(0.0, tracker.getDrift())
    }

    @Test
    fun withoutProviderTheQueueDrivesTheDriftTracker() {
        // Upstream behaviour: a queue far below its target fill level moves the drift.
        val tracker = driveWithShortQueue(null)
        assertNotEquals(0.0, tracker.getDrift())
    }

    @Test
    fun providerReturningNullFallsBackToTheQueue() {
        assertEquals(driveWithShortQueue(null).getDrift(), driveWithShortQueue { null }.getDrift())
    }

    @Test
    fun appendWithoutConsumerStaysWithinHardCapAndReleasesDropped() {
        val releases = Releases()
        val video = createFrames(releases)
        val frames = (0 until 1000).map { Frame(it * 0.01) }
        frames.forEach { video.appendFrame(it) }
        assertTrue(video.numberOfFrames() <= 400, "${video.numberOfFrames()} frames held")
        assertEquals(1000 - video.numberOfFrames(), releases.counts.size)
        assertTrue(releases.counts.values.all { it == 1 }, "a frame was released twice")
        assertTrue(frames.subList(0, releases.counts.size).all { it in releases.counts }, "not the oldest dropped")
    }

    @Test
    fun updateFrameReturnsTheNewlySelectedFrame() {
        val video = createFrames(Releases())
        val first = Frame(1.0)
        val second = Frame(2.0)
        video.appendFrame(first)
        video.appendFrame(second)
        assertSame(first, video.updateFrame(1.0))
        assertNull(video.updateFrame(1.0), "a duplicate tick selects nothing new")
        assertSame(second, video.updateFrame(2.0))
    }

    @Test
    fun setLatestFrameReleasesTheReplacedFrame() {
        val releases = Releases()
        val video = createFrames(releases)
        val a = Frame(1.0)
        val b = Frame(2.0)
        video.setLatestFrame(a)
        video.setLatestFrame(a)
        assertTrue(releases.counts.isEmpty(), "re-setting the same frame releases nothing")
        video.setLatestFrame(b)
        assertEquals(mapOf(a to 1), releases.counts.toMap())
        video.setLatestFrame(null)
        assertEquals(mapOf(a to 1, b to 1), releases.counts.toMap())
        assertNull(video.latestFrame())
    }

    @Test
    fun clearReleasesEveryHeldFrameOnce() {
        val releases = Releases()
        val video = createFrames(releases)
        val frames = (0 until 5).map { Frame(1.0 + it) }
        frames.forEach { video.appendFrame(it) }
        video.updateFrame(1.0)
        assertSame(frames[0], video.latestFrame())
        video.clear()
        assertEquals(0, video.numberOfFrames())
        assertNull(video.latestFrame())
        assertEquals(frames.toSet(), releases.counts.keys)
        assertTrue(releases.counts.values.all { it == 1 }, "a frame was released twice")
        video.clear()
        assertTrue(releases.counts.values.all { it == 1 }, "clearing twice released again")
    }
}
