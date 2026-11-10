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

    @Test
    fun inconsistentOffsetsStillReanchorAfterAFewReads() {
        // getTimestamp failing now and then: fallback estimates disagree with each other by 25 ms,
        // but all say the count is 30-55 ms off. After four reads, re-anchor to their median.
        val timeline = CaptureTimeline(48_000)
        timeline.next(0, 1024)
        val offsets = listOf(30_000_000L, 55_000_000L, 30_000_000L, 55_000_000L)
        offsets.forEachIndexed { index, offset ->
            val frames = (index + 1) * 1024L
            val pts = timeline.next(nanos(frames) + offset, 1024)
            if (index < 3) {
                assertEquals(nanos(frames), pts)
                assertFalse(timeline.resync)
            } else {
                assertTrue(timeline.resync)
                assertEquals(nanos(frames) + 42_500_000, pts)
            }
        }
        assertEquals(5 * 1024L, timeline.framesRead)
    }

    @Test
    fun jitterAroundTheCountDoesNotReanchor() {
        val timeline = CaptureTimeline(48_000)
        timeline.next(0, 1024)
        (1..12).forEach {
            val offset = if (it % 2 == 0) 25_000_000L else -25_000_000L
            assertEquals(nanos(it * 1024L), timeline.next(nanos(it * 1024L) + offset, 1024))
            assertFalse(timeline.resync)
        }
    }
}
