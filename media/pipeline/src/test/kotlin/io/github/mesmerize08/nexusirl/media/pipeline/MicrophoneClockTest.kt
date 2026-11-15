package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MicrophoneClockTest {
    private val chunkNanos = 1024 * 1_000_000_000L / 48_000

    /** Reads of 1024 frames measured at [measured] (one per read); returns each read's time. */
    private fun times(clock: MicrophoneClock, measured: List<Long>): List<Long> = measured.map { clock.next(it, 1024) }

    @Test
    fun timesAreCountedFromTheFirstRead() {
        val clock = MicrophoneClock(48_000)
        // Measurements jitter by a few milliseconds; the times do not.
        val measured = (0 until 10).map { 1_000_000_000L + it * chunkNanos + (if (it % 2 == 0) 3_000_000 else -3_000_000) }
        val times = times(clock, measured)
        assertEquals(1_003_000_000L, times[0])
        for (i in 1 until times.size) assertEquals(1_003_000_000L + i * 1024 * 1_000_000_000L / 48_000, times[i])
    }

    /** One late timestamp (a busy device) does not move the times: no gap forward, no step back after it. */
    @Test
    fun oneOutlyingMeasurementIsIgnored() {
        val clock = MicrophoneClock(48_000)
        val measured = (0 until 10).map { 1_000_000_000L + it * chunkNanos + if (it == 4) 33_000_000 else 0 }
        val times = times(clock, measured)
        for (i in 1 until times.size) assertEquals(chunkNanos, times[i] - times[i - 1], 1L)
    }

    /** A lasting offset (a device change, or a first timestamp that was off) is followed. */
    @Test
    fun aLastingOffsetReanchors() {
        val clock = MicrophoneClock(48_000)
        val measured = (0 until 10).map { 1_000_000_000L + it * chunkNanos + if (it >= 2) -40_000_000 else 0 }
        val times = times(clock, measured)
        // Counted on from the new anchor (measured in whole nanoseconds per chunk here).
        assertEquals(measured.last(), times.last(), 1_000L)
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Long) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")
}
