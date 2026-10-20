// Port of Moblin/VideoEffects/Text/TextFormatStringLoader.swift @97e26d2.
package io.github.mesmerize08.nexusirl.effects

enum class SpeedUnit(val symbol: String) {
    SYSTEM(""),
    METERS_PER_SECOND("m/s"),
    KILOMETERS_PER_HOUR("km/h"),
    MILES_PER_HOUR("mph"),
    ;

    companion object {
        fun parse(value: String): SpeedUnit? = entries.firstOrNull { it != SYSTEM && it.symbol == value }
    }
}

enum class TemperatureUnit(val symbol: String) {
    SYSTEM(""),
    KELVIN("k"),
    CELSIUS("c"),
    FAHRENHEIT("f"),
    ;

    companion object {
        fun parse(value: String): TemperatureUnit? = entries.firstOrNull { it != SYSTEM && it.symbol == value }
    }
}

enum class LengthUnit(val symbol: String) {
    SYSTEM(""),
    METERS("m"),
    KILOMETERS("km"),
    FEET("ft"),
    YARDS("yd"),
    MILES("mi"),
    NAUTICAL_MILES("nmi"),
    LIGHT_YEARS("ly"),
    ;

    companion object {
        fun parse(value: String): LengthUnit? = entries.firstOrNull { it != SYSTEM && it.symbol == value }
    }
}

/** One piece of a text widget's format string (upstream `TextFormatPart`). */
sealed interface TextFormatPart {
    data class Text(val text: String) : TextFormatPart

    data object NewLine : TextFormatPart

    data object Clock : TextFormatPart

    data object ShortClock : TextFormatPart

    data object Date : TextFormatPart

    data object FullDate : TextFormatPart

    data object Bitrate : TextFormatPart

    data object BitrateAndTotal : TextFormatPart

    data object Bonding : TextFormatPart

    data object Resolution : TextFormatPart

    data object Fps : TextFormatPart

    data object DebugOverlay : TextFormatPart

    data class Speed(val unit: SpeedUnit) : TextFormatPart

    data class AverageSpeed(val unit: SpeedUnit) : TextFormatPart

    data class Altitude(val unit: LengthUnit) : TextFormatPart

    data class Distance(val unit: LengthUnit) : TextFormatPart

    data class SplitDistance(val unit: LengthUnit) : TextFormatPart

    data class AltitudeAscent(val unit: LengthUnit) : TextFormatPart

    data class AltitudeDescent(val unit: LengthUnit) : TextFormatPart

    data class SplitAltitudeAscent(val unit: LengthUnit) : TextFormatPart

    data class SplitAltitudeDescent(val unit: LengthUnit) : TextFormatPart

    data object Slope : TextFormatPart

    data object Timer : TextFormatPart

    data object Stopwatch : TextFormatPart

    data object Conditions : TextFormatPart

    data class Temperature(val unit: TemperatureUnit) : TextFormatPart

    data class FeelsLikeTemperature(val unit: TemperatureUnit) : TextFormatPart

    data class Wind(val unit: SpeedUnit) : TextFormatPart

    data object Country : TextFormatPart

    data object CountryFlag : TextFormatPart

    data object State : TextFormatPart

    data object Area : TextFormatPart

    data object City : TextFormatPart

    data object Neighborhood : TextFormatPart

    data object Checkbox : TextFormatPart

    data object Rating : TextFormatPart

    data class Subtitles(val language: String?) : TextFormatPart

    data object Muted : TextFormatPart

    data class HeartRate(val device: String) : TextFormatPart

    data object ActiveEnergyBurned : TextFormatPart

    data object Power : TextFormatPart

    data object StepCount : TextFormatPart

    data object WorkoutDistance : TextFormatPart

    data object TeslaBatteryLevel : TextFormatPart

    data object TeslaDrive : TextFormatPart

    data object TeslaMedia : TextFormatPart

    data object CyclingPower : TextFormatPart

    data object CyclingCadence : TextFormatPart

    data class CyclingSpeed(val unit: SpeedUnit) : TextFormatPart

    data class RunningPace(val device: String) : TextFormatPart

    data class RunningCadence(val device: String) : TextFormatPart

    data class RunningDistance(val device: String) : TextFormatPart

    data object LapTimes : TextFormatPart

    data object BrowserTitle : TextFormatPart

