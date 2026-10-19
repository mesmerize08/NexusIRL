package io.github.mesmerize08.nexusirl.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of MoblinTests/Moblin/Media/WrappingTimestampSuite.swift @97e26d2. */
class WrappingTimestampTest {
    private fun seconds(value: Double) = MediaTime.fromSeconds(value)

    @Test
    fun all() {
        val timestamp = WrappingTimestamp("Test", seconds(1024.0))
        assertEquals(30.0, timestamp.update(seconds(30.0)).seconds)
        assertEquals(-1.0, timestamp.update(seconds(1023.0)).seconds)
        assertEquals(500.0, timestamp.update(seconds(500.0)).seconds)
        assertEquals(700.0, timestamp.update(seconds(700.0)).seconds)
        assertEquals(1000.0, timestamp.update(seconds(1000.0)).seconds)
        assertEquals(1054.0, timestamp.update(seconds(30.0)).seconds)
        assertEquals(1000.0, timestamp.update(seconds(1000.0)).seconds)
        assertEquals(1524.0, timestamp.update(seconds(500.0)).seconds)
        assertEquals(2024.0, timestamp.update(seconds(1000.0)).seconds)
        assertEquals(2048.0, timestamp.update(seconds(0.0)).seconds)
        assertEquals(2046.0, timestamp.update(seconds(1022.0)).seconds)
        assertEquals(2046.0, timestamp.update(seconds(1022.0)).seconds)
    }

    @Test
    fun mpegTsWrapAt33Bits() {
        val period = 1L shl 33
        val max = MediaTime(period, MediaTime.MPEG_TS)
        var wraps = 0
        val timestamp = WrappingTimestamp("pts", max) { wraps++ }
        // Advance steadily (as a real stream does) up to just before the 33-bit wrap point.
        for (step in 0L..3L) {
            val pts = step * period / 4
            assertEquals(pts, timestamp.update(MediaTime(pts, MediaTime.MPEG_TS)).value)
        }
        assertEquals(period - 90_000, timestamp.update(MediaTime(period - 90_000, MediaTime.MPEG_TS)).value)
        assertEquals(0, wraps)
        // The raw PTS wraps to a small value; the unwrapped timestamp keeps increasing.
        assertEquals(period + 90_000, timestamp.update(MediaTime(90_000, MediaTime.MPEG_TS)).value)
        assertEquals(1, wraps)
        // A late packet from before the wrap maps back into the previous period.
        assertEquals(period - 45_000, timestamp.update(MediaTime(period - 45_000, MediaTime.MPEG_TS)).value)
    }
}
