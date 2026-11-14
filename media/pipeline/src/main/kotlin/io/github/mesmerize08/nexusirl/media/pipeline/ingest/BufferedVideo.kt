// Port of Moblin/Media/HaishinKit/Media/Video/BufferedVideo.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.util.Locale

/**
 * Holds incoming video frames in presentation order and picks the one to show for an output time.
 * Not thread-safe: callers use one thread, like upstream's queue. [onRelease] gets every frame that
 * leaves without becoming the latest one, and a replaced latest frame.
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
        hasFrameBeenAppended = true
        val pts = presentationTimeStamp(frame)
        val index = frames.indexOfLast { presentationTimeStamp(it) < pts }
        frames.add(index + 1, frame)
    }

    fun updateFrame(outputPresentationTimeStamp: Double, forceUpdate: Boolean = false) {
        if (!(update || forceUpdate)) return
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
            val newest = frames.lastOrNull() ?: current
            if (driftTracker != null && newest != null) {
                driftTracker.update(
                    DriftTrackerMedia.VIDEO,
                    outputPresentationTimeStamp,
                    presentationTimeStamp(newest),
                )
            }
        }
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

    fun setLatestFrame(frame: F?) {
        current = frame
    }

    fun latestFrame(): F? = current

    fun numberOfFrames(): Int = frames.size

    private fun three(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val MAX_FRAMES = 200
        const val AHEAD_THRESHOLD = 0.01
    }
}
