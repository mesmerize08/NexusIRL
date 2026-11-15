package io.github.mesmerize08.nexusirl.media.pipeline.ingest

/**
 * How far ahead of the output time compressed video is fed to the decoder (spec §5: 150–500 ms,
 * adaptive). A frame decoded too late for its output time moves it 50 ms later; every second
 * without a late frame moves it 10 ms earlier. Not thread-safe (the GL thread owns it).
 */
class IngestDecodeSchedule(
    initialAheadNanos: Long = 300_000_000,
    private val minAheadNanos: Long = 150_000_000,
    private val maxAheadNanos: Long = 500_000_000,
) {
    var aheadNanos: Long = initialAheadNanos.coerceIn(minAheadNanos, maxAheadNanos)
        private set

    private var lastTickNanos: Long? = null
    private var quietNanos = 0L

    /** A frame was decoded after its output time. */
    fun onLate() {
        aheadNanos = minOf(aheadNanos + LATE_STEP_NANOS, maxAheadNanos)
        quietNanos = 0
    }

    /** Called on every output tick with the host time. */
    fun onTick(nowNanos: Long) {
        val last = lastTickNanos
        if (last == null || nowNanos < last) {
            lastTickNanos = nowNanos
            return
        }
        lastTickNanos = nowNanos
        quietNanos += nowNanos - last
        while (quietNanos >= SECOND_NANOS) {
            quietNanos -= SECOND_NANOS
            aheadNanos = maxOf(aheadNanos - QUIET_STEP_NANOS, minAheadNanos)
        }
    }

    private companion object {
        const val LATE_STEP_NANOS = 50_000_000L
        const val QUIET_STEP_NANOS = 10_000_000L
        const val SECOND_NANOS = 1_000_000_000L
    }
}

/**
 * Notices a video decoder that takes input but never gives a picture (one that silently accepted
 * bad parameter sets). It expires once [timeoutNanos] have passed since the first unit queued
 * after the last picture, with at least [minUnits] queued: fewer is what a decoder holding back
 * its last frames until more data comes looks like when a publisher stops sending. A stream
 * restart (data again after a pause) starts it over. Not thread-safe.
 */
internal class IngestNoPictureWatchdog(private val timeoutNanos: Long = 5_000_000_000, private val minUnits: Int = 3) {
    private var sinceNanos: Long? = null
    private var units = 0

    fun onQueued(nowNanos: Long) {
        if (sinceNanos == null) sinceNanos = nowNanos
        units++
    }

    fun onPicture() = reset()

    fun onRestart() = reset()

    fun expired(nowNanos: Long): Boolean {
        val since = sinceNanos ?: return false
        return units >= minUnits && nowNanos - since >= timeoutNanos
    }

    private fun reset() {
        sinceNanos = null
        units = 0
    }
}

/**
 * The PTS of units queued to the decoder whose picture has not come out yet. A decoder gives
 * pictures in PTS order, so a picture retires its own unit and every earlier one (units it dropped
 * included). Units more than [staleNanos] after a picture are from before the publisher's clock
 * went back, and are retired too. Not thread-safe.
 */
internal class IngestDecoderInFlight(private val staleNanos: Long = 5_000_000_000) {
    private val pts = ArrayList<Long>()

    val size: Int get() = pts.size

    fun add(ptsNanos: Long) {
        pts += ptsNanos
    }

    fun onPicture(ptsNanos: Long) {
        pts.removeAll { it <= ptsNanos || it - ptsNanos > staleNanos }
    }

    fun clear() = pts.clear()
}
