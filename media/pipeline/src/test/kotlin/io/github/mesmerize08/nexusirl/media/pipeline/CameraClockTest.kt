package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraClockTest {
    // Monotonic 10190 s, boot time 16885 s: the phone slept about 6695 s since boot.
    private val monotonic = 10_190_196_136_266L
    private val realtime = 16_885_062_885_621L

    @Test
    fun realtimeStampsAreShifted() {
        // A frame captured 85 ms ago, stamped in boot time.
        val stamp = realtime - 85_000_000
        assertEquals(monotonic - 85_000_000, CameraClock.toHostClock(stamp, true, monotonic, realtime))
    }

    /** The Galaxy S26 Ultra case: the sensor says REALTIME but the stamps are already monotonic. */
    @Test
    fun monotonicStampsAreKeptEvenWhenTheSensorSaysRealtime() {
        val stamp = monotonic - 85_000_000
        assertEquals(stamp, CameraClock.toHostClock(stamp, true, monotonic, realtime))
    }

    @Test
    fun unknownSourceIsKept() {
        val stamp = realtime - 85_000_000
        assertEquals(stamp, CameraClock.toHostClock(stamp, false, monotonic, realtime))
    }
}
