package io.github.mesmerize08.nexusirl.effects

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationFormatTest {
    private val ireland = Locale.Builder().setLanguage("en").setRegion("IE").build()

    @Test
    fun speed() {
        assertEquals("36 km/h", LocationFormat.speed(10.0, SpeedUnit.SYSTEM, ireland))
        assertEquals("22 mph", LocationFormat.speed(10.0, SpeedUnit.SYSTEM, Locale.US))
        assertEquals("22 mph", LocationFormat.speed(10.0, SpeedUnit.SYSTEM, Locale.UK))
        assertEquals("10 m/s", LocationFormat.speed(10.0, SpeedUnit.METERS_PER_SECOND, Locale.US))
        assertEquals("0 km/h", LocationFormat.speed(-1.0, SpeedUnit.KILOMETERS_PER_HOUR, Locale.US))
    }

    @Test
    fun altitudeAndDistance() {
        assertEquals("1,235 m", LocationFormat.altitude(1234.6, LengthUnit.METERS, Locale.US))
        assertEquals("12 m", LocationFormat.altitude(12.0, LengthUnit.SYSTEM, ireland))
        assertEquals("328 ft", LocationFormat.altitude(100.0, LengthUnit.SYSTEM, Locale.US))
        assertEquals("1 km", LocationFormat.altitude(1234.0, LengthUnit.KILOMETERS, Locale.US))
        assertEquals("850.4 m", LocationFormat.distance(850.44, LengthUnit.SYSTEM, ireland))
        assertEquals("12.3 km", LocationFormat.distance(12_345.0, LengthUnit.SYSTEM, ireland))
        assertEquals("328.1 ft", LocationFormat.distance(100.0, LengthUnit.SYSTEM, Locale.US))
        assertEquals("1.2 mi", LocationFormat.distance(2000.0, LengthUnit.SYSTEM, Locale.US))
        assertEquals("2 mi", LocationFormat.distance(3000.0, LengthUnit.MILES, Locale.US))
    }

    @Test
    fun inTextWidgets() {
        val lines = TextFormatter.format(
            TextFormat.parse("{speed:km/h} {altitude:m} {distance:km} {altitudeascent} {averagespeed}"),
            TextVariables(speed = 5.0, altitude = 12.0, distance = 4200.0, locale = ireland),
        )
        assertEquals("18 km/h 12 m 4 km - -", lines.single().joinToString("") { (it as TextSegment.Text).text })
    }
}
