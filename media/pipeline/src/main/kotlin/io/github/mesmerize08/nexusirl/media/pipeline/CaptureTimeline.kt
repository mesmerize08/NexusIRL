package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs

/**
 * Presentation times for microphone reads. Times come from the sample count, like upstream, so
 * frames are evenly spaced; Android's per-read timestamps jitter by several milliseconds. When the
 * count drifts from the clock by more than [RESYNC_NANOS] on two reads in a row that agree (e.g.
 * after a device change), it re-anchors to the clock and sets [resync]; a single wild timestamp is
 * ignored. When the clock stays off but its readings disagree (fallback estimates), it re-anchors
 * to their median after [MAX_OFF_READS] reads.
 *
 * Capture thread only.
 */
class CaptureTimeline(private val sampleRate: Int) {
    var framesRead = 0L
        private set
    private var anchorNanos: Long? = null
    private var anchorFrames = 0L

    /** How far the clock was from the count on the latest reads in a row beyond [RESYNC_NANOS]. */
    private val offOffsets = ArrayList<Long>()

    /** Whether the latest [next] re-anchored, so its time does not continue the previous read's. */
    var resync = false
        private set

    /** The time of a read of [frames] frames whose first frame the clock puts at [measuredNanos]. */
    fun next(measuredNanos: Long, frames: Int): Long {
        resync = false
        val counted = anchorNanos?.let { it + (framesRead - anchorFrames) * 1_000_000_000L / sampleRate }
        val pts = if (counted == null) anchor(measuredNanos) else follow(counted, measuredNanos - counted)
        framesRead += frames
        return pts
    }

    private fun follow(counted: Long, offset: Long): Long {
        if (abs(offset) <= RESYNC_NANOS) {
            offOffsets.clear()
            return counted
        }
        val previous = offOffsets.lastOrNull()
        offOffsets += offset
        if (previous != null && abs(offset - previous) <= RESYNC_NANOS) {
            resync = true
            return anchor(counted + offset)
        }
        if (offOffsets.size < MAX_OFF_READS) {
            return counted
        }
        val sorted = offOffsets.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        if (abs(median) <= RESYNC_NANOS) {
            // Noise around the count, which is right on average.
            offOffsets.clear()
            return counted
        }
        resync = true
        return anchor(counted + median)
    }

    private fun anchor(nanos: Long): Long {
        anchorNanos = nanos
        anchorFrames = framesRead
        offOffsets.clear()
        return nanos
    }

    private companion object {
        /** Re-anchor sample-counted times when they are this far from the measured clock. */
        const val RESYNC_NANOS = 20_000_000L

        /** Reads in a row off the count, however inconsistent, before re-anchoring anyway. */
        const val MAX_OFF_READS = 4
    }
}
