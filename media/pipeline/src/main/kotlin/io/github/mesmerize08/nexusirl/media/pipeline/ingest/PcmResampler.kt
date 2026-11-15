package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.math.abs

/**
 * Converts decoded ingest audio (interleaved 16-bit PCM, mono or stereo, any rate) to
 * [outputRate] / [outputChannels] in chunks of exactly [framesPerChunk] frames, each with the pts
 * of its first frame.
 *
 * Linear interpolation. Stereo to mono averages, mono to stereo duplicates.
 *
 * Chunk pts is computed from an anchor (the input pts at the start of the run) and the output
 * frame index, never accumulated, so it does not drift over long runs. An input pts more than
 * 100 ms away from the expected next one (a gap or a new stream) starts a new run: the anchor
 * moves to the new pts and a partly filled chunk is dropped.
 *
 * Threading: not thread-safe; the caller serialises all calls.
 */
class PcmResampler(
    private val inputRate: Int,
    private val inputChannels: Int,
    private val outputChannels: Int,
    private val outputRate: Int = 48_000,
    private val framesPerChunk: Int = 1024,
) {
    init {
        require(inputRate > 0 && outputRate > 0 && framesPerChunk > 0) {
            "rates and framesPerChunk must be positive: $inputRate, $outputRate, $framesPerChunk"
        }
        require(inputChannels in 1..2 && outputChannels in 1..2) {
            "only mono and stereo are supported: $inputChannels in, $outputChannels out"
        }
    }

    private var chunk = ShortArray(framesPerChunk * outputChannels)
    private var chunkFrames = 0
    private var chunkPtsNanos = 0L

    private var anchored = false
    private var anchorPtsNanos = 0L

    /** Input frames consumed since the anchor. */
    private var inputConsumed = 0L

    /** Output frames produced since the anchor. */
    private var outputIndex = 0L

    /** The last input frame of the previous call, one value per input channel. */
    private val previous = IntArray(2)
    private var hasPrevious = false

    /**
     * [pcm] interleaved 16-bit, [frames] frames, the first one at [ptsNanos]. [emit] gets each
     * completed chunk with its pts; each chunk is a new array the receiver may keep.
     */
    fun process(pcm: ShortArray, frames: Int, ptsNanos: Long, emit: (chunk: ShortArray, ptsNanos: Long) -> Unit) {
        require(frames * inputChannels <= pcm.size) { "pcm holds ${pcm.size} samples, $frames frames of $inputChannels channels need more" }
        if (frames <= 0) return
        val expected = anchorPtsNanos + inputConsumed * NANOS / inputRate
        if (!anchored || abs(ptsNanos - expected) > GAP_NANOS) {
            reset()
            anchored = true
            anchorPtsNanos = ptsNanos
        }
        rebase()
        val end = inputConsumed + frames
        while (true) {
            val position = outputIndex * inputRate
            val index = position / outputRate
            val remainder = position % outputRate
            val needed = if (remainder == 0L) index else index + 1
            if (needed >= end) break
            if (chunkFrames == 0) chunkPtsNanos = anchorPtsNanos + outputIndex * NANOS / outputRate
            for (channel in 0 until outputChannels) {
                val a = sample(pcm, index, channel)
                val value =
                    if (remainder == 0L) {
                        a
                    } else {
                        val b = sample(pcm, index + 1, channel)
                        a + ((b - a) * remainder / outputRate).toInt()
                    }
                chunk[chunkFrames * outputChannels + channel] = value.toShort()
            }
            outputIndex++
            chunkFrames++
            if (chunkFrames == framesPerChunk) {
                chunkFrames = 0
                val done = chunk
                chunk = ShortArray(framesPerChunk * outputChannels)
                emit(done, chunkPtsNanos)
            }
        }
        for (channel in 0 until inputChannels) previous[channel] = pcm[(frames - 1) * inputChannels + channel].toInt()
        hasPrevious = true
        inputConsumed = end
    }

    /**
     * Moves the anchor forward by whole seconds once both counters pass them, so the nanosecond
     * products stay far from overflowing and pts stays exact (a whole second is an integer number
     * of frames at both rates).
     */
    private fun rebase() {
        val seconds = REBASE_SECONDS
        if (outputIndex >= seconds * outputRate && inputConsumed >= seconds * inputRate) {
            anchorPtsNanos += seconds * NANOS
            outputIndex -= seconds * outputRate
            inputConsumed -= seconds * inputRate
        }
    }

    /** Forgets the anchor, the interpolation history and any partly filled chunk. */
    fun reset() {
        anchored = false
        anchorPtsNanos = 0
        inputConsumed = 0
        outputIndex = 0
        chunkFrames = 0
        hasPrevious = false
    }

    /** Sample of output [channel] for input frame [index] (since the anchor), after channel mapping. */
    private fun sample(pcm: ShortArray, index: Long, channel: Int): Int {
        val local = index - inputConsumed

        fun raw(inputChannel: Int): Int = if (local < 0) {
            check(hasPrevious && local == -1L)
            previous[inputChannel]
        } else {
            pcm[local.toInt() * inputChannels + inputChannel].toInt()
        }
        return when {
            inputChannels == outputChannels -> raw(channel)
            inputChannels == 2 -> (raw(0) + raw(1)) / 2
            else -> raw(0)
        }
    }

    private companion object {
        const val NANOS = 1_000_000_000L
        const val REBASE_SECONDS = 3_600L
        const val GAP_NANOS = 100_000_000L
    }
}
