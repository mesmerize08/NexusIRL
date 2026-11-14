// Port of Moblin/Media/HaishinKit/Media/Audio/BufferedAudio.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.util.Locale
import kotlin.math.abs

/** [silent] means: play silence for this frame's duration (upstream zeroes the last buffer instead). */
data class BufferedAudioOutput<F>(val frame: F, val silent: Boolean)

/**
 * Holds incoming audio frames and picks the one to play for an output time. Output timing (the
 * timer, sample rate and frame length) belongs to [BufferedAudioClock] and the caller. Not
 * thread-safe: callers use one thread, like upstream's queue.
 */
class BufferedAudio<F>(
    private val name: String,
    val latency: Double,
    private val manualOutput: Boolean,
    private val driftTracker: DriftTracker?,
    private val presentationTimeStamp: (F) -> Double,
    private val log: (String) -> Unit = {},
) {
    private val frames = ArrayDeque<F>()
    private var latest: F? = null
    private var isInitialBuffering = true
    private var isSyncingWithOutput = true
    private var hasFrameBeenAppended = false
    private val stats = BufferedStats()

    init {
        driftTracker?.addMedia(DriftTrackerMedia.AUDIO, latency)
    }

    fun numberOfFrames(): Int = frames.size

    fun appendFrame(frame: F) {
        frames.addLast(frame)
        hasFrameBeenAppended = true
    }

    fun getFrame(outputPresentationTimeStamp: Double): BufferedAudioOutput<F>? {
        var candidate: F? = null
        var numberOfFramesConsumed = 0
        val drift = driftTracker?.getDrift() ?: 0.0
        while (frames.isNotEmpty()) {
            val next = frames.first()
            if (latest == null) latest = next
            if (frames.size > MAX_FRAMES) {
                log("buffered-audio: $name: Over $MAX_FRAMES buffers (${frames.size}) buffered. Dropping oldest buffer.")
                candidate = frames.removeFirst()
                numberOfFramesConsumed += 1
                continue
            }
            if (hasBestFrame(next, candidate, outputPresentationTimeStamp, drift)) break
            candidate = frames.removeFirst()
            numberOfFramesConsumed += 1
            isInitialBuffering = false
        }
        if (!isInitialBuffering) {
            updateStatsAndLog(outputPresentationTimeStamp, candidate, drift, numberOfFramesConsumed)
        }
        var silent = false
        var result = candidate
        if (result != null) {
            latest = result
        } else if (latest != null) {
            result = latest
            silent = true
        }
        if (!isInitialBuffering && hasFrameBeenAppended && !manualOutput) {
            hasFrameBeenAppended = false
            val newest = frames.lastOrNull() ?: latest
            if (driftTracker != null && newest != null) {
                driftTracker.update(
                    DriftTrackerMedia.AUDIO,
                    outputPresentationTimeStamp,
                    presentationTimeStamp(newest),
                )
            }
        }
        return result?.let { BufferedAudioOutput(it, silent) }
    }

    private fun updateStatsAndLog(outputPresentationTimeStamp: Double, candidate: F?, drift: Double, numberOfFramesConsumed: Int) {
        if (numberOfFramesConsumed == 0) {
            stats.incrementDuplicated()
        } else if (numberOfFramesConsumed > 1) {
            stats.incrementDropped(numberOfFramesConsumed - 1)
        }
        val (duplicated, dropped) = stats.getStats(outputPresentationTimeStamp) ?: return
        val last = frames.lastOrNull()?.let(presentationTimeStamp) ?: 0.0
        val first = frames.firstOrNull()?.let(presentationTimeStamp) ?: 0.0
        log(
            "buffered-audio: $name: $duplicated duplicated and $dropped dropped buffers. " +
                "Output ${three(outputPresentationTimeStamp)}, " +
                "Current ${three(candidate?.let(presentationTimeStamp) ?: 0.0)}, " +
                "${three(first + drift)}..${three(last + drift)} (${three(last - first)}), " +
                "Buffers ${frames.size}",
        )
    }

    private fun hasBestFrame(next: F, candidate: F?, outputPresentationTimeStamp: Double, drift: Double): Boolean =
        if (isSyncingWithOutput) {
            hasBestFrameSyncing(next, candidate, outputPresentationTimeStamp, drift)
        } else if (candidate != null) {
            hasBestFrameNormal(candidate, outputPresentationTimeStamp, drift)
        } else {
            false
        }

    // Find the first frame that is ahead in time.
    private fun hasBestFrameSyncing(next: F, candidate: F?, outputPresentationTimeStamp: Double, drift: Double): Boolean {
        val delta = presentationTimeStamp(next) + drift - outputPresentationTimeStamp
        if (delta <= 0) return false
        if (candidate != null) isSyncingWithOutput = false
        return true
    }

    // Do not skip any frames unless very far apart.
    private fun hasBestFrameNormal(candidate: F, outputPresentationTimeStamp: Double, drift: Double): Boolean {
        val delta = presentationTimeStamp(candidate) + drift - outputPresentationTimeStamp
        if (abs(delta) > RESYNC_THRESHOLD) isSyncingWithOutput = true
        return true
    }

    private fun three(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val MAX_FRAMES = 300
        const val RESYNC_THRESHOLD = 0.05
    }
}
