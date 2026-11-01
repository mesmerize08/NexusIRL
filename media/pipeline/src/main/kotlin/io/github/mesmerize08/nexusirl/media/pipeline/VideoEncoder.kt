package io.github.mesmerize08.nexusirl.media.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig

/** How the encoder spends its bitrate (upstream `SettingsStreamRateControl`, minus ABR; see [VideoEncoderSettings.rateControl]). */
enum class VideoRateControl { CBR, VBR }

/** Video encoder settings (subset of upstream `VideoEncoderSettings`). */
data class VideoEncoderSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Long,
    val hevc: Boolean = false,
    /** Upstream default `maxKeyFrameInterval` is 2 seconds. */
    val keyFrameIntervalSeconds: Int = 2,
    /**
     * Frame reordering (B-frames): better quality per bit, but output comes in decode order and a
     * little later. Upstream streams without them by default; its recordings (AVAssetWriter) use them.
     */
    val bFrames: Boolean = false,
    /**
     * Upstream's default ABR is an average bitrate with a hard 1.2x-per-second limit. MediaCodec's
     * VBR has no limit and bursts to twice its target on some phones, so ABR streams use CBR and
     * [limitDataRate]. Falls back to VBR when the encoder has no CBR.
     */
    val rateControl: VideoRateControl = VideoRateControl.VBR,
    /** Holds frames back while the output is over 1.2x the bitrate ([DataRateLimiter]). */
    val limitDataRate: Boolean = false,
)

/**
 * Hardware video encoder with Surface input (upstream `VideoEncoder` on VTCompressionSession).
 * Output is Annex-B; the codec-config buffer becomes the SPS/PPS(/VPS) config for the sinks.
 */
class VideoEncoder(private val settings: VideoEncoderSettings, private val sink: MediaSink) {
    private val mimeType = if (settings.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    /** The encoder in use (hardware preferred), from [EncoderCapabilities]. */
    val info: VideoEncoderInfo? = EncoderCapabilities.best(mimeType)
    private val codec: MediaCodec = info?.let { MediaCodec.createByCodecName(it.name) } ?: MediaCodec.createEncoderByType(mimeType)
    val inputSurface: Surface
    private val outputThread: Thread

    @Volatile
    private var running = true

    @Volatile
    var currentBitrate: Long = settings.bitrate
        private set

    @Volatile
    var encodedFrames = 0L
        private set

    /** Frames not encoded because the output was over its limit ([VideoEncoderSettings.limitDataRate]). */
    @Volatile
    var limitedFrames = 0L
        private set

    private val dataRateLimiter = if (settings.limitDataRate) DataRateLimiter() else null

    /** The bitrate mode in use, which may differ from the one asked for when it is unsupported. */
    val rateControl = if (settings.rateControl == VideoRateControl.CBR &&
        info?.supportsCbr != false
    ) {
        VideoRateControl.CBR
    } else {
        VideoRateControl.VBR
    }

    init {
        val format = MediaFormat.createVideoFormat(mimeType, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameIntervalSeconds)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (rateControl == VideoRateControl.CBR) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                },
            )
            // 0 = real-time priority (the old spec had this inverted).
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, settings.fps)
            // MediaFormat.KEY_MAX_B_FRAMES; encoders without B-frame support ignore it.
            setInteger("max-bframes", if (settings.bFrames) 1 else 0)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            // Hardware encoder instances are few: give this one back before reporting the failure.
            codec.release()
            throw e
        }
        outputThread = Thread({ drain() }, "video-encoder").apply { start() }
    }

    /** Changes the bitrate on the fly (adaptive bitrate). */
    fun setBitrate(bitrate: Long) {
        if (bitrate == currentBitrate || !running) {
            return
        }
        currentBitrate = bitrate
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate.toInt()) })
        } catch (e: IllegalStateException) {
            // Encoder released concurrently.
        }
    }

    /**
     * Whether the next frame should be handed to the encoder; false (and counted) while its output
     * over the last second is above the data rate limit. Called by the pipeline for each frame.
     */
    fun wantsFrame(): Boolean {
        val limiter = dataRateLimiter ?: return true
        if (limiter.allows(System.nanoTime(), currentBitrate)) {
            return true
        }
        limitedFrames++
        return false
    }

    /** Requests an IDR frame as soon as possible (e.g. when a new destination connects). */
    fun requestKeyFrame() {
        if (!running) {
            return
        }
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // Encoder released concurrently.
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                return
            }
            if (index < 0) {
                continue
            }
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    sink.setVideoConfig(parseConfig(data))
                } else {
                    encodedFrames++
                    dataRateLimiter?.add(System.nanoTime(), currentBitrate, info.size)
                    sink.writeVideo(
                        data,
                        MediaTime(info.presentationTimeUs, 1_000_000),
                        null,
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                    )
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                return
            }
        }
    }

    private fun parseConfig(data: ByteArray): MpegTsVideoConfig {
        val nalUnits = getNalUnits(data).map { data.copyOfRange(it.dataOffset(), it.dataOffset() + it.dataLength) }
        return if (settings.hevc) {
            fun find(type: Int) = nalUnits.firstOrNull { ((it[0].toInt() ushr 1) and 0x3F) == type }
            MpegTsVideoConfig.Hevc(find(32), find(33), find(34))
        } else {
            fun find(type: Int) = nalUnits.firstOrNull { (it[0].toInt() and 0x1F) == type }
            MpegTsVideoConfig.Avc(find(7), find(8))
        }
    }

    fun release() {
        // Flush first: with B-frames the encoder holds the last frames back for reordering, and a
        // recording would lose them. The output thread writes them and ends at end-of-stream.
        try {
            codec.signalEndOfInputStream()
            outputThread.join(1_000)
        } catch (e: IllegalStateException) {
            // Not running.
        }
        running = false
        outputThread.join(1_000)
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        codec.release()
        inputSurface.release()
    }
}
