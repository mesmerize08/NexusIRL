package io.github.mesmerize08.nexusirl.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals

class MapCameraTest {
    private fun groundWidth(camera: MapCamera, side: Int) =
        40_075_016.686 * cos(Math.toRadians(camera.latitude)) / (512 * 2.0.pow(camera.zoom)) * side

    @Test
    fun standingStill() {
        val camera = MapCamera.compute(59.0, 18.0, 0.0, 90.0, northUp = false, distance = 1000.0, sidePixels = 400)
        assertEquals(59.0, camera.latitude)
        assertEquals(18.0, camera.longitude)
        assertEquals(90.0, camera.bearing)
        assertEquals(0.0, camera.dotOffsetRatio)
        // The same ground width as MapKit at 1000 m: 2 × tan(15°) × 1000.
        assertEquals(2 * tan(PI / 12) * 1000, groundWidth(camera, 400), 1e-6)
        assertEquals(0.0, MapCamera.compute(59.0, 18.0, 0.0, 90.0, northUp = true, distance = 1000.0, sidePixels = 400).bearing)
        assertEquals(0.0, MapCamera.compute(59.0, 18.0, 0.0, -1.0, northUp = false, distance = 1000.0, sidePixels = 400).bearing)
    }

    @Test
    fun movingLikeUpstream() {
        // 12 m/s north: 1000 + 150 × 8 = 2200 m, the dot half way to its maximum offset.
        val camera = MapCamera.compute(0.0, 0.0, 12.0, 0.0, northUp = false, distance = 1000.0, sidePixels = 400)
        assertEquals(2 * tan(PI / 12) * 2200, groundWidth(camera, 400), 1e-6)
        assertEquals(0.25, camera.dotOffsetRatio, 1e-9)
        val offsetMeters = tan(PI / 12) * 2200 / 4
        assertEquals(360 * offsetMeters / 40_075_000, camera.latitude, 1e-12)
        assertEquals(0.0, camera.longitude, 1e-12)
        // Capped at 20 m/s; east moves the centre east.
        val fast = MapCamera.compute(0.0, 0.0, 40.0, 90.0, northUp = false, distance = 1000.0, sidePixels = 400)
        assertEquals(0.5, fast.dotOffsetRatio, 1e-9)
        assertEquals(0.0, fast.latitude, 1e-9)
        // North up zooms out but keeps the dot centred.
        val northUp = MapCamera.compute(0.0, 0.0, 12.0, 0.0, northUp = true, distance = 1000.0, sidePixels = 400)
        assertEquals(0.0, northUp.dotOffsetRatio)
        assertEquals(0.0, northUp.latitude)
    }
}
