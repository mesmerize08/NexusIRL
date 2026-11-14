package io.github.mesmerize08.nexusirl.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaTimeTest {
    /** [multiplyHigh] replaces Math.multiplyHigh, which Android only has from API 31. */
    @Test
    fun multiplyHighMatchesBigInteger() {
        val random = java.util.Random(29)
        val values = listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0xFFFF_FFFFL, -0x1_0000_0000L) +
            List(2000) { random.nextLong() }
        for (x in values) {
            for (y in values.take(40)) {
                val expected = java.math.BigInteger.valueOf(x).multiply(java.math.BigInteger.valueOf(y)).shiftRight(64).toLong()
                assertEquals(expected, multiplyHigh(x, y), "$x * $y")
            }
        }
    }

    @Test
    fun comparesHugeValuesAcrossTimescales() {
        // value * timescale overflows 64 bits; the comparison must still be exact.
        val a = MediaTime(Long.MAX_VALUE / 2, MediaTime.MPEG_TS)
        val b = MediaTime(Long.MAX_VALUE / 2, MediaTime.NANOSECONDS)
        assertTrue(a > b)
        assertTrue(MediaTime(-Long.MAX_VALUE / 3, MediaTime.MPEG_TS) < MediaTime(-1, MediaTime.NANOSECONDS))
        assertEquals(MediaTime(90_000, MediaTime.MPEG_TS), MediaTime(1_000_000_000, MediaTime.NANOSECONDS))
    }

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
