// Port of the output() presentation time stamp grid in Moblin/Media/HaishinKit/Media/Audio/BufferedAudio.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.util.Locale
import kotlin.math.abs

/**
 * Hands out the presentation time stamp for each audio output tick: a grid of one frame per tick
 * (frameLength / sampleRate) that starts at the first tick's clock and is moved one frame back or
 * forward whenever it ends up more than 0.03 s from the clock. Not thread-safe.
 */
class BufferedAudioClock(private val sampleRate: Int, private val frameLength: Int, private val log: (String) -> Unit = {}) {
    private var counter = -1L
    private var start: Double? = null

    fun next(clockSeconds: Double): Double {
        counter += 1
        val origin = start ?: clockSeconds.also { start = it }
        var presentationTimeStamp = grid(origin)
        val delta = presentationTimeStamp - clockSeconds
        if (abs(delta) > DELTA_LIMIT) {
            if (delta > 0) {
                log(
                    "buffered-audio: Adjust PTS back in time. Calculated is ${three(presentationTimeStamp)} " +
                        "and clock is ${three(clockSeconds)}",
                )
                counter -= 1
            } else {
                log(
                    "buffered-audio: Adjust PTS forward in time. Calculated is ${three(presentationTimeStamp)} " +
                        "and clock is ${three(clockSeconds)}",
                )
                counter += 1
            }
            presentationTimeStamp = grid(origin)
        }
        return presentationTimeStamp
    }

    private fun grid(origin: Double) = origin + frameLength.toDouble() * counter / sampleRate

    private fun three(value: Double) = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val DELTA_LIMIT = 0.03
    }
}
