package io.github.mesmerize08.nexusirl.media.pipeline

import android.media.MediaFormat
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig

/** Upstream `AudioEncoderSettings.Format`. */
enum class AudioEncoderFormat(val mimeType: String, val framesPerChunk: Int, val displayName: String) {
    AAC(MediaFormat.MIMETYPE_AUDIO_AAC, 1024, "AAC"),

    /** 20 ms frames at 48 kHz (upstream `mFramesPerPacket: 960`). */
    OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, 960, "Opus"),
    ;

    /**
     * The bitrate to configure for [requested]: at most what the format carries for [channels]
     * (AAC-LC: 6144 bits per channel per 1024 frames, 288 kbps mono at 48 kHz; 256 kbps leaves
     * headroom), then within the encoder's [supported] range like upstream `AudioEncoder.setBitrate`.
     */
    fun bitrate(requested: Int, channels: Int, supported: IntRange? = null): Int {
        val limit = if (this == AAC && channels == 1) 256_000 else 320_000
        val bitrate = requested.coerceAtMost(limit)
        return supported?.let { bitrate.coerceIn(it) } ?: bitrate
    }

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
    /**
     * Returns what the encoder actually produces (AAC when Opus is unavailable, a lower bitrate
     * than asked when the codec cannot do more), or null when it could not start: that key is
     * tried again next time.
     */
    private val startEncoder: (AudioEncoderKey, MediaSink) -> AudioEncoderKey?,
    private val stopEncoder: (AudioEncoderKey) -> Unit,
) {
    /** A running encoder: started for [requested], producing [encoded]. */
    private class Running(val requested: AudioEncoderKey, val encoded: AudioEncoderKey, val fanOut: FanOutSink)

    private val running = mutableListOf<Running>()

    /** Null for consumers whose encoder could not start. */
    private val consumers = mutableMapOf<MediaSink, Running?>()

    val isEmpty: Boolean
        get() = consumers.isEmpty()

    /** Whether any encoder is running; without one, capturing the microphone is pointless. */
    val hasEncoder: Boolean
        get() = running.isNotEmpty()

    /** Returns what [consumer] gets (e.g. AAC after an Opus fallback), or null for no audio. */
    fun add(consumer: MediaSink, format: AudioEncoderFormat, bitrate: Int): AudioEncoderKey? {
        remove(consumer)
        val key = AudioEncoderKey(format, bitrate)
        // An encoder already producing what is asked for is shared, whatever it was started for.
        val encoder = running.firstOrNull { it.requested == key } ?: running.firstOrNull { it.encoded == key } ?: run {
            val fanOut = FanOutSink()
            startEncoder(key, fanOut)?.let { Running(key, it, fanOut) }?.also { running += it }
        }
        // The current audio format is replayed to the new consumer.
        encoder?.fanOut?.add(consumer)
        consumers[consumer] = encoder
        return encoder?.encoded
    }

    fun remove(consumer: MediaSink) {
        val encoder = consumers.remove(consumer) ?: return
        encoder.fanOut.remove(consumer)
        if (consumers.values.none { it === encoder }) {
            running.remove(encoder)
            stopEncoder(encoder.requested)
        }
    }
}
