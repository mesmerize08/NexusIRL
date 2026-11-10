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
     * An empty range is ignored. A codec minimum above the format's limit gives the limit: if the
     * codec then refuses it, the usual fallback applies rather than an out-of-spec stream.
     */
    fun bitrate(requested: Int, channels: Int, supported: IntRange? = null): Int {
        val limit = if (this == AAC && channels == 1) 256_000 else 320_000
        val range = supported?.takeUnless { it.isEmpty() } ?: return requested.coerceAtMost(limit)
        if (range.first > limit) {
            return limit
        }
        return requested.coerceIn(range.first, minOf(limit, range.last))
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
    /**
     * A running encoder: started for [started], producing [encoded]; [aliases] are other requests
     * that turned out to produce the same and share it.
     */
    private class Running(val started: AudioEncoderKey, val encoded: AudioEncoderKey, val fanOut: FanOutSink) {
        val aliases = mutableSetOf<AudioEncoderKey>()

        fun serves(key: AudioEncoderKey) = key == started || key == encoded || key in aliases
    }

    private val running = mutableListOf<Running>()

    /** Consumers with a running encoder; those whose encoder failed get no audio and are not kept. */
    private val consumers = mutableMapOf<MediaSink, Running>()

    /** True when nothing is encoding, so capture can stop. */
    val isEmpty: Boolean
        get() = consumers.isEmpty()

    /** Whether any encoder is running; without one, capturing the microphone is pointless. */
    val hasEncoder: Boolean
        get() = running.isNotEmpty()

    /** Returns what [consumer] gets (e.g. AAC after an Opus fallback), or null for no audio. */
    fun add(consumer: MediaSink, format: AudioEncoderFormat, bitrate: Int): AudioEncoderKey? {
        remove(consumer)
        val key = AudioEncoderKey(format, bitrate)
        val encoder = running.firstOrNull { it.serves(key) } ?: start(key) ?: return null
        // The current audio format is replayed to the new consumer.
        encoder.fanOut.add(consumer)
        consumers[consumer] = encoder
        return encoder.encoded
    }

    /**
     * Starts an encoder for [key]. Whether Opus falls back or the bitrate is clamped is only known
     * once it runs: one that duplicates a running encoder is released and that one shared.
     */
    private fun start(key: AudioEncoderKey): Running? {
        val fanOut = FanOutSink()
        val encoded = startEncoder(key, fanOut) ?: return null
        val existing = running.firstOrNull { it.encoded == encoded }
        if (existing != null) {
            stopEncoder(key)
            existing.aliases += key
            return existing
        }
        return Running(key, encoded, fanOut).also { running += it }
    }

    fun remove(consumer: MediaSink) {
        val encoder = consumers.remove(consumer) ?: return
        encoder.fanOut.remove(consumer)
        if (consumers.values.none { it === encoder }) {
            running.remove(encoder)
            stopEncoder(encoder.started)
        }
    }
}
