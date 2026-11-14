// Port of Moblin/Media/HaishinKit/Media/DriftTracker.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.math.max

enum class DriftTrackerMedia { AUDIO, VIDEO }

private class DriftTrackerMediaState(val targetFillLevel: Double) {
    val fillLevels = ArrayDeque<Double>()
    var latestFillLevelPresentationTimeStamp = 0.0

    fun lowWaterMark(): Double = if (targetFillLevel >= 0.1) {
        max(-0.2, -targetFillLevel / 2)
    } else {
        // Upstream: should do something better. The queue is likely close to empty.
        -targetFillLevel
    }
}

/**
 * Estimates how far the publisher's clock has drifted from the output clock, from the fill levels
 * of the audio and video queues. Not thread-safe: callers use one thread, like upstream's queue.
 */
class DriftTracker(private val name: String, private val log: (String) -> Unit = {}) {
    private var audio: DriftTrackerMediaState? = null
    private var video: DriftTrackerMediaState? = null
    private var latestAdjustDriftPresentationTimeStamp = -1.0
    private var drift = 0.0

    fun addMedia(media: DriftTrackerMedia, targetFillLevel: Double) {
        log("drift-tracker: $name: Adding $media with target fill level ${three(targetFillLevel)}")
        val state = DriftTrackerMediaState(targetFillLevel)
        when (media) {
            DriftTrackerMedia.AUDIO -> audio = state
            DriftTrackerMedia.VIDEO -> video = state
        }
    }

    fun getDrift(): Double = drift

    fun setDrift(drift: Double) {
        this.drift = drift
    }

    fun update(media: DriftTrackerMedia, outputPresentationTimeStamp: Double, newestPresentationTimeStamp: Double) {
        val state = state(media) ?: return
        if (outputPresentationTimeStamp <= state.latestFillLevelPresentationTimeStamp + 0.5) return
        state.latestFillLevelPresentationTimeStamp = outputPresentationTimeStamp
        state.fillLevels.addLast(newestPresentationTimeStamp - outputPresentationTimeStamp)
        if (state.fillLevels.size > 60) state.fillLevels.removeFirst()
        if (latestAdjustDriftPresentationTimeStamp == -1.0) {
            latestAdjustDriftPresentationTimeStamp = outputPresentationTimeStamp
        }
        if (outputPresentationTimeStamp <= latestAdjustDriftPresentationTimeStamp + 20.0) return
        latestAdjustDriftPresentationTimeStamp = outputPresentationTimeStamp
        val deviations = listOfNotNull(audio, video)
            .filter {
                it.fillLevels.size >= 30 &&
                    outputPresentationTimeStamp - it.latestFillLevelPresentationTimeStamp < 20.0
            }
            .map { it to estimateDeviations(it) }
        val best = deviations.minByOrNull { it.second.second } ?: return
        val lowerDeviation = deviations.minOf { it.second.first }
        val upperDeviation = best.second.second
        if (upperDeviation < best.first.lowWaterMark()) {
            adjustDrift(upperDeviation)
        } else if (lowerDeviation > 0.2) {
            adjustDrift(lowerDeviation)
        }
    }

    private fun state(media: DriftTrackerMedia) = when (media) {
        DriftTrackerMedia.AUDIO -> audio
        DriftTrackerMedia.VIDEO -> video
    }

    /** The 25th and 75th percentile (lower, upper) of fill level plus drift minus target. */
    private fun estimateDeviations(state: DriftTrackerMediaState): Pair<Double, Double> {
        val deviations = state.fillLevels.sorted().map { it + drift - state.targetFillLevel }
        return deviations[deviations.size / 4] to deviations[3 * deviations.size / 4]
    }

    private fun adjustDrift(deviation: Double) {
        val newDrift = drift - deviation
        log(
            "drift-tracker: $name: Estimated deviation from target ${three(deviation)}, " +
                "Drift ${three(drift)} -> ${three(newDrift)}",
        )
        drift = newDrift
    }

    private fun three(value: Double) = String.format(java.util.Locale.ROOT, "%.3f", value)
}
