// Port of the speed, altitude and distance formatting in
// Moblin/VideoEffects/Text/TextEffectFormatter.swift and `format(distance:)` in
// Common/Various/CommonUtils.swift @97e26d2. Foundation's MeasurementFormatter is approximated:
// whole numbers (one decimal for automatic distances) with the unit's short symbol.
package io.github.mesmerize08.nexusirl.effects

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

object LocationFormat {
    /** Countries whose preferred units are imperial (Foundation's measurement system "US"). */
    private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

    /** Countries that measure speed in miles per hour. */
    private val MPH_COUNTRIES = IMPERIAL_COUNTRIES + "GB"

    private fun number(value: Double, locale: Locale, fractionDigits: Int = 0): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = fractionDigits }.format(value)

    /** Upstream `formatSpeed`: [metersPerSecond] (negative means unknown, shown as 0). */
    fun speed(metersPerSecond: Double, unit: SpeedUnit, locale: Locale = Locale.getDefault()): String {
        val speed = max(metersPerSecond, 0.0)
        val resolved = if (unit == SpeedUnit.SYSTEM) {
            if (locale.country in MPH_COUNTRIES) SpeedUnit.MILES_PER_HOUR else SpeedUnit.KILOMETERS_PER_HOUR
        } else {
            unit
        }
        val value = when (resolved) {
            SpeedUnit.KILOMETERS_PER_HOUR -> speed * 3.6
            SpeedUnit.MILES_PER_HOUR -> speed * 3600 / 1609.344
            else -> speed
        }
        return "${number(value, locale)} ${resolved.symbol}"
    }

    private fun lengthIn(meters: Double, unit: LengthUnit): Double = when (unit) {
        LengthUnit.KILOMETERS -> meters / 1000
        LengthUnit.FEET -> meters / 0.3048
        LengthUnit.YARDS -> meters / 0.9144
        LengthUnit.MILES -> meters / 1609.344
        LengthUnit.NAUTICAL_MILES -> meters / 1852
        LengthUnit.LIGHT_YEARS -> meters / 9.4607304725808e15
        else -> meters
    }

    /** Upstream `formatAltitude` (also used for ascent and descent): feet in imperial countries. */
    fun altitude(meters: Double, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
        val resolved = if (unit == LengthUnit.SYSTEM) {
            if (locale.country in IMPERIAL_COUNTRIES) LengthUnit.FEET else LengthUnit.METERS
        } else {
            unit
        }
        return "${number(lengthIn(meters, resolved), locale)} ${resolved.symbol}"
    }

    /**
     * Upstream `formatDistance`: a given unit shows whole numbers; the automatic unit picks metres
     * or kilometres (feet or miles in imperial countries) with one decimal, like LengthFormatter.
     */
    fun distance(meters: Double, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
        if (unit != LengthUnit.SYSTEM) {
            return "${number(lengthIn(meters, unit), locale)} ${unit.symbol}"
        }
        val resolved = if (locale.country in IMPERIAL_COUNTRIES) {
            if (meters < 0.1 * 1609.344) LengthUnit.FEET else LengthUnit.MILES
        } else {
            if (meters < 1000) LengthUnit.METERS else LengthUnit.KILOMETERS
        }
        return "${number(lengthIn(meters, resolved), locale, fractionDigits = 1)} ${resolved.symbol}"
    }
}
