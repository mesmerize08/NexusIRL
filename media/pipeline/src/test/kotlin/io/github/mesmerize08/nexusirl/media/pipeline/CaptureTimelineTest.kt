package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureTimelineTest {
    private fun nanos(frames: Long) = frames * 1_000_000_000L / 48_000

    @Test
    fun timesComeFromTheSampleCountWhileTheClockJitters() {
        val timeline = CaptureTimeline(48_000)
        assertEquals(1_000_000_000L, timeline.next(1_000_000_000L, 1024))
        assertFalse(timeline.resync)
        // Android's per-read timestamps jitter by several milliseconds; times stay evenly spaced.
        assertEquals(1_000_000_000L + nanos(1024), timeline.next(1_000_000_000L + nanos(1024) + 8_000_000, 1024))
        assertFalse(timeline.resync)
        assertEquals(1_000_000_000L + nanos(2048), timeline.next(1_000_000_000L + nanos(2048) - 7_000_000, 1024))
        assertFalse(timeline.resync)
    }

    @Test
    fun oneBadTimestampDoesNotMoveTheTimeline() {
        val timeline = CaptureTimeline(48_000)
        timeline.next(0, 1024)
        assertEquals(nanos(1024), timeline.next(nanos(1024) + 5_000_000_000L, 1024))
        assertFalse(timeline.resync)
        assertEquals(nanos(2048), timeline.next(nanos(2048), 1024))
        assertFalse(timeline.resync)
    }

    @Test
    fun aLastingShiftReanchorsOnTheSecondRead() {
        // E.g. after a device change the clock says 50 ms later than counted, read after read.
        val timeline = CaptureTimeline(48_000)
        timeline.next(0, 1024)
        val shift = 50_000_000L
        assertEquals(nanos(1024), timeline.next(nanos(1024) + shift, 1024))
        assertFalse(timeline.resync)
        assertEquals(nanos(2048) + shift, timeline.next(nanos(2048) + shift + 2_000_000, 1024) - 2_000_000)
        assertTrue(timeline.resync)
        // Counted from the new anchor afterwards.
        assertEquals(nanos(2048) + shift + 2_000_000 + nanos(1024), timeline.next(nanos(3072) + shift, 1024))
        assertFalse(timeline.resync)
    }
}
