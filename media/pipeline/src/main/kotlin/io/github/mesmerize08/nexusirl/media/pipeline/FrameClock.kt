// Port of the frame timer's presentation time stamps (makePresentationTimeStamp and handleFrameTimer
// in Moblin/Media/HaishinKit/Media/Video/VideoUnit.swift) @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs

/**
 * Output times on an exact grid of `start + counter × interval`, so every frame sent to the
 * encoders is exactly one frame time after the previous one, whatever the camera's own timing.
 * When the grid is more than [driftLimitNanos] from the clock, it moves by one frame (upstream's
 * one-frame nudge), so it follows the clock without jumps.
 */
class FrameClock(private val intervalNanos: Long, private val driftLimitNanos: Long = DRIFT_LIMIT_NANOS) {
    private var start: Long? = null
    private var counter = 0L

    /** The time for the next output frame, given the current [clockNanos]. */
    fun next(clockNanos: Long): Long {
        val start = start ?: run {
            start = clockNanos
            counter = 0
            return clockNanos
        }
        counter += 1
        var time = start + counter * intervalNanos
        val delta = time - clockNanos
        if (abs(delta) > driftLimitNanos) {
            counter += if (delta > 0) -1 else 1
            time = start + counter * intervalNanos
        }
        return time
    }

    companion object {
        /** Upstream `deltaLimit`: 30 ms. */
        const val DRIFT_LIMIT_NANOS = 30_000_000L
    }
}