    data object GForce : TextFormatPart

    data object GForceRecentMax : TextFormatPart

    data object GForceMax : TextFormatPart

    data object LatestSubscriber : TextFormatPart

    data object LatestFollower : TextFormatPart

    data object SystemMonitor : TextFormatPart
}

/**
 * Parses a text widget format string such as `"{time}\n{speed:km/h}"` into parts (upstream
 * `TextFormatLoader`). Placeholders are case-insensitive; unknown ones stay as literal text.
 * A literal `\n` in the string is a line break.
 */
object TextFormat {
    private val simple: List<Pair<String, TextFormatPart>> = listOf(
        "{time}" to TextFormatPart.Clock,
        "{shorttime}" to TextFormatPart.ShortClock,
        "{date}" to TextFormatPart.Date,
        "{fulldate}" to TextFormatPart.FullDate,
        "{bitrate}" to TextFormatPart.Bitrate,
        "{bitrateandtotal}" to TextFormatPart.BitrateAndTotal,
        "{bonding}" to TextFormatPart.Bonding,
        "{resolution}" to TextFormatPart.Resolution,
        "{fps}" to TextFormatPart.Fps,
        "{debugoverlay}" to TextFormatPart.DebugOverlay,
    )

    // Order matters where one name is a prefix of another, exactly as upstream checks them.
    private val afterUnits: List<Pair<String, TextFormatPart>> = listOf(
        "{slope}" to TextFormatPart.Slope,
        "{timer}" to TextFormatPart.Timer,
        "{stopwatch}" to TextFormatPart.Stopwatch,
        "{conditions}" to TextFormatPart.Conditions,
    )

    private val geography: List<Pair<String, TextFormatPart>> = listOf(
        "{country}" to TextFormatPart.Country,
        "{countryflag}" to TextFormatPart.CountryFlag,
        "{state}" to TextFormatPart.State,
        "{area}" to TextFormatPart.Area,
        "{city}" to TextFormatPart.City,
        "{neighborhood}" to TextFormatPart.Neighborhood,
        "{checkbox}" to TextFormatPart.Checkbox,
        "{rating}" to TextFormatPart.Rating,
        "{muted}" to TextFormatPart.Muted,
    )

    private val tail: List<Pair<String, TextFormatPart>> = listOf(
        "{activeenergyburned}" to TextFormatPart.ActiveEnergyBurned,
        "{power}" to TextFormatPart.Power,
        "{stepcount}" to TextFormatPart.StepCount,
        "{workoutdistance}" to TextFormatPart.WorkoutDistance,
        "{teslabatterylevel}" to TextFormatPart.TeslaBatteryLevel,
        "{tesladrive}" to TextFormatPart.TeslaDrive,
        "{teslamedia}" to TextFormatPart.TeslaMedia,
        "{cyclingpower}" to TextFormatPart.CyclingPower,
        "{cyclingcadence}" to TextFormatPart.CyclingCadence,
    )

    private val last: List<Pair<String, TextFormatPart>> = listOf(
        "{laptimes}" to TextFormatPart.LapTimes,
        "{browsertitle}" to TextFormatPart.BrowserTitle,
        "{gforce}" to TextFormatPart.GForce,
        "{gforcerecentmax}" to TextFormatPart.GForceRecentMax,
        "{gforcemax}" to TextFormatPart.GForceMax,
        "{latestsubscriber}" to TextFormatPart.LatestSubscriber,
        "{latestfollower}" to TextFormatPart.LatestFollower,
        "{systemmonitor}" to TextFormatPart.SystemMonitor,
    )

    /** A placeholder with an optional `:option` (upstream `appendOptionsIfPresent`). */
    private class Optioned<O>(val name: String, val parse: (String) -> O?, val make: (O?) -> TextFormatPart)

    private fun <O> optioned(name: String, parse: (String) -> O?, make: (O?) -> TextFormatPart) = Optioned(name, parse, make)

