package io.github.mesmerize08.nexusirl.media.pipeline

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig

/**
 * Receives encoded media (upstream: the Processor's AudioEncoderDelegate/VideoEncoderDelegate).
 * Implemented by adapters around SrtStream / RtmpStream; multiple sinks fan out one encode.
 * Called from encoder output threads.
 */
interface MediaSink {
    fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int)

    fun setVideoConfig(config: MpegTsVideoConfig)

    /** One raw AAC frame (no ADTS header) or Opus packet. */
    fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime)

    /** One Annex-B access unit. [decodeTimeStamp] is null without frame reordering. */
    fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean)
}

/** Fans encoder output out to several sinks (upstream: multiple encoding delegates). */
class FanOutSink : MediaSink {
    private val sinks = java.util.concurrent.CopyOnWriteArrayList<MediaSink>()
    private var audioConfig: Pair<MpegTsAudioConfig, Int>? = null
    private var videoConfig: MpegTsVideoConfig? = null

    /** Adds a sink; the current formats are replayed to it, like `processor.startEncoding()`. */
    @Synchronized
    fun add(sink: MediaSink) {
        audioConfig?.let { sink.setAudioConfig(it.first, it.second) }
        videoConfig?.let { sink.setVideoConfig(it) }
        sinks.add(sink)
    }

    fun remove(sink: MediaSink) {
        sinks.remove(sink)
    }

    @Synchronized
    override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {
        audioConfig = config to sampleRate
        sinks.forEach { it.setAudioConfig(config, sampleRate) }
    }

    @Synchronized
    override fun setVideoConfig(config: MpegTsVideoConfig) {
        videoConfig = config
        sinks.forEach { it.setVideoConfig(config) }
    }

    override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) {
        sinks.forEach { it.writeAudio(frame, presentationTimeStamp) }
    }

    override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {
        sinks.forEach { it.writeVideo(accessUnit, presentationTimeStamp, decodeTimeStamp, isSync) }
    }
}
