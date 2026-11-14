package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
}
