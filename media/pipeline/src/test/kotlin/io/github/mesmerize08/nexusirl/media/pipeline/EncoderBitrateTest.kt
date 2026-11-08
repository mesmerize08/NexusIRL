package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EncoderBitrateTest {
    @Test
    fun appliesTheRequestedBitrate() {
        val bitrate = EncoderBitrate(5_000_000)
        assertEquals(5_000_000, bitrate.applied)
        assertEquals(3_000_000, bitrate.request(3_000_000))
        assertEquals(3_000_000, bitrate.applied)
    }

    @Test
    fun aStillPictureGetsATenthUntilTheCameraIsBack() {
        // Field test 2026-11-08: 1 MB keyframes while the camera was away, 2 fps once it was back.
        val bitrate = EncoderBitrate(5_000_000)
        assertEquals(500_000, bitrate.setStill(true))
        assertEquals(500_000, bitrate.applied)
        assertEquals(5_000_000, bitrate.setStill(false))
        assertEquals(5_000_000, bitrate.applied)
    }

    @Test
    fun aBitrateRequestedDuringAStillPictureAppliesWhenTheCameraIsBack() {
        val bitrate = EncoderBitrate(5_000_000)
        bitrate.setStill(true)
        // Adaptive bitrate lowers it meanwhile: a tenth of that now, all of it later.
        assertEquals(200_000, bitrate.request(2_000_000))
        assertEquals(2_000_000, bitrate.setStill(false))
    }

    @Test
    fun nothingToApplyWhenNothingChanges() {
        val bitrate = EncoderBitrate(5_000_000)
        assertNull(bitrate.request(5_000_000))
        assertNull(bitrate.setStill(false))
        bitrate.setStill(true)
        assertNull(bitrate.setStill(true))
        assertNull(bitrate.request(5_000_000))
    }
}
