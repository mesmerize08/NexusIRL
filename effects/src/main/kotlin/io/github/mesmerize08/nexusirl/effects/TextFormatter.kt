// Port of Moblin/VideoEffects/Text/TextEffectFormatter.swift @97e26d2 (v1 subset of variables).
package io.github.mesmerize08.nexusirl.effects

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One piece of a rendered line. */
sealed interface TextSegment {
    data class Text(val text: String) : TextSegment

    /** Five stars, [stars] of them filled (upstream draws filled stars in yellow). */
    data class Rating(val stars: Int) : TextSegment
}

/** A stopwatch's state (upstream `SettingsWidgetTextStopwatch`); times in host nanoseconds. */
data class StopwatchState(val totalElapsedSeconds: Double = 0.0, val running: Boolean = false, val playPressedAtNanos: Long = 0)

/** Values a text widget can show (upstream `Variables`). Null or empty means "not available". */
data class TextVariables(
    val date: Date = Date(),
    val nowNanos: Long = 0,
    /** Video bitrate in Mbps, already formatted (upstream `bitrate`). */
    val bitrateMbps: String = "",
    val bitrateAndTotal: String = "",
    val bonding: String = "",
    val resolution: String? = null,
    val fps: Int? = null,
    val muted: Boolean = false,
    /** Timer end times in host nanoseconds, in widget order. */
    val timerEndNanos: List<Long> = emptyList(),
    val stopwatches: List<StopwatchState> = emptyList(),
    val checkboxes: List<Boolean> = emptyList(),
    val ratings: List<Int> = emptyList(),
    /** Location values in metres and metres per second; null (shown as "-") without location. */
    val speed: Double? = null,
    val averageSpeed: Double? = null,
    val altitude: Double? = null,
    val distance: Double? = null,
    val splitDistance: Double? = null,
    val altitudeAscent: Double? = null,
    val altitudeDescent: Double? = null,
    val splitAltitudeAscent: Double? = null,
    val splitAltitudeDescent: Double? = null,
    val locale: java.util.Locale = java.util.Locale.getDefault(),
)

object TextFormatter {
    /** Placeholder for variables Android does not provide yet (weather, workouts, Tesla, ...). */
    const val UNAVAILABLE = "-"

    /** Upstream `uptimeFormatter`: abbreviated units, zero units dropped, e.g. "1h 2m 3s". */
    fun formatDuration(totalSeconds: Double): String {
        var seconds = totalSeconds.toLong().coerceAtLeast(0)
        val days = seconds / 86_400
        seconds %= 86_400
        val hours = seconds / 3600
        seconds %= 3600
        val minutes = seconds / 60
        seconds %= 60
        val parts = listOf(days to "d", hours to "h", minutes to "m", seconds to "s").filter { it.first > 0 }
        return if (parts.isEmpty()) "0s" else parts.joinToString(" ") { "${it.first}${it.second}" }
    }

    fun format(parts: List<TextFormatPart>, variables: TextVariables, locale: Locale = Locale.getDefault()): List<List<TextSegment>> {
        val lines = mutableListOf<List<TextSegment>>()
        var line = mutableListOf<TextSegment>()
        var timerIndex = 0
        var stopwatchIndex = 0
        var checkboxIndex = 0
        var ratingIndex = 0

        fun text(value: String) {
            // Values may hold line breaks (upstream splits them into lines too).
            val pieces = value.split("\n")
            pieces.forEachIndexed { index, piece ->
                if (index > 0) {
                    lines += line
                    line = mutableListOf()
                }
                if (piece.isNotEmpty()) {
                    line += TextSegment.Text(piece)
                }
            }
        }
        for (part in parts) {
            when (part) {
                is TextFormatPart.Text -> text(part.text)
                TextFormatPart.NewLine -> {
                    lines += line
                    line = mutableListOf()
                }
                TextFormatPart.Clock -> text(DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).format(variables.date))
                TextFormatPart.ShortClock -> text(DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(variables.date))
                TextFormatPart.Date -> text(DateFormat.getDateInstance(DateFormat.SHORT, locale).format(variables.date))
                TextFormatPart.FullDate -> text(DateFormat.getDateInstance(DateFormat.FULL, locale).format(variables.date))
                TextFormatPart.Bitrate -> text("${variables.bitrateMbps.ifEmpty { "-" }} Mbps")
                TextFormatPart.BitrateAndTotal -> text(variables.bitrateAndTotal)
                TextFormatPart.Bonding -> text(variables.bonding)
                TextFormatPart.Resolution -> text(variables.resolution ?: "")
                TextFormatPart.Fps -> text(variables.fps?.toString() ?: "")
                TextFormatPart.Timer -> {
                    variables.timerEndNanos.getOrNull(timerIndex)?.let { end ->
                        text(formatDuration((end - variables.nowNanos).coerceAtLeast(0) / 1e9))
                    }
                    timerIndex += 1
                }
                TextFormatPart.Stopwatch -> {
                    variables.stopwatches.getOrNull(stopwatchIndex)?.let { stopwatch ->
                        var elapsed = stopwatch.totalElapsedSeconds
                        if (stopwatch.running) {
                            elapsed += (variables.nowNanos - stopwatch.playPressedAtNanos) / 1e9
                        }
                        text(formatDuration(elapsed))
                    }
                    stopwatchIndex += 1
                }
                TextFormatPart.Checkbox -> {
                    variables.checkboxes.getOrNull(checkboxIndex)?.let { text(if (it) "☑️" else "⬜") }
                    checkboxIndex += 1
                }
                TextFormatPart.Rating -> {
                    variables.ratings.getOrNull(ratingIndex)?.let { line += TextSegment.Rating(it.coerceIn(0, 5)) }
                    ratingIndex += 1
                }
                TextFormatPart.Muted -> if (variables.muted) text("🔇")
                is TextFormatPart.Speed -> text(
                    variables.speed?.let { LocationFormat.speed(it, part.unit, variables.locale) } ?: UNAVAILABLE,
                )
                is TextFormatPart.AverageSpeed -> text(
                    variables.averageSpeed?.let { LocationFormat.speed(it, part.unit, variables.locale) } ?: UNAVAILABLE,
                )
                is TextFormatPart.Altitude -> text(
                    variables.altitude?.let { LocationFormat.altitude(it, part.unit, variables.locale) } ?: UNAVAILABLE,
                )
                is TextFormatPart.AltitudeAscent -> text(altitude(variables.altitudeAscent, part.unit, variables))
                is TextFormatPart.AltitudeDescent -> text(altitude(variables.altitudeDescent, part.unit, variables))
                is TextFormatPart.SplitAltitudeAscent -> text(altitude(variables.splitAltitudeAscent, part.unit, variables))
                is TextFormatPart.SplitAltitudeDescent -> text(altitude(variables.splitAltitudeDescent, part.unit, variables))
                is TextFormatPart.Distance -> text(
                    variables.distance?.let { LocationFormat.distance(it, part.unit, variables.locale) } ?: UNAVAILABLE,
                )
                is TextFormatPart.SplitDistance -> text(
                    variables.splitDistance?.let { LocationFormat.distance(it, part.unit, variables.locale) } ?: UNAVAILABLE,
                )
                else -> text(UNAVAILABLE)
            }
        }
        lines += line
        return lines
    }

    private fun altitude(meters: Double?, unit: LengthUnit, variables: TextVariables): String =
        meters?.let { LocationFormat.altitude(it, unit, variables.locale) } ?: UNAVAILABLE
}
