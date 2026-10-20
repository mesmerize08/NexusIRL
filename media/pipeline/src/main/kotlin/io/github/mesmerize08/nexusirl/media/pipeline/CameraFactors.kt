// Port of the slider mappings in Moblin/Various/Utils/CameraUtils.swift @97e26d2: every manual
// camera control is a 0..1 "factor" in the UI, mapped onto the device's range.
package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object CameraFactors {
    /** Upstream shutter speeds (1/x s), fastest first. */
    private val SHUTTER_SPEEDS = listOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 240,
        200, 160, 125, 120, 100, 80, 60, 50, 48, 40, 30, 25, 24, 20, 15, 13, 10, 8,
    )

    /** Upstream `slowestExposureWithoutFrameDuration` (1/20 s). */
    private const val SLOWEST_WITHOUT_FRAME_DURATION_NS = 50_000_000L

    /** Upstream `minimumWhiteBalanceTemperature` / `maximumWhiteBalanceTemperature`. */
    const val MIN_WHITE_BALANCE_KELVIN = 2200f
    const val MAX_WHITE_BALANCE_KELVIN = 10000f

    fun isoFromFactor(minIso: Int, maxIso: Int, factor: Float): Int = (minIso + (maxIso - minIso) * factor.coerceIn(0f, 1f)).roundToInt()

    fun factorFromIso(minIso: Int, maxIso: Int, iso: Int): Float =
        if (maxIso <= minIso) 0f else ((iso - minIso).toFloat() / (maxIso - minIso)).coerceIn(0f, 1f)

    /**
     * Upstream `exposures(device:)`: the standard shutter speeds the camera supports, no slower
     * than one frame (so the frame rate is kept), fastest first. Durations in nanoseconds.
     */
    fun exposures(fastestNs: Long, slowestNs: Long, frameDurationNs: Long?): List<Long> {
        var slowest = if (frameDurationNs != null && frameDurationNs > 0) {
            minOf(slowestNs, frameDurationNs)
        } else {
            minOf(slowestNs, SLOWEST_WITHOUT_FRAME_DURATION_NS)
        }
        slowest = maxOf(fastestNs, slowest)
        val exposures = SHUTTER_SPEEDS.map { 1_000_000_000L / it }.filter { it in fastestNs..slowest }
        return exposures.ifEmpty { listOf(slowest) }
    }

    fun exposureFromFactor(exposures: List<Long>, factor: Float): Long {
        if (exposures.size <= 1) {
            return exposures.firstOrNull() ?: (1_000_000_000L / 60)
        }
        return exposures[(factor.coerceIn(0f, 1f) * (exposures.size - 1)).roundToInt()]
    }

    fun factorFromExposure(exposures: List<Long>, exposureNs: Long): Float {
        if (exposures.size <= 1 || exposureNs <= 0) {
            return 0f
        }
        val best = exposures.indices.minBy { abs(ln(exposures[it].toDouble() / exposureNs)) }
        return best.toFloat() / (exposures.size - 1)
    }

    /** Upstream `formatExposure`: "1/250". */
    fun formatExposure(exposureNs: Long): String = if (exposureNs <= 0) "" else "1/${(1_000_000_000.0 / exposureNs).roundToInt()}"

    fun kelvinFromFactor(factor: Float): Float =
        MIN_WHITE_BALANCE_KELVIN + (MAX_WHITE_BALANCE_KELVIN - MIN_WHITE_BALANCE_KELVIN) * factor.coerceIn(0f, 1f)

    /**
     * Camera2 white balance gains (R, G, B) for a colour temperature, the equivalent of upstream's
     * `deviceWhiteBalanceGains(for: temperature)`. Uses the blackbody RGB approximation by Tanner
     * Helland and inverts it so a light of that temperature becomes neutral; green is 1.
     */
    fun whiteBalanceGains(kelvin: Float): Triple<Float, Float, Float> {
        val t = kelvin.coerceIn(1000f, 40000f) / 100f
        val red = if (t <= 66) 255.0 else (329.698727446 * (t - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        val green = if (t <= 66) {
            (99.4708025861 * ln(t.toDouble()) - 161.1195681661).coerceIn(1.0, 255.0)
        } else {
            (288.1221695283 * (t - 60.0).pow(-0.0755148492)).coerceIn(1.0, 255.0)
        }
        val blue = when {
            t >= 66 -> 255.0
            t <= 19 -> 1.0
            else -> (138.5177312231 * ln(t - 10.0) - 305.0447927307).coerceIn(1.0, 255.0)
        }
        return Triple((green / red).toFloat(), 1f, (green / blue).toFloat())
    }

    /**
     * AVFoundation's lens position is 0 (closest) to 1 (farthest); Camera2 takes diopters, 0 at
     * infinity up to the camera's minimum focus distance.
     */
    fun focusDistanceFromPosition(position: Float, minimumFocusDistance: Float): Float =
        (1f - position.coerceIn(0f, 1f)) * minimumFocusDistance

    fun positionFromFocusDistance(diopters: Float, minimumFocusDistance: Float): Float =
        if (minimumFocusDistance <= 0f) 1f else (1f - diopters / minimumFocusDistance).coerceIn(0f, 1f)

    /** Exposure bias in EV to a Camera2 compensation index, or null outside the range. */
    fun compensationIndex(bias: Float, step: Float, minIndex: Int, maxIndex: Int): Int? {
        if (step <= 0f) {
            return null
        }
        val index = (bias / step).roundToInt()
        return if (index in minIndex..maxIndex) index else null
    }
}