    private val unitParts: List<Optioned<*>> = listOf(
        optioned("speed", SpeedUnit::parse) { TextFormatPart.Speed(it ?: SpeedUnit.SYSTEM) },
        optioned("averagespeed", SpeedUnit::parse) { TextFormatPart.AverageSpeed(it ?: SpeedUnit.SYSTEM) },
        optioned("altitudeascent", LengthUnit::parse) { TextFormatPart.AltitudeAscent(it ?: LengthUnit.SYSTEM) },
        optioned("altitudedescent", LengthUnit::parse) { TextFormatPart.AltitudeDescent(it ?: LengthUnit.SYSTEM) },
        optioned("splitaltitudeascent", LengthUnit::parse) { TextFormatPart.SplitAltitudeAscent(it ?: LengthUnit.SYSTEM) },
        optioned("splitaltitudedescent", LengthUnit::parse) { TextFormatPart.SplitAltitudeDescent(it ?: LengthUnit.SYSTEM) },
        optioned("altitude", LengthUnit::parse) { TextFormatPart.Altitude(it ?: LengthUnit.SYSTEM) },
        optioned("runningdistance", { it }) { TextFormatPart.RunningDistance(it ?: "") },
        optioned("splitdistance", LengthUnit::parse) { TextFormatPart.SplitDistance(it ?: LengthUnit.SYSTEM) },
        optioned("distance", LengthUnit::parse) { TextFormatPart.Distance(it ?: LengthUnit.SYSTEM) },
    )

    private val weatherParts: List<Optioned<*>> = listOf(
        optioned("temperature", TemperatureUnit::parse) { TextFormatPart.Temperature(it ?: TemperatureUnit.SYSTEM) },
        optioned("feelsliketemperature", TemperatureUnit::parse) { TextFormatPart.FeelsLikeTemperature(it ?: TemperatureUnit.SYSTEM) },
        optioned("wind", SpeedUnit::parse) { TextFormatPart.Wind(it ?: SpeedUnit.SYSTEM) },
    )

    private val deviceParts: List<Optioned<*>> = listOf(
        optioned("heartrate", { it }) { TextFormatPart.HeartRate(it ?: "") },
        optioned("subtitles", { it }) { TextFormatPart.Subtitles(it) },
        optioned("runningpace", { it }) { TextFormatPart.RunningPace(it ?: "") },
        optioned("runningcadence", { it }) { TextFormatPart.RunningCadence(it ?: "") },
    )

    private val cyclingSpeed = optioned("cyclingspeed", SpeedUnit::parse) { TextFormatPart.CyclingSpeed(it ?: SpeedUnit.SYSTEM) }

    fun parse(inputFormat: String): List<TextFormatPart> {
        val format = inputFormat.replace("\\n", "\n")
        val parts = mutableListOf<TextFormatPart>()
        var index = 0
        var textStart = 0

        fun appendText() {
            if (textStart < index) {
                parts += TextFormatPart.Text(format.substring(textStart, index))
            }
        }

        fun load(part: TextFormatPart, length: Int) {
            appendText()
            parts += part
            index += length
            textStart = index
        }

        fun tryList(lower: String, list: List<Pair<String, TextFormatPart>>): Boolean {
            val match = list.firstOrNull { lower.startsWith(it.first) } ?: return false
            load(match.second, match.first.length)
            return true
        }

        fun tryOptioned(lower: String, list: List<Optioned<*>>): Boolean {
            for (item in list) {
                val plain = "{${item.name}}"
                if (lower.startsWith(plain)) {
                    load(item.make(null), plain.length)
                    return true
                }
                val match = Regex("""^\{${item.name}:([^}]+)\}""").find(lower) ?: continue
                val options = item.parse(match.groupValues[1]) ?: continue
                @Suppress("UNCHECKED_CAST")
                load((item.make as (Any?) -> TextFormatPart)(options), match.value.length)
                return true
            }
            return false
        }

        while (index < format.length) {
            when (format[index]) {
                '{' -> {
                    val lower = format.substring(index).lowercase()
                    val handled = tryList(lower, simple) ||
                        tryOptioned(lower, unitParts) ||
                        tryList(lower, afterUnits) ||
                        tryOptioned(lower, weatherParts) ||
                        tryList(lower, geography) ||
                        tryOptioned(lower, deviceParts) ||
                        tryList(lower, tail) ||
                        tryOptioned(lower, listOf(cyclingSpeed)) ||
                        tryList(lower, last)
                    if (!handled) {
                        index += 1
                    }
                }
                '\n' -> load(TextFormatPart.NewLine, 1)
                else -> index += 1
            }
        }
        appendText()
        return parts
    }
}
