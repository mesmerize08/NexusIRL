// Port of Moblin/Media/HaishinKit/Media/Video/BufferedVideo.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.util.Locale

/**
 * Holds incoming video frames in presentation order and picks the one to show for an output time.
 *
 * Threading: an instance is not thread-safe. The caller serialises [appendFrame] against
 * [updateFrame], [setLatestFrame] and [clear] (upstream runs them all on one queue). Only the
 * [DriftTracker] may be shared.
 *
 * Frame ownership: the buffer owns every frame it is given until it passes it to [onRelease],
 * exactly once: a frame dropped from the queue, a frame that never became the latest one, a
 * replaced latest frame, and everything still held on [clear]. The caller renders the frame
 * [updateFrame] returns at selection time and must not keep it past its release. [F] should be a
 * wrapper whose release is idempotent, and [onRelease] must not throw.
 *
 * Unlike upstream, [appendFrame] also drops (and releases) the oldest frame once twice the
 * upstream cap is queued, so a stalled consumer cannot grow the queue without bound.
 *
 * @param newestPresentationTimeStamp when set, the newest timestamp the drift tracker sees, for a
 *   stream whose frames wait in an earlier stage (a decoder) before reaching this queue. A null
 *   result falls back to the queue's newest frame. Without it the behaviour is upstream's.
 */
class BufferedVideo<F>(
    private val name: String,
    private val update: Boolean,
    val latency: Double,
    private val driftTracker: DriftTracker?,
    private val presentationTimeStamp: (F) -> Double,
    private val onReady: () -> Unit = {},
    private val onRelease: (F) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val newestPresentationTimeStamp: (() -> Double?)? = null,
) {
    private val frames = ArrayDeque<F>()
    private var current: F? = null
    private var isInitialBuffering = true
    private var hasFrameBeenAppended = false
    private val stats = BufferedStats()

    init {
        driftTracker?.addMedia(DriftTrackerMedia.VIDEO, latency)
    }

    fun appendFrame(frame: F) {
        if (frames.size >= HARD_MAX_FRAMES) onRelease(frames.removeFirst())
        hasFrameBeenAppended = true
        val pts = presentationTimeStamp(frame)
        val index = frames.indexOfLast { presentationTimeStamp(it) < pts }
        frames.add(index + 1, frame)
    }

    /** Picks the frame for [outputPresentationTimeStamp]; returns it when it is new, else null. */
    fun updateFrame(outputPresentationTimeStamp: Double, forceUpdate: Boolean = false): F? {
        if (!(update || forceUpdate)) return null
        var candidate: F? = null
        var numberOfFramesConsumed = 0
        val drift = driftTracker?.getDrift() ?: 0.0
        while (frames.isNotEmpty()) {
            val next = frames.first()
            if (frames.size > MAX_FRAMES) {
                candidate = consume(candidate)
                numberOfFramesConsumed += 1
                continue
            }
            if (hasBestFrame(next, candidate, outputPresentationTimeStamp, drift)) break
            candidate = consume(candidate)
            numberOfFramesConsumed += 1
            markInitialBufferingComplete()
        }
        if (!isInitialBuffering) {
            updateStatsAndLog(outputPresentationTimeStamp, drift, numberOfFramesConsumed)
        }
        if (candidate != null) {
            current?.let(onRelease)
            current = candidate
        }
        if (!isInitialBuffering && hasFrameBeenAppended && update) {
            hasFrameBeenAppended = false
            val newest = newestPresentationTimeStamp?.invoke()
                ?: (frames.lastOrNull() ?: current)?.let(presentationTimeStamp)
            if (driftTracker != null && newest != null) {
                driftTracker.update(DriftTrackerMedia.VIDEO, outputPresentationTimeStamp, newest)
            }
        }
        return candidate
    }

    /** Removes the first frame; the candidate it replaces never becomes the latest, so it is released. */
    private fun consume(previousCandidate: F?): F {
        previousCandidate?.let(onRelease)
        return frames.removeFirst()
    }

    private fun updateStatsAndLog(outputPresentationTimeStamp: Double, drift: Double, numberOfFramesConsumed: Int) {
        if (numberOfFramesConsumed == 0) {
            stats.incrementDuplicated()
        } else if (numberOfFramesConsumed > 1) {
            stats.incrementDropped(numberOfFramesConsumed - 1)
        }
        val (duplicated, dropped) = stats.getStats(outputPresentationTimeStamp) ?: return
        val last = frames.lastOrNull()?.let(presentationTimeStamp) ?: 0.0
        val first = frames.firstOrNull()?.let(presentationTimeStamp) ?: 0.0
        log(
            "buffered-video: $name: $duplicated duplicated and $dropped dropped buffers. " +
                "Output ${three(outputPresentationTimeStamp)}, " +
                "Current ${three(current?.let(presentationTimeStamp) ?: 0.0)}, " +
                "${three(first + drift)}..${three(last + drift)} (${three(last - first)}), " +
                "Buffers ${frames.size}",
        )
    }

    // Break on first frame that is ahead in time.
    private fun hasBestFrame(next: F, candidate: F?, outputPresentationTimeStamp: Double, drift: Double): Boolean {
        val delta = presentationTimeStamp(next) + drift - outputPresentationTimeStamp
        if (delta <= 0) return false
        return candidate != null || delta > AHEAD_THRESHOLD
    }

    private fun markInitialBufferingComplete() {
        if (isInitialBuffering) onReady()
        isInitialBuffering = false
    }

    /** Replaces the latest frame; the replaced one is released unless it is [frame] itself. */
    fun setLatestFrame(frame: F?) {
        val replaced = current
        current = frame
        if (replaced != null && replaced !== frame) onRelease(replaced)
    }

    /** Releases every queued frame and the latest frame, leaving the buffer empty. */
    fun clear() {
        val held = frames.toList()
        val latest = current
        frames.clear()
        current = null
        held.forEach(onRelease)
        if (latest != null && held.none { it === latest }) onRelease(latest)
    }

    fun latestFrame(): F? = current

    fun numberOfFrames(): Int = frames.size

    private fun three(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val MAX_FRAMES = 200

        /** Android's addition: a cap that holds even when [updateFrame] is not called. */
        const val HARD_MAX_FRAMES = 2 * MAX_FRAMES
        const val AHEAD_THRESHOLD = 0.01
    }
}
