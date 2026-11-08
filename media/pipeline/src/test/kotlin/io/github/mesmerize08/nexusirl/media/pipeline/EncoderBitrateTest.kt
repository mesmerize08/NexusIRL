package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncoderBitrateTest {
    @Test
    fun theTargetIsTheRequestedBitrate() {
        val bitrate = EncoderBitrate(5_000_000, reduceWhenStill = true)
        assertEquals(5_000_000, bitrate.target)
        bitrate.requested = 3_000_000
        assertEquals(3_000_000, bitrate.target)
    }

    @Test
    fun aStillPictureGetsATenthUntilTheCameraIsBack() {
        // Field test 2026-11-08: 1 MB keyframes while the camera was away, 2 fps once it was back.
        val bitrate = EncoderBitrate(5_000_000, reduceWhenStill = true)
        bitrate.still = true
        assertEquals(500_000, bitrate.target)
        assertTrue(bitrate.reduced)
        bitrate.still = false
        assertEquals(5_000_000, bitrate.target)
        assertFalse(bitrate.reduced)
    }

    @Test
    fun onlyACbrStreamIsReduced() {
        // VBR (recordings, VBR streams) does not spend unused bits on a still picture.
        val bitrate = EncoderBitrate(5_000_000, reduceWhenStill = false)
        bitrate.still = true
        assertEquals(5_000_000, bitrate.target)
        assertFalse(bitrate.reduced)
    }

    @Test
    fun aStillPictureGetsAtLeastHalfAMegabit() {
        // A tenth of 2 Mbps would let the data rate limiter through a frame every few seconds.
        val bitrate = EncoderBitrate(2_000_000, reduceWhenStill = true)
        bitrate.still = true
        assertEquals(500_000, bitrate.target)
    }

    @Test
    fun aStillPictureNeverGetsMoreThanRequested() {
        val bitrate = EncoderBitrate(300_000, reduceWhenStill = true)
        bitrate.still = true
        assertEquals(300_000, bitrate.target)
    }

    @Test
    fun aBitrateRequestedDuringAStillPictureIsTheTargetOnceTheCameraIsBack() {
        val bitrate = EncoderBitrate(8_000_000, reduceWhenStill = true)
        bitrate.still = true
        bitrate.requested = 6_000_000
        assertEquals(600_000, bitrate.target)
        bitrate.still = false
        assertEquals(6_000_000, bitrate.target)
    }
}
