package io.github.mesmerize08.nexusirl.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaTimeTest {
    @Test
    fun mixedTimescaleArithmeticUsesFinerScale() {
        val sum = MediaTime(90_000, MediaTime.MPEG_TS) + MediaTime(500_000_000, MediaTime.NANOSECONDS)
        assertEquals(MediaTime.NANOSECONDS, sum.timescale)
        assertEquals(1.5, sum.seconds)
    }

    @Test
    fun equalityAndOrderingAcrossTimescales() {
        assertEquals(MediaTime(1, 1), MediaTime(90_000, MediaTime.MPEG_TS))
        assertEquals(MediaTime(1, 1).hashCode(), MediaTime(90_000, MediaTime.MPEG_TS).hashCode())
        assertTrue(MediaTime(1, 1) < MediaTime(90_001, MediaTime.MPEG_TS))
        assertTrue(MediaTime(-1, 1) < MediaTime(0, MediaTime.NANOSECONDS))
    }

    @Test
    fun comparisonDoesNotOverflow() {
        val big = MediaTime(Long.MAX_VALUE / 2, MediaTime.NANOSECONDS)
        val bigger = MediaTime(Long.MAX_VALUE / 2 / 1000 + 1, 1_000_000)
        assertTrue(big < bigger)
    }

    @Test
    fun fakeClockAdvances() {
        val clock = FakeHostClock()
        clock.advanceMillis(20)
        assertEquals(0.02, clock.now().seconds)
    }
}
