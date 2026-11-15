package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Where the audio chunk fed to the encoders last ends, from any source, so the next one never
 * starts before it: chunks never overlap, and an encoder's own chunking never steps back.
 * Not thread-safe.
 */
internal class FeedTimeline(private val sampleRate: Int) {
    /** The end (PTS plus duration, nanoseconds) of the chunk fed last; [Long.MIN_VALUE] before the first. */
    var endNanos = Long.MIN_VALUE
        private set

    /**
     * The frames to cut from the start of a chunk of [frames] frames at [ptsNanos] so that the rest
     * starts at or after [endNanos] (rounded up); [frames] when nothing would be left.
     */
    fun framesToCut(ptsNanos: Long, frames: Int): Int {
        if (ptsNanos >= endNanos) return 0
        // Compared first, so neither the subtraction nor the multiplication below can overflow.
        if (ptsNanos <= endNanos - framesToNanos(frames)) return frames
        val overlap = endNanos - ptsNanos
        val cut = (overlap * sampleRate + 999_999_999L) / 1_000_000_000L
        return minOf(cut, frames.toLong()).toInt()
    }

    /** A chunk of [frames] frames starting at [startNanos] was fed. */
    fun fed(startNanos: Long, frames: Int) {
        endNanos = startNanos + framesToNanos(frames)
    }

    private fun framesToNanos(frames: Int) = frames * 1_000_000_000L / sampleRate
}
