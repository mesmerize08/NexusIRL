package io.github.mesmerize08.nexusirl.stream

import io.github.mesmerize08.nexusirl.media.pipeline.AudioEncoderFormat
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.media.pipeline.VideoRateControl
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.settings.SettingsStreamRecording
import io.github.mesmerize08.nexusirl.settings.SrtAdaptiveBitrateAlgorithm
import io.github.mesmerize08.nexusirl.settings.StreamAudioCodec
import io.github.mesmerize08.nexusirl.settings.StreamCodec
import io.github.mesmerize08.nexusirl.settings.StreamRateControl
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings

/** Which SRT adaptive bitrate algorithm to run, with its tunables (upstream Media/Model). */
data class SrtAdaptiveBitrateChoice(val belabox: Boolean, val settings: AdaptiveBitrateSettings)

/** An extra RTMP destination (upstream multi streaming). [url] includes the stream key: memory only. */
data class RtmpDestination(val name: String, val url: String) {
    override fun toString(): String = "RtmpDestination(name=$name, url=***)"
}

/** Everything a [StreamSession] needs, resolved from a [SettingsStream]. */
data class StreamConfig(
    /** Full URL including secrets. In memory only; never log or persist it. */
    val url: String,
    val video: VideoEncoderSettings,
    val srtLatency: Int = 3000,
    /** Null when adaptive bitrate is off for SRT (upstream `adaptiveBitrate = nil`). */
    val srtAdaptiveBitrate: SrtAdaptiveBitrateChoice? = SrtAdaptiveBitrateChoice(false, AdaptiveBitrateSettings.FAST),
    /** More RTMP servers fed by the same encoders (only when [url] is RTMP, as upstream). */
    val rtmpDestinations: List<RtmpDestination> = emptyList(),
    /**
     * Upstream `limitAdaptiveBitrateByTransportBitrate` (off for CBR): adaptive bitrate may not go
     * far above what the link has recently carried.
     */
    val limitAdaptiveBitrateByTransportBitrate: Boolean = true,
    /** The stream's audio codec (upstream `audioCodec.toEncoder()`); recordings stay AAC. */
    val audioFormat: AudioEncoderFormat = AudioEncoderFormat.AAC,
    /**
     * Upstream `setAudioStreamBitrate(stream.audioBitrate)`, bits/s. Upstream also applies a change
     * while live; Android's audio encoders cannot change bitrate mid-stream, so it applies at go-live.
     */
    val audioBitrate: Int = SharedAudio.DEFAULT_BITRATE,
) {
    override fun toString(): String =
        "StreamConfig(url=***, video=$video, audio=$audioFormat ${audioBitrate / 1000}kbps, srtLatency=$srtLatency, rtmpDestinations=${rtmpDestinations.size})"

    companion object {
        /** Upstream's audio bitrate picker range (`isValidAudioBitrate`). */
        private val AUDIO_BITRATES = 32_000..320_000

        /** Upstream ModelRecording: the recording's own audio bitrate; 0 ("Auto") is the default. */
        fun recordingAudioBitrate(recording: SettingsStreamRecording?): Int {
            val bitrate = recording?.audioBitrate ?: 0
            if (bitrate <= 0) {
                return SharedAudio.DEFAULT_BITRATE
            }
            return bitrate.coerceIn(AUDIO_BITRATES.first.toLong(), AUDIO_BITRATES.last.toLong()).toInt()
        }

        /**
         * Port of upstream `Model.updateAdaptiveBitrateSrt` and `Media.srtSetAdaptiveBitrateAlgorithm`:
         * settings hold kbps (and kbps factors), the algorithms take bps.
         */
        fun from(stream: SettingsStream, resolvedUrl: String, rtmpDestinations: List<RtmpDestination> = emptyList()): StreamConfig {
            val srt = stream.srt
            val abr = srt.adaptiveBitrate
            val choice = if (!srt.adaptiveBitrateEnabled) {
                null
            } else {
                when (abr.algorithm) {
                    SrtAdaptiveBitrateAlgorithm.FastIrl -> SrtAdaptiveBitrateChoice(
                        false,
                        AdaptiveBitrateSettings.FAST.copy(
                            packetsInFlight = abr.fastIrlSettings.packetsInFlight.toLong(),
                            minimumBitrate = (abr.fastIrlSettings.minimumBitrate * 1000).toLong(),
                        ),
                    )
                    SrtAdaptiveBitrateAlgorithm.SlowIrl -> SrtAdaptiveBitrateChoice(false, AdaptiveBitrateSettings.SLOW)
                    SrtAdaptiveBitrateAlgorithm.CustomIrl -> {
                        val custom = abr.customSettings
                        SrtAdaptiveBitrateChoice(
                            false,
                            AdaptiveBitrateSettings(
                                packetsInFlight = custom.packetsInFlight.toLong(),
                                rttDiffHighFactor = custom.rttDiffHighDecreaseFactor.toDouble(),
                                rttDiffHighAllowedSpike = custom.rttDiffHighAllowedSpike.toDouble(),
                                rttDiffHighMinDecrease = (custom.rttDiffHighMinimumDecrease * 1000).toLong(),
                                pifDiffIncreaseFactor = (custom.pifDiffIncreaseFactor * 1000).toLong(),
                                minimumBitrate = (custom.minimumBitrate * 1000).toLong(),
                            ),
                        )
                    }
                    SrtAdaptiveBitrateAlgorithm.Belabox -> SrtAdaptiveBitrateChoice(
                        true,
                        AdaptiveBitrateSettings.BELABOX.copy(minimumBitrate = (abr.belaboxSettings.minimumBitrate * 1000).toLong()),
                    )
                }
            }
            return StreamConfig(
                url = resolvedUrl,
                video = VideoEncoderSettings(
                    width = stream.canvasWidth,
                    height = stream.canvasHeight,
                    fps = stream.fps,
                    bitrate = stream.bitrate,
                    hevc = stream.codec == StreamCodec.H265_HEVC,
                    // 0 is "Auto" (upstream), which is 2 seconds like recordings.
                    keyFrameIntervalSeconds = stream.maxKeyFrameInterval.takeIf { it > 0 }?.coerceAtMost(10) ?: 2,
                    // ABR (the default) is CBR plus upstream's 1.2x limit, which CBR also gets, as
                    // some encoders overshoot even in CBR. VBR is unlimited, as upstream.
                    rateControl = if (stream.rateControl == StreamRateControl.VBR) VideoRateControl.VBR else VideoRateControl.CBR,
                    limitDataRate = stream.rateControl != StreamRateControl.VBR,
                ),
                srtLatency = srt.latency,
                srtAdaptiveBitrate = choice,
                rtmpDestinations = rtmpDestinations,
                limitAdaptiveBitrateByTransportBitrate = stream.rateControl != StreamRateControl.CBR,
                audioFormat = when (stream.audioCodec) {
                    StreamAudioCodec.AAC -> AudioEncoderFormat.AAC
                    StreamAudioCodec.OPUS -> AudioEncoderFormat.OPUS
                },
                audioBitrate = stream.audioBitrate.coerceIn(AUDIO_BITRATES),
            )
        }
    }
}
