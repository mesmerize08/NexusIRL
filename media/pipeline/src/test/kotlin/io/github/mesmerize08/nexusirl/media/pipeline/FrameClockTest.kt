package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameClockTest {
    private val interval = 1_000_000_000L / 60

    @Test
    fun jitteryClockGivesAnExactGrid() {
        val clock = FrameClock(interval)
        val start = 5_000_000_000L
        // Ticks arrive up to ±8 ms late or early, as a Handler timer does.
        val jitter = longArrayOf(0, 3_000_000, -2_000_000, 8_000_000, -8_000_000, 1_000_000)
        val times = (0 until 600).map { clock.next(start + it * interval + jitter[it % jitter.size]) }
        val deltas = times.zipWithNext { a, b -> b - a }.toSet()
        assertEquals(setOf(interval), deltas)
    }

    @Test
    fun followsAClockThatRunsFastWithSingleFrameSteps() {
        val clock = FrameClock(interval)
        // The clock gains 1 % on the grid; the grid follows it one frame at a time.
        var now = 0L
        val times = (0 until 3000).map {
            now += interval * 101 / 100
            clock.next(now)
        }
        val deltas = times.zipWithNext { a, b -> b - a }
        assertTrue(deltas.all { it == interval || it == 2 * interval }, "steps of one frame only")
        assertTrue(abs(times.last() - now) <= FrameClock.DRIFT_LIMIT_NANOS + interval)
    }

    @Test
    fun followsAClockThatRunsSlow() {
        val clock = FrameClock(interval)
        var now = 0L
        val times = (0 until 3000).map {
            now += interval * 99 / 100
            clock.next(now)
        }
        val deltas = times.zipWithNext { a, b -> b - a }
        assertTrue(deltas.all { it == interval || it == 0L }, "a repeated time is dropped by the pipeline")
        assertTrue(abs(times.last() - now) <= FrameClock.DRIFT_LIMIT_NANOS + interval)
    }

    @Test
    fun startsAtTheFirstClockTime() {
        val clock = FrameClock(interval)
        assertEquals(1234L, clock.next(1234L))
        assertEquals(1234L + interval, clock.next(1234L + interval))
    }
}
