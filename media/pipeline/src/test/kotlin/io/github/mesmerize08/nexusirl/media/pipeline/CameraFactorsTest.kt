package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraFactorsTest {
    @Test
    fun isoFactorRoundTrips() {
        assertEquals(100, CameraFactors.isoFromFactor(100, 3200, 0f))
        assertEquals(3200, CameraFactors.isoFromFactor(100, 3200, 1f))
        assertEquals(1650, CameraFactors.isoFromFactor(100, 3200, 0.5f))
        assertEquals(0.5f, CameraFactors.factorFromIso(100, 3200, 1650), 0.001f)
        assertEquals(0f, CameraFactors.factorFromIso(100, 100, 100))
    }

    @Test
    fun exposuresAreStandardShutterSpeedsWithinOneFrame() {
        // A camera from 1/10000 s to 1/2 s at 30 fps: nothing slower than 1/30 s.
        val exposures = CameraFactors.exposures(100_000, 500_000_000, 33_333_333)
        assertEquals(1_000_000_000L / 8000, exposures.first())
        assertEquals(1_000_000_000L / 30, exposures.last())
        assertTrue(exposures.zipWithNext().all { (a, b) -> a < b })
        // Without a frame duration, upstream stops at 1/20 s.
        assertEquals(1_000_000_000L / 20, CameraFactors.exposures(100_000, 500_000_000, null).last())
        // A camera that cannot reach any standard speed keeps its slowest.
        assertEquals(listOf(40_000_000L), CameraFactors.exposures(40_000_000, 40_000_000, 33_333_333))
    }

    @Test
    fun exposureFactorRoundTrips() {
        val exposures = CameraFactors.exposures(100_000, 500_000_000, 33_333_333)
        for (index in exposures.indices) {
            val factor = index.toFloat() / (exposures.size - 1)
            assertEquals(exposures[index], CameraFactors.exposureFromFactor(exposures, factor))
            assertEquals(factor, CameraFactors.factorFromExposure(exposures, exposures[index]), 0.0001f)
        }
        assertEquals("1/250", CameraFactors.formatExposure(4_000_000))
    }

    @Test
    fun whiteBalanceGainsFollowTemperature() {
        // Warm light (tungsten) needs more blue gain; cool light (shade) more red gain.
        val (warmR, _, warmB) = CameraFactors.whiteBalanceGains(2700f)
        val (coolR, _, coolB) = CameraFactors.whiteBalanceGains(9000f)
        assertTrue(warmB > warmR, "tungsten boosts blue")
        assertTrue(coolR > coolB, "shade boosts red")
        val (dayR, dayG, dayB) = CameraFactors.whiteBalanceGains(6600f)
        assertEquals(1f, dayG)
        assertEquals(1f, dayR, 0.05f)
        assertEquals(1f, dayB, 0.05f)
        assertEquals(2200f, CameraFactors.kelvinFromFactor(0f))
        assertEquals(10000f, CameraFactors.kelvinFromFactor(1f))
    }

    @Test
    fun focusAndExposureBias() {
        assertEquals(10f, CameraFactors.focusDistanceFromPosition(0f, 10f))
        assertEquals(0f, CameraFactors.focusDistanceFromPosition(1f, 10f))
        assertEquals(0.25f, CameraFactors.positionFromFocusDistance(7.5f, 10f), 0.0001f)
        assertEquals(3, CameraFactors.compensationIndex(1f, 1f / 3, -6, 6))
        assertEquals(-6, CameraFactors.compensationIndex(-2f, 1f / 3, -6, 6))
        assertNull(CameraFactors.compensationIndex(3f, 1f / 3, -6, 6))
    }
}
