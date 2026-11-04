// Port of Moblin/Media/HaishinKit/Codec/Audio/AudioEncoderRingBuffer.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Cuts interleaved 16-bit PCM of any read size into chunks of exactly [framesPerChunk] frames
 * (1024 for AAC, 960 for Opus), each timed from where its first frame was captured.
 */
class PcmRechunker(private val channels: Int, private val framesPerChunk: Int, private val sampleRate: Int) {
    private val output = ShortArray(framesPerChunk * channels)

    /** Samples (not frames) already in [output]. */
    private var outputSamples = 0
    private var outputPtsNanos = 0L

    /**
     * Appends [count] samples of [pcm] whose first frame was captured at [ptsNanos]; [emit] gets
     * every completed chunk (the array is reused afterwards).
     */
    fun append(pcm: ShortArray, count: Int, ptsNanos: Long, emit: (ShortArray, Long) -> Unit) {
        var index = 0
        while (index < count) {
            if (outputSamples == 0) {
                outputPtsNanos = ptsNanos + (index / channels) * 1_000_000_000L / sampleRate
            }
            val samples = minOf(output.size - outputSamples, count - index)
            System.arraycopy(pcm, index, output, outputSamples, samples)
            index += samples
            outputSamples += samples
            if (outputSamples == output.size) {
                outputSamples = 0
                emit(output, outputPtsNanos)
            }
        }
    }
}
