package io.github.mesmerize08.nexusirl.stream

import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.settings.SrtAdaptiveBitrateAlgorithm
import io.github.mesmerize08.nexusirl.settings.StreamCodec
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings

/** Which SRT adaptive bitrate algorithm to run, with its tunables (upstream Media/Model). */
data class SrtAdaptiveBitrateChoice(val belabox: Boolean, val settings: AdaptiveBitrateSettings)

/** Everything a [StreamSession] needs, resolved from a [SettingsStream]. */
data class StreamConfig(
    /** Full URL including secrets. In memory only; never log or persist it. */
    val url: String,
    val video: VideoEncoderSettings,
    val srtLatency: Int = 3000,
    /** Null when adaptive bitrate is off for SRT (upstream `adaptiveBitrate = nil`). */
    val srtAdaptiveBitrate: SrtAdaptiveBitrateChoice? = SrtAdaptiveBitrateChoice(false, AdaptiveBitrateSettings.FAST),
) {
    override fun toString(): String = "StreamConfig(url=***, video=$video, srtLatency=$srtLatency)"

    companion object {
        /**
         * Port of upstream `Model.updateAdaptiveBitrateSrt` and `Media.srtSetAdaptiveBitrateAlgorithm`:
         * settings hold kbps (and kbps factors), the algorithms take bps.
         */
        fun from(stream: SettingsStream, resolvedUrl: String): StreamConfig {
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
                ),
                srtLatency = srt.latency,
                srtAdaptiveBitrate = choice,
            )
        }
    }
}
