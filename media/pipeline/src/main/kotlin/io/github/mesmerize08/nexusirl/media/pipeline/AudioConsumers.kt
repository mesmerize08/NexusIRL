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

/** What one encoder produces; consumers asking for the same key share it. */
data class AudioEncoderKey(val format: AudioEncoderFormat, val bitrate: Int)

/**
 * Which audio encoders the consumers of one capture need: one per format and bitrate, shared by
 * every consumer asking for that. Upstream streams Opus while its recorder (AVAssetWriter) encodes
 * AAC from the same samples, each at its own bitrate (`SettingsStream.audioBitrate`,
 * `SettingsStreamRecording.audioBitrate`); here a recording next to the stream gets a second
 * encoder unless both want the same format and bitrate.
 *
 * Not thread safe; SharedAudio calls it from the capture-control thread.
 */
class AudioConsumers(
    /** Returns false when the encoder could not start: that key is tried again next time. */
    private val startEncoder: (AudioEncoderKey, MediaSink) -> Boolean,
    private val stopEncoder: (AudioEncoderKey) -> Unit,
) {
    private val fanOuts = mutableMapOf<AudioEncoderKey, FanOutSink>()
    private val consumers = mutableMapOf<MediaSink, AudioEncoderKey>()

    val isEmpty: Boolean
        get() = consumers.isEmpty()

    /** Whether any encoder is running; without one, capturing the microphone is pointless. */
    val hasEncoder: Boolean
        get() = fanOuts.isNotEmpty()

    fun add(consumer: MediaSink, format: AudioEncoderFormat, bitrate: Int) {
        remove(consumer)
        val key = AudioEncoderKey(format, bitrate)
        val fanOut = fanOuts[key] ?: FanOutSink().takeIf { startEncoder(key, it) }?.also { fanOuts[key] = it }
        // The current audio format is replayed to the new consumer.
        fanOut?.add(consumer)
        consumers[consumer] = key
    }

    fun remove(consumer: MediaSink) {
        val key = consumers.remove(consumer) ?: return
        fanOuts[key]?.remove(consumer)
        if (key !in consumers.values && fanOuts.remove(key) != null) {
            stopEncoder(key)
        }
    }
}
