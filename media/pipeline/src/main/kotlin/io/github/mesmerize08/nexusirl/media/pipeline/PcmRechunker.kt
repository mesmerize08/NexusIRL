// Port of Moblin/Media/HaishinKit/Codec/Audio/AudioEncoderRingBuffer.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.math.abs

/**
 * Cuts interleaved 16-bit PCM of any read size into chunks of exactly [framesPerChunk] frames
 * (1024 for AAC, 960 for Opus), each timed from where its first frame was captured.
 *
 * When the capture loop re-anchors its times (by more than 20 ms, e.g. after a device change),
 * a partly filled chunk is dropped and the next one starts on the new timeline; frames that would
 * start before the previous chunk ends are skipped, so times never overlap or go backwards.
 */
class PcmRechunker(private val channels: Int, private val framesPerChunk: Int, private val sampleRate: Int) {
    private val output = ShortArray(framesPerChunk * channels)

    /** Samples (not frames) already in [output]. */
    private var outputSamples = 0
    private var outputPtsNanos = 0L

    /** Where the last emitted chunk ends; null before the first. */
    private var emittedEndNanos: Long? = null

    /**
     * Appends [count] samples of [pcm] whose first frame was captured at [ptsNanos]; [emit] gets
     * every completed chunk (the array is reused afterwards).
     */
    fun append(pcm: ShortArray, count: Int, ptsNanos: Long, emit: (ShortArray, Long) -> Unit) {
        if (outputSamples > 0 && abs(ptsNanos - (outputPtsNanos + nanos(outputSamples / channels))) > RESYNC_NANOS) {
            outputSamples = 0
        }
        var index = 0
        val behind = emittedEndNanos?.let { it - ptsNanos } ?: 0
        if (outputSamples == 0 && behind > TOLERANCE_NANOS) {
            // Ceiling: the first frame at or after the previous chunk's end.
            val skipFrames = ((behind - TOLERANCE_NANOS) * sampleRate + 999_999_999) / 1_000_000_000
            index = (minOf(skipFrames, (count / channels).toLong()) * channels).toInt()
        }
        while (index < count) {
            if (outputSamples == 0) {
                outputPtsNanos = ptsNanos + nanos(index / channels)
            }
            val samples = minOf(output.size - outputSamples, count - index)
            System.arraycopy(pcm, index, output, outputSamples, samples)
            index += samples
            outputSamples += samples
            if (outputSamples == output.size) {
                outputSamples = 0
                emittedEndNanos = outputPtsNanos + nanos(framesPerChunk)
                emit(output, outputPtsNanos)
            }
        }
    }

    private fun nanos(frames: Int): Long = frames * 1_000_000_000L / sampleRate

    private companion object {
        /** Well under the capture loop's 20 ms re-anchor threshold (AudioPipeline.RESYNC_NANOS). */
        const val RESYNC_NANOS = 10_000_000L

        /** Times are whole nanoseconds: continuous input may land a rounding step early. */
        const val TOLERANCE_NANOS = 1_000L
    }
}
