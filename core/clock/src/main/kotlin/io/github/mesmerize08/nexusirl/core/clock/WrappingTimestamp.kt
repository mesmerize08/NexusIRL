// Port of Moblin/Media/WrappingTimestamp.swift @97e26d2
package io.github.mesmerize08.nexusirl.core.clock

/**
 * Unwraps a timestamp that wraps around at [maximumTimestamp] (e.g. 33-bit MPEG-TS PTS) into a
 * continuously increasing timestamp. Small backwards jumps across the wrap point (reordered
 * packets) are mapped into the previous period.
 */
class WrappingTimestamp(private val name: String, private val maximumTimestamp: MediaTime, private val onWrap: (String) -> Unit = {}) {
    private val halfMaximumTimestamp = MediaTime(maximumTimestamp.value / 2, maximumTimestamp.timescale)
    private var highTimestamp = MediaTime(0, maximumTimestamp.timescale)
    private var previousTimestamp = MediaTime(0, maximumTimestamp.timescale)

    fun update(timestamp: MediaTime): MediaTime {
        if (timestamp >= previousTimestamp) {
            return if (timestamp - previousTimestamp < halfMaximumTimestamp) {
                previousTimestamp = timestamp
                highTimestamp + timestamp
            } else {
                highTimestamp - maximumTimestamp + timestamp
            }
        }
        val wrapped = previousTimestamp - timestamp > halfMaximumTimestamp
        previousTimestamp = timestamp
        if (wrapped) {
            onWrap("Wrapping timestamp $name just wrapped around.")
            highTimestamp = highTimestamp + maximumTimestamp
        }
        return highTimestamp + timestamp
    }
}
