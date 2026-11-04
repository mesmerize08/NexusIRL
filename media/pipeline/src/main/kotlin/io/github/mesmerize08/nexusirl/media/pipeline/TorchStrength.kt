package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.roundToInt

/**
 * Upstream's torch brightness (`Database.torchLevel`, 0.01 to 1, passed to
 * `AVCaptureDevice.setTorchModeOn(level:)`) on Camera2's torch strength levels, 1 to
 * `FLASH_TORCH_STRENGTH_MAX_LEVEL`.
 */
object TorchStrength {
    /** Upstream clamps to this before setting the torch. */
    const val MINIMUM_LEVEL = 0.01f

    fun level(level: Float, maxLevel: Int): Int {
        val clamped = if (level.isNaN()) MINIMUM_LEVEL else level.coerceIn(MINIMUM_LEVEL, 1f)
        return (clamped * maxLevel).roundToInt().coerceIn(1, maxLevel)
    }

    /** Cameras reporting one level (or none) only have full strength. */
    fun adjustable(maxLevel: Int?): Boolean = maxLevel != null && maxLevel > 1
}
