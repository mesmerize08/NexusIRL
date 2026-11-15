package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs

/**
 * The capture time of each microphone read. Times are counted from the samples read since an
 * anchor, like upstream, so reads are evenly spaced (Android's per-read timestamps jitter by
 * several milliseconds), and re-anchored on the measured time when the count drifted more than
 * [RESYNC_NANOS] from it (e.g. after a device change, or when the first timestamp was off) for
 * [RESYNC_READS] reads in a row. One measurement that far off, which a busy device gives now and
 * then, does not move the times: re-anchoring on it would leave a gap and then step back over it.
 * Not thread-safe.
 */
internal class MicrophoneClock(private val sampleRate: Int, private val log: (String) -> Unit = {}) {
    private var anchorNanos: Long? = null
    private var anchorFrames = 0L
    private var framesRead = 0L

    /** Reads in a row measured more than [RESYNC_NANOS] from the count. */
    private var offReads = 0

    /** The time of the first frame of a read of [frames] frames whose first frame was measured at [measuredNanos]. */
    fun next(measuredNanos: Long, frames: Int): Long {
        val counted = anchorNanos?.let { it + (framesRead - anchorFrames) * 1_000_000_000L / sampleRate }
        offReads = if (counted != null && abs(measuredNanos - counted) > RESYNC_NANOS) offReads + 1 else 0
        val pts = if (counted == null || offReads >= RESYNC_READS) {
            if (counted != null) log("Microphone time re-anchored by ${(measuredNanos - counted) / 1_000_000} ms")
            offReads = 0
            anchorNanos = measuredNanos
            anchorFrames = framesRead
            measuredNanos
        } else {
            counted
        }
        framesRead += frames
        return pts
    }

    private companion object {
        /** Re-anchor counted times when they are this far from the measured clock. */
        const val RESYNC_NANOS = 20_000_000L

        /** Reads in a row that far off before re-anchoring. */
        const val RESYNC_READS = 3
    }
}
