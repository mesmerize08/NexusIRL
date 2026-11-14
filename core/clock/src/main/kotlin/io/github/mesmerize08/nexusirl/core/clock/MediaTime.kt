package io.github.mesmerize08.nexusirl.core.clock

/**
 * A rational media timestamp, the Kotlin counterpart of Core Media's `CMTime` that upstream
 * Moblin uses throughout its pipeline: `value / timescale` seconds.
 *
 * Arithmetic between different timescales converts to the finer of the two, so precision is
 * never lost when mixing e.g. 90 kHz MPEG-TS and nanosecond host timestamps.
 */
data class MediaTime(val value: Long, val timescale: Int) : Comparable<MediaTime> {
    init {
        require(timescale > 0) { "timescale must be positive, was $timescale" }
    }

    val seconds: Double
        get() = value.toDouble() / timescale

    /** Converts to [newTimescale], rounding to the nearest tick. */
    fun convertScale(newTimescale: Int): MediaTime {
        if (newTimescale == timescale) {
            return this
        }
        return MediaTime(Math.round(value.toDouble() * newTimescale / timescale), newTimescale)
    }

    operator fun plus(other: MediaTime): MediaTime {
        val scale = maxOf(timescale, other.timescale)
        return MediaTime(convertScale(scale).value + other.convertScale(scale).value, scale)
    }

    operator fun minus(other: MediaTime): MediaTime {
        val scale = maxOf(timescale, other.timescale)
        return MediaTime(convertScale(scale).value - other.convertScale(scale).value, scale)
    }

    operator fun unaryMinus(): MediaTime = MediaTime(-value, timescale)

    override fun compareTo(other: MediaTime): Int {
        if (timescale == other.timescale) {
            return value.compareTo(other.value)
        }
        // Cross-multiply exactly: value * otherTimescale vs otherValue * timescale.
        val left = multiplyHigh(value, other.timescale.toLong()) to value * other.timescale
        val right = multiplyHigh(other.value, timescale.toLong()) to other.value * timescale
        val high = left.first.compareTo(right.first)
        return if (high != 0) high else java.lang.Long.compareUnsigned(left.second, right.second)
    }

    /** Equality is by value, so `1/1 == 1000/1000`, matching `CMTime` comparison semantics. */
    override fun equals(other: Any?): Boolean = other is MediaTime && compareTo(other) == 0

    override fun hashCode(): Int = convertScale(NANOSECONDS).value.hashCode()

    override fun toString(): String = "MediaTime($value/$timescale = ${seconds}s)"

    companion object {
        const val NANOSECONDS: Int = 1_000_000_000
        const val MPEG_TS: Int = 90_000

        val ZERO = MediaTime(0, 1)

        /** Equivalent of upstream's `CMTime(seconds:)` helper (nanosecond timescale). */
        fun fromSeconds(seconds: Double, timescale: Int = NANOSECONDS): MediaTime = MediaTime(Math.round(seconds * timescale), timescale)
    }
}

/**
 * The high 64 bits of the 128-bit product `x * y` (signed), like `Math.multiplyHigh`, which Android
 * only has from API 31 (minSdk is 29).
 */
internal fun multiplyHigh(x: Long, y: Long): Long {
    val x1 = x shr 32
    val x2 = x and 0xFFFF_FFFFL
    val y1 = y shr 32
    val y2 = y and 0xFFFF_FFFFL
    val z2 = x2 * y2
    val t = x1 * y2 + (z2 ushr 32)
    var z1 = t and 0xFFFF_FFFFL
    val z0 = t shr 32
    z1 += x2 * y1
    return x1 * y1 + z0 + (z1 shr 32)
}
