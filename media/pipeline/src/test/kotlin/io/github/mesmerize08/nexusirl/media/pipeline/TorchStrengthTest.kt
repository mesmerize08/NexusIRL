package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorchStrengthTest {
    @Test
    fun fullLevelIsTheMaximumStrength() {
        assertEquals(10, TorchStrength.level(1f, 10))
        assertEquals(1, TorchStrength.level(1f, 1))
    }

    @Test
    fun levelsScaleIntoOneToMaximum() {
        assertEquals(5, TorchStrength.level(0.5f, 10))
        assertEquals(3, TorchStrength.level(0.25f, 10))
        // Upstream's lowest level, 0.01, still lights the torch.
        assertEquals(1, TorchStrength.level(0.01f, 10))
        assertEquals(3, TorchStrength.level(0.01f, 255))
        assertEquals(128, TorchStrength.level(0.5f, 255))
    }

    @Test
    fun outOfRangeLevelsAreClampedLikeUpstream() {
        // Upstream clamps to 0.01...1 before setTorchModeOn(level:).
        assertEquals(1, TorchStrength.level(0f, 10))
        assertEquals(1, TorchStrength.level(-3f, 10))
        assertEquals(10, TorchStrength.level(7f, 10))
        assertEquals(1, TorchStrength.level(Float.NaN, 10))
    }

    @Test
    fun strengthIsAdjustableOnlyWithMoreThanOneLevel() {
        assertEquals(false, TorchStrength.adjustable(null))
        assertEquals(false, TorchStrength.adjustable(1))
        assertEquals(true, TorchStrength.adjustable(2))
    }

    @Test
    fun fullBrightnessLeavesThePhonesDefaultLevel() {
        assertFalse(TorchStrength.overridesDefault(1f))
        assertFalse(TorchStrength.overridesDefault(5f))
        assertTrue(TorchStrength.overridesDefault(0.5f))
    }

    @Test
    fun importedLevelsAreMadeValid() {
        assertEquals(1f, TorchStrength.clamp(Float.NaN))
        assertEquals(1f, TorchStrength.clamp(5f))
        assertEquals(TorchStrength.MINIMUM_LEVEL, TorchStrength.clamp(0f))
        assertEquals(0.4f, TorchStrength.clamp(0.4f))
    }
}
