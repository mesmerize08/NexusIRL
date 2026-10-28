package io.github.mesmerize08.nexusirl.media.pipeline

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** What one video encoder can do (for the settings UI and the per-device quirks table). */
data class VideoEncoderInfo(
    val name: String,
    val mimeType: String,
    val hardware: Boolean,
    val supportsVbr: Boolean,
    val supportsCbr: Boolean,
    val supports1080p30: Boolean,
    val supports1080p60: Boolean,
    val supports4k30: Boolean,
)

/**
 * Probes the device's video encoders (docs/PORT_PLAN.md §6 "MediaCodec variance"): prefers a
 * hardware encoder, and reports HEVC availability so the app can fall back to H.264.
 */
object EncoderCapabilities {
    val all: List<VideoEncoderInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it == MediaFormat.MIMETYPE_VIDEO_AVC || it == MediaFormat.MIMETYPE_VIDEO_HEVC }
                    .map { type -> describe(info, type) }
            }
    }

    /** The best encoder for the type: hardware first, then one that can do 1080p30. */
    fun best(mimeType: String): VideoEncoderInfo? = all.filter { it.mimeType == mimeType }
        .sortedWith(compareByDescending<VideoEncoderInfo> { it.hardware }.thenByDescending { it.supports1080p30 })
        .firstOrNull()

    /** Only a hardware HEVC encoder counts: software ones can't keep up in real time (~20 fps at 720p). */
    fun supportsHevc(): Boolean = best(MediaFormat.MIMETYPE_VIDEO_HEVC)?.hardware == true

    private fun describe(info: MediaCodecInfo, type: String): VideoEncoderInfo {
        val capabilities = info.getCapabilitiesForType(type)
        // Null for codecs that are not video encoders (annotated so from Android 17's SDK).
        val video = capabilities.videoCapabilities
        val encoder = capabilities.encoderCapabilities
        fun supports(width: Int, height: Int, fps: Int): Boolean = try {
            video?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
        } catch (e: IllegalArgumentException) {
            false
        }
        return VideoEncoderInfo(
            name = info.name,
            mimeType = type,
            hardware = info.isHardwareAccelerated,
            supportsVbr = encoder?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true,
            supportsCbr = encoder?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true,
            supports1080p30 = supports(1920, 1080, 30),
            supports1080p60 = supports(1920, 1080, 60),
            supports4k30 = supports(3840, 2160, 30),
        )
    }
}
