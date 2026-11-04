package io.github.mesmerize08.nexusirl.media.pipeline

import android.media.MediaFormat
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig

/** Upstream `AudioEncoderSettings.Format`. */
enum class AudioEncoderFormat(val mimeType: String, val framesPerChunk: Int) {
    AAC(MediaFormat.MIMETYPE_AUDIO_AAC, 1024),

    /** 20 ms frames at 48 kHz (upstream `mFramesPerPacket: 960`). */
    OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, 960),
    ;

    /** What the muxers expect; they build the OpusHead / AudioSpecificConfig themselves. */
    fun config(sampleRate: Int, channels: Int): MpegTsAudioConfig = when (this) {
        AAC -> MpegTsAudioConfig.aacLc(sampleRate, channels)
        OPUS -> MpegTsAudioConfig.opus(sampleRate, channels)
    }
}

/**
 * Which audio encoders the consumers of one capture need: one per format, shared by every
 * consumer of that format. Upstream streams Opus while its recorder (AVAssetWriter) encodes AAC
 * from the same samples; here a recording next to an Opus stream gets a second, AAC encoder.
 *
 * Not thread safe; SharedAudio calls it from the capture-control thread.
 */
class AudioConsumers(
    private val startEncoder: (AudioEncoderFormat, MediaSink) -> Unit,
    private val stopEncoder: (AudioEncoderFormat) -> Unit,
) {
    private val fanOuts = mutableMapOf<AudioEncoderFormat, FanOutSink>()
    private val consumers = mutableMapOf<MediaSink, AudioEncoderFormat>()

    val isEmpty: Boolean
        get() = consumers.isEmpty()

    fun add(consumer: MediaSink, format: AudioEncoderFormat) {
        remove(consumer)
        val fanOut = fanOuts.getOrPut(format) { FanOutSink().also { startEncoder(format, it) } }
        // The current audio format is replayed to the new consumer.
        fanOut.add(consumer)
        consumers[consumer] = format
    }

    fun remove(consumer: MediaSink) {
        val format = consumers.remove(consumer) ?: return
        fanOuts[format]?.remove(consumer)
        if (format !in consumers.values) {
            fanOuts.remove(format)
            stopEncoder(format)
        }
    }
}
