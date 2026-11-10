// Port of Moblin/Media/HaishinKit/Codec/Audio/AudioEncoderRingBuffer.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Cuts interleaved 16-bit PCM of any read size into chunks of exactly [framesPerChunk] frames
 * (1024 for AAC, 960 for Opus), each timed from where its first frame was captured.
 *
 * When the capture loop says its times re-anchored (a device change), a partly filled chunk is
 * dropped and the next one starts on the new timeline. Times never overlap or go backwards: a new
 * timeline less than a chunk behind skips those frames; one further behind is shifted to start
 * where the last chunk ended, rather than dropping seconds of audio.
 */
class PcmRechunker(private val channels: Int, private val framesPerChunk: Int, private val sampleRate: Int) {
    private val output = ShortArray(framesPerChunk * channels)

    /** Samples (not frames) already in [output]. */
    private var outputSamples = 0
    private var outputPtsNanos = 0L

    /** Where the last emitted chunk ends; null before the first. */
    private var emittedEndNanos: Long? = null

    /** Frames still to skip after a resync less than a chunk behind. */
    private var skipFrames = 0L

    /** Added to incoming times after a resync far behind, until the next resync. */
    private var offsetNanos = 0L

    /**
     * Appends [count] samples of [pcm] whose first frame was captured at [ptsNanos]; [resync]
     * says the capture times re-anchored before this read. [emit] gets every completed chunk (the
     * array is reused afterwards).
     */
    fun append(pcm: ShortArray, count: Int, ptsNanos: Long, resync: Boolean = false, emit: (ShortArray, Long) -> Unit) {
        if (resync) {
            restart(ptsNanos)
        }
        var index = 0
        if (skipFrames > 0) {
            val frames = minOf(skipFrames, (count / channels).toLong()).toInt()
            skipFrames -= frames
            index = frames * channels
        }
        while (index < count) {
            if (outputSamples == 0) {
                outputPtsNanos = ptsNanos + offsetNanos + nanos(index / channels)
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

    private fun restart(ptsNanos: Long) {
        outputSamples = 0
        skipFrames = 0
        offsetNanos = 0
        val behind = emittedEndNanos?.let { it - ptsNanos } ?: return
        if (behind <= 0) {
            return
        }
        if (behind <= nanos(framesPerChunk)) {
            // Ceiling: the first frame at or after the previous chunk's end.
            skipFrames = (behind * sampleRate + 999_999_999) / 1_000_000_000
        } else {
            offsetNanos = behind
        }
    }

    private fun nanos(frames: Int): Long = frames * 1_000_000_000L / sampleRate
}
