package io.github.mesmerize08.nexusirl.media.pipeline.ingest

/** One compressed video access unit from a publisher. Times are host nanoseconds. */
class IngestVideoAccessUnit(val isKeyFrame: Boolean, val ptsNanos: Long, val dtsNanos: Long, val data: ByteArray)

/**
 * Holds compressed video from an untrusted publisher until it is due for decoding.
 *
 * Bounded by [maxBytes] and [maxFrames]. When an add would pass a bound, everything before the
 * newest queued key frame is dropped (the last GOP is kept). If that is not enough the whole queue
 * is dropped and non-key units are skipped until the next key frame.
 *
 * Threading: not thread-safe; the caller serialises all calls.
 */
class IngestJitterBuffer(
    private val maxBytes: Long = minOf(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024),
    private val maxFrames: Int = 1200,
) {
    private val units = ArrayDeque<IngestVideoAccessUnit>()
    private var waitingForKeyFrame = false

    var bytes: Long = 0
        private set

    val size: Int get() = units.size

    /** Adds a unit, in DTS order as received. */
    fun add(unit: IngestVideoAccessUnit) {
        if (waitingForKeyFrame) {
            if (!unit.isKeyFrame) return
            waitingForKeyFrame = false
        }
        if (wouldOverflow(unit)) {
            dropBeforeNewestKeyFrame()
            if (wouldOverflow(unit)) {
                clear()
                if (wouldOverflow(unit) || !unit.isKeyFrame) {
                    waitingForKeyFrame = true
                    return
                }
            }
        }
        units.addLast(unit)
        bytes += unit.data.size
    }

    /** Removes and returns the oldest unit whose DTS is at or before [limitDtsNanos], else null. */
    fun takeDue(limitDtsNanos: Long): IngestVideoAccessUnit? {
        val first = units.firstOrNull() ?: return null
        if (first.dtsNanos > limitDtsNanos) return null
        units.removeFirst()
        bytes -= first.data.size
        return first
    }

    /** The largest PTS held (B-frames make it differ from the last added), or null when empty. */
    fun newestPtsNanos(): Long? = units.maxOfOrNull { it.ptsNanos }

    fun clear() {
        units.clear()
        bytes = 0
    }

    private fun wouldOverflow(unit: IngestVideoAccessUnit): Boolean = units.size + 1 > maxFrames || bytes + unit.data.size > maxBytes

    private fun dropBeforeNewestKeyFrame() {
        val index = units.indexOfLast { it.isKeyFrame }
        repeat(index) { bytes -= units.removeFirst().data.size }
    }
}
