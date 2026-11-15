package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Audio chunks waiting for an encoder input buffer, oldest first. A codec that has no free input
 * buffer for a moment (a busy, slow device) gets them later, in order, instead of losing them (a
 * hole in the audio and its timestamps). Beyond [capacity] chunks the oldest is dropped and
 * counted in [dropped]. Not thread-safe.
 */
internal class ChunkBacklog(private val capacity: Int) {
    private val chunks = ArrayDeque<Pair<ShortArray, Long>>()

    /** Chunks dropped because the backlog was full. */
    var dropped = 0
        private set

    /** Chunks waiting. */
    val size: Int get() = chunks.size

    /**
     * Hands the waiting chunks and then [chunk] (PTS [ptsNanos]) to [tryQueue], oldest first,
     * until it returns false (no input buffer); what it did not take waits, [chunk] as a copy
     * (the caller may reuse its array).
     */
    fun offer(chunk: ShortArray, ptsNanos: Long, tryQueue: (ShortArray, Long) -> Boolean) {
        while (chunks.isNotEmpty()) {
            val (pcm, pts) = chunks.first()
            if (!tryQueue(pcm, pts)) {
                hold(chunk, ptsNanos)
                return
            }
            chunks.removeFirst()
        }
        if (!tryQueue(chunk, ptsNanos)) hold(chunk, ptsNanos)
    }

    private fun hold(chunk: ShortArray, ptsNanos: Long) {
        if (chunks.size >= capacity) {
            chunks.removeFirst()
            dropped++
        }
        chunks.addLast(chunk.copyOf() to ptsNanos)
    }
}
