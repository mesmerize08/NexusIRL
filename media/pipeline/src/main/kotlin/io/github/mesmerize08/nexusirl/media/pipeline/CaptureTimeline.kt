package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs

/**
 * Presentation times for microphone reads. Times come from the sample count, like upstream, so
 * frames are evenly spaced; Android's per-read timestamps jitter by several milliseconds. When the
 * count drifts from the clock by more than [RESYNC_NANOS] on two reads in a row (e.g. after a
 * device change), it re-anchors to the clock and sets [resync]; a single wild timestamp is ignored.
 *
 * Capture thread only.
 */
class CaptureTimeline(private val sampleRate: Int) {
    private var framesRead = 0L
    private var anchorNanos: Long? = null
    private var anchorFrames = 0L

    /** How far the previous read's clock was from the count, when beyond [RESYNC_NANOS]. */
    private var pendingOffsetNanos: Long? = null

    /** Whether the latest [next] re-anchored, so its time does not continue the previous read's. */
    var resync = false
        private set

    /** The time of a read of [frames] frames whose first frame the clock puts at [measuredNanos]. */
    fun next(measuredNanos: Long, frames: Int): Long {
        resync = false
        val anchor = anchorNanos
        val counted = anchor?.let { it + (framesRead - anchorFrames) * 1_000_000_000L / sampleRate }
        val offset = counted?.let { measuredNanos - it }
        val pending = pendingOffsetNanos
        val pts = when {
            counted == null || offset == null -> anchor(measuredNanos)
            abs(offset) <= RESYNC_NANOS -> {
                pendingOffsetNanos = null
                counted
            }
            pending != null && abs(offset - pending) <= RESYNC_NANOS -> {
                resync = true
                anchor(measuredNanos)
            }
            else -> {
                pendingOffsetNanos = offset
                counted
            }
        }
        framesRead += frames
        return pts
    }

    private fun anchor(measuredNanos: Long): Long {
        anchorNanos = measuredNanos
        anchorFrames = framesRead
        pendingOffsetNanos = null
        return measuredNanos
    }

    private companion object {
        /** Re-anchor sample-counted times when they are this far from the measured clock. */
        const val RESYNC_NANOS = 20_000_000L
    }
}
