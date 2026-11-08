package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StillPictureTest {
    /** At 30 fps: still after half a second, animating at 8 or more changes a second. */
    private fun stillPicture() = StillPicture(thresholdTicks = 15, windowTicks = 30, animatingChanges = 8)

    private fun StillPicture.tick() = tick(contentChanged = false, switching = false)

    @Test
    fun stillAfterTheThresholdOfFrameTimesWithoutACameraImage() {
        val still = stillPicture()
        repeat(14) { assertNull(still.tick()) }
        assertEquals(true, still.tick())
        assertNull(still.tick())
        assertTrue(still.isStill)
    }

    @Test
    fun aCameraImageEndsIt() {
        val still = stillPicture()
        repeat(15) { still.tick() }
        assertEquals(false, still.cameraImage())
        assertNull(still.cameraImage())
        assertNull(still.tick())
    }

    @Test
    fun aSlowCameraIsNotStill() {
        // 15 fps in the dark: an image every other frame time.
        val still = stillPicture()
        repeat(100) {
            assertNull(still.tick())
            assertNull(still.tick())
            assertNull(still.cameraImage())
        }
    }

    @Test
    fun anImageArrivingAfterTheCameraIsLostDoesNotEndTheOutage() {
        // An image already on its way when the camera disconnects arrives after the loss.
        val still = stillPicture()
        repeat(15) { still.tick() }
        assertEquals(false, still.cameraImage())
        repeat(14) { assertNull(still.tick()) }
        assertEquals(true, still.tick())
    }

    @Test
    fun aCameraSwitchIsNotStill() {
        // Closing one camera and opening the next takes a second or more; the transition animates.
        val still = stillPicture()
        repeat(100) { assertNull(still.tick(contentChanged = false, switching = true)) }
        assertFalse(still.isStill)
    }

    @Test
    fun aCameraSwitchEndsIt() {
        val still = stillPicture()
        repeat(15) { still.tick() }
        assertEquals(false, still.tick(contentChanged = false, switching = true))
    }

    @Test
    fun animatedWidgetsAreNotStill() {
        // A browser widget playing an animation: a new image every frame time.
        val still = stillPicture()
        repeat(100) { assertNull(still.tick(contentChanged = true, switching = false)) }
    }

    @Test
    fun aWidgetChangingOnceASecondIsStill() {
        // A clock: one small change a second, which a low bitrate encodes fine.
        val still = stillPicture()
        val changes = (0 until 60).map { still.tick(contentChanged = it % 30 == 0, switching = false) }
        assertEquals(listOf<Boolean?>(true), changes.filterNotNull())
        assertTrue(still.isStill)
    }

    @Test
    fun aWidgetStartingToAnimateEndsItUntilItStops() {
        val still = stillPicture()
        repeat(15) { still.tick() }
        val whileAnimating = (0 until 30).map { still.tick(contentChanged = true, switching = false) }
        assertEquals(listOf<Boolean?>(false), whileAnimating.filterNotNull())
        val afterwards = (0 until 60).map { still.tick() }
        assertEquals(listOf<Boolean?>(true), afterwards.filterNotNull())
    }
}
