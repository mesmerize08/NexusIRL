package io.github.mesmerize08.nexusirl.effects

import io.github.mesmerize08.nexusirl.settings.SettingsSceneWidget
import io.github.mesmerize08.nexusirl.settings.WidgetAlignment
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class EffectsTest {
    @Test
    fun parsesPlaceholdersLikeUpstream() {
        assertEquals(
            listOf(
                TextFormatPart.Text("Now "),
                TextFormatPart.Clock,
                TextFormatPart.NewLine,
                TextFormatPart.Speed(SpeedUnit.KILOMETERS_PER_HOUR),
                TextFormatPart.Text(" "),
                TextFormatPart.Altitude(LengthUnit.SYSTEM),
                TextFormatPart.Text(" {nope} "),
                TextFormatPart.Bitrate,
            ),
            TextFormat.parse("Now {TIME}\\n{speed:km/h} {altitude} {nope} {bitrate}"),
        )
        // Prefix-sensitive names resolve as upstream: ascent before altitude, countryflag after country.
        assertEquals(listOf(TextFormatPart.AltitudeAscent(LengthUnit.FEET)), TextFormat.parse("{altitudeascent:ft}"))
        assertEquals(listOf(TextFormatPart.CountryFlag), TextFormat.parse("{countryflag}"))
        assertEquals(listOf(TextFormatPart.HeartRate("my band")), TextFormat.parse("{heartRate:My Band}"))
        // An unknown unit is not a placeholder.
        assertEquals(listOf(TextFormatPart.Text("{speed:furlongs}")), TextFormat.parse("{speed:furlongs}"))
        assertEquals(listOf(TextFormatPart.Text("{")), TextFormat.parse("{"))
    }

    @Test
    fun formatsLinesAndValues() {
        val date = Date(Date.UTC(126, 9, 20, 13, 5, 9))
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val lines = TextFormatter.format(
                TextFormat.parse("{shortTime}\n{bitrate} {fps} {timer} {stopwatch}\n{rating}{weather}{conditions}"),
                TextVariables(
                    date = date,
                    nowNanos = 10_000_000_000,
                    bitrateMbps = "4.2",
                    fps = 30,
                    timerEndNanos = listOf(75_000_000_000),
                    stopwatches = listOf(StopwatchState(totalElapsedSeconds = 3600.0, running = true, playPressedAtNanos = 4_000_000_000)),
                    ratings = listOf(4),
                ),
                Locale.UK,
            )
            assertEquals(listOf(TextSegment.Text("13:05")), lines[0])
            assertEquals(listOf(TextSegment.Text("4.2 Mbps 30 1m 5s 1h 6s")), lines[1].mergeText())
            assertEquals(listOf(TextSegment.Rating(4), TextSegment.Text("{weather}-")), lines[2].mergeText())
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun durations() {
        assertEquals("0s", TextFormatter.formatDuration(0.0))
        assertEquals("1m", TextFormatter.formatDuration(60.0))
        assertEquals("1d 1h 1s", TextFormatter.formatDuration(90_001.0))
    }

    @Test
    fun layoutMatchesUpstream() {
        val canvasW = 1920.0
        val canvasH = 1080.0
        // A 30% image in the top left, 2% in from the corner: scaled to fit a 30% box.
        val image = SettingsSceneWidget(x = 2.0, y = 2.0, size = 30.0)
        val placed = WidgetLayout.place(image, 1000.0, 1000.0, canvasW, canvasH, resize = true)
        assertEquals(WidgetRect(38.4, 21.6, 324.0, 324.0), placed)
        // Bottom-right offsets are measured from that corner.
        val bottomRight = WidgetLayout.place(image.copy(alignment = WidgetAlignment.BOTTOM_RIGHT), 100.0, 50.0, canvasW, canvasH, false)
        assertEquals(WidgetRect(canvasW - 38.4 - 100, canvasH - 21.6 - 50, 100.0, 50.0), bottomRight)
        // Centred axes ignore the offset.
        val centred = WidgetLayout.place(image.copy(alignment = WidgetAlignment.CENTER), 100.0, 50.0, canvasW, canvasH, false)
        assertEquals(WidgetRect(910.0, 515.0, 100.0, 50.0), centred)
        assertEquals(30f, WidgetLayout.scaledFontSize(30, 1920, 1080))
        assertEquals(20f, WidgetLayout.scaledFontSize(30, 1280, 720))
    }

    private fun List<TextSegment>.mergeText(): List<TextSegment> {
        val merged = mutableListOf<TextSegment>()
        for (segment in this) {
            val last = merged.lastOrNull()
            if (segment is TextSegment.Text && last is TextSegment.Text) {
                merged[merged.size - 1] = TextSegment.Text(last.text + segment.text)
            } else {
                merged += segment
            }
        }
        return merged
    }
}
