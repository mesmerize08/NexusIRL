package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StillPictureTest {
    @Test
    fun stillAfterTheThresholdOfFrameTimesWithoutACameraImage() {
        val still = StillPicture(thresholdTicks = 15)
        repeat(14) { assertNull(still.tick()) }
        assertEquals(true, still.tick())
        assertNull(still.tick())
        assertTrue(still.isStill)
    }

    @Test
    fun aCameraImageEndsIt() {
        val still = StillPicture(thresholdTicks = 15)
        repeat(15) { still.tick() }
        assertEquals(false, still.cameraImage())
        assertNull(still.cameraImage())
        assertNull(still.tick())
    }

    @Test
    fun aSlowCameraIsNotStill() {
        // 15 fps in the dark: an image every other frame time.
        val still = StillPicture(thresholdTicks = 15)
        repeat(100) {
            assertNull(still.tick())
            assertNull(still.tick())
            assertNull(still.cameraImage())
        }
    }

    @Test
    fun anImageArrivingAfterTheCameraIsLostDoesNotEndTheOutage() {
        // An image already on its way when the camera disconnects arrives after the loss.
        val still = StillPicture(thresholdTicks = 15)
        repeat(15) { still.tick() }
        assertEquals(false, still.cameraImage())
        repeat(14) { assertNull(still.tick()) }
        assertEquals(true, still.tick())
    }
}
