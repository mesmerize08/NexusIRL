// Port of Moblin/Media/HaishinKit/Codec/Audio/AudioEncoderRingBuffer.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Cuts interleaved 16-bit PCM of any read size into chunks of exactly [framesPerChunk] frames
 * (1024 for AAC, 960 for Opus), each timed from where its first frame was captured.
 *
 * When the capture loop says its times re-anchored (a device change), a partly filled chunk is
 * dropped and the next one starts on the new timeline, which stays on the capture clock so audio
 * stays aligned with video. A timeline behind the last chunk's end skips the overlapping frames,
 * so times do not overlap or go backwards; beyond [MAX_SKIP_NANOS] one step back is accepted
 * rather than seconds of silence.
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

    private fun restart(ptsNanos: Long) {
        outputSamples = 0
        val behind = emittedEndNanos?.let { it - ptsNanos } ?: 0
        // Ceiling: the first frame at or after the previous chunk's end.
        skipFrames = if (behind in 1..MAX_SKIP_NANOS) (behind * sampleRate + 999_999_999) / 1_000_000_000 else 0
    }

    private fun nanos(frames: Int): Long = frames * 1_000_000_000L / sampleRate

    private companion object {
        /** A capture timeline further behind than this is pathological: not worth the silence. */
        const val MAX_SKIP_NANOS = 2_000_000_000L
    }
}
