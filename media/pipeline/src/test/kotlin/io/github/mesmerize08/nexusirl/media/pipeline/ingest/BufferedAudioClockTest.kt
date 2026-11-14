package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals

class BufferedAudioClockTest {
    private val period = 1024.0 / 48000.0

    @Test
    fun followsGrid() {
        val clock = BufferedAudioClock(48000, 1024)
        for (k in 0 until 100) {
            val t = 10.0 + k * period
            assertEquals(t, clock.next(t), 1e-9)
        }
    }

    @Test
    fun stepsBackWhenAhead() {
        val clock = BufferedAudioClock(48000, 1024)
        assertEquals(10.0, clock.next(10.0), 1e-9)
        assertEquals(10.0, clock.next(10.0 + period - 0.05), 1e-9)
    }

    @Test
    fun stepsForwardWhenBehind() {
        val clock = BufferedAudioClock(48000, 1024)
        clock.next(10.0)
        assertEquals(10.0 + 2 * period, clock.next(10.0 + period + 0.05), 1e-9)
    }
}
