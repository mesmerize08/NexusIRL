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
 * A decoder can only start at a key frame, so the buffer starts out waiting for one and [clear]
 * returns it to that state: non-key units are dropped until the next key frame arrives.
 *
 * A unit's DTS is clamped to at most its PTS and to at least the previous unit's DTS, so [takeDue]
 * (which looks at the head only) returns DTS order and one bogus far-future DTS cannot block the
 * queue.
 *
 * Threading: not thread-safe; the caller serialises all calls.
 */
class IngestJitterBuffer(
    private val maxBytes: Long = minOf(Runtime.getRuntime().maxMemory() / 8, 64L * 1024 * 1024),
    private val maxFrames: Int = 1200,
) {
    private val units = ArrayDeque<IngestVideoAccessUnit>()
    private var waitingForKeyFrame = true
    private var lastDtsNanos = Long.MIN_VALUE

    var bytes: Long = 0
        private set

    val size: Int get() = units.size

    /** Adds a unit, in DTS order as received. */
    fun add(incoming: IngestVideoAccessUnit) {
        if (waitingForKeyFrame && !incoming.isKeyFrame) return
        val dts = maxOf(minOf(incoming.dtsNanos, incoming.ptsNanos), lastDtsNanos)
        val unit =
            if (dts == incoming.dtsNanos) {
                incoming
            } else {
                IngestVideoAccessUnit(incoming.isKeyFrame, incoming.ptsNanos, dts, incoming.data)
            }
        if (wouldOverflow(unit)) {
            dropBeforeNewestKeyFrame()
            if (wouldOverflow(unit)) {
                clear()
                if (wouldOverflow(unit) || !unit.isKeyFrame) return
            }
        }
        waitingForKeyFrame = false
        lastDtsNanos = unit.dtsNanos
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

    /**
     * A backlog (nothing took units for a while): drops every unit before the newest key frame
     * whose DTS is at or before [limitDtsNanos], so decoding restarts there. Without such a key
     * frame nothing is dropped. The key frame stays at the head, so the buffer does not wait for
     * another one. Returns the number of units dropped.
     */
    fun dropBeforeNewestKeyFrameDue(limitDtsNanos: Long): Int {
        val index = units.indexOfLast { it.isKeyFrame && it.dtsNanos <= limitDtsNanos }
        if (index <= 0) return 0
        repeat(index) { bytes -= units.removeFirst().data.size }
        return index
    }

    /** The oldest DTS held, or null when empty. */
    fun oldestDtsNanos(): Long? = units.firstOrNull()?.dtsNanos

    /** The PTS of the oldest unit (the head), or null when empty. */
    fun oldestPtsNanos(): Long? = units.firstOrNull()?.ptsNanos

    /** The largest PTS held (B-frames make it differ from the last added), or null when empty. */
    fun newestPtsNanos(): Long? = units.maxOfOrNull { it.ptsNanos }

    fun clear() {
        units.clear()
        bytes = 0
        waitingForKeyFrame = true
        lastDtsNanos = Long.MIN_VALUE
    }

    private fun wouldOverflow(unit: IngestVideoAccessUnit): Boolean = units.size + 1 > maxFrames || bytes + unit.data.size > maxBytes

    private fun dropBeforeNewestKeyFrame() {
        val index = units.indexOfLast { it.isKeyFrame }
        repeat(index) { bytes -= units.removeFirst().data.size }
    }
}
