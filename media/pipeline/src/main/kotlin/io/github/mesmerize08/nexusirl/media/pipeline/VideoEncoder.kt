package io.github.mesmerize08.nexusirl.media.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

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

    /** The bitrate set ([setBitrate]); while the picture is still the encoder runs at less ([appliedBitrate]). */
    val currentBitrate: Long
        get() = encoderBitrate.requested

    /** Whether the encoder runs below [currentBitrate] for a still picture ([setStillPicture]). */
    val stillPicture: Boolean
        get() = encoderBitrate.reduced

    /** The bitrate the encoder was last given successfully. */
    @Volatile
    var appliedBitrate: Long = settings.bitrate
        private set

    /**
     * Parameter changes, in order: MediaCodec.setParameters is a binder call, which callers on the
     * GL thread (still picture) must not wait for.
     */
    private val parameterExecutor = Executors.newSingleThreadExecutor { Thread(it, "video-encoder-parameters") }

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

    // Declared after [rateControl], which it reads.
    private val encoderBitrate = EncoderBitrate(settings.bitrate, reduceWhenStill = rateControl == VideoRateControl.CBR)

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
        encoderBitrate.requested = bitrate
        updateBitrate()
    }

    /**
     * Whether the picture is a repeated still ([StillPicture]); a CBR encoder then runs at a
     * fraction of its bitrate ([EncoderBitrate]). Does not wait for the encoder.
     */
    fun setStillPicture(still: Boolean) {
        encoderBitrate.still = still
        updateBitrate()
    }

    private fun updateBitrate() {
        try {
            parameterExecutor.execute {
                val bitrate = encoderBitrate.target
                if (bitrate == appliedBitrate || !running) {
                    return@execute
                }
                try {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate.toInt()) })
                    appliedBitrate = bitrate
                } catch (e: IllegalStateException) {
                    // Encoder released concurrently.
                }
            }
        } catch (e: RejectedExecutionException) {
            // Released.
        }
    }

    /**
     * Whether the next frame should be handed to the encoder; false (and counted) while its output
     * over the last second is above the data rate limit. Called by the pipeline for each frame.
     */
    fun wantsFrame(): Boolean {
        val limiter = dataRateLimiter ?: return true
        if (limiter.allows(System.nanoTime(), appliedBitrate)) {
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
                    dataRateLimiter?.add(System.nanoTime(), appliedBitrate, info.size)
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

    private var released = false

    /**
     * Once only, and one caller at a time: the pipeline may release an encoder it still holds while
     * it stops, as its owner stops it from the capture-control thread.
     */
    @Synchronized
    fun release() {
        if (released) {
            return
        }
        released = true
        // Flush first: with B-frames the encoder holds the last frames back for reordering, and a
        // recording would lose them. The output thread writes them and ends at end-of-stream.
        try {
            codec.signalEndOfInputStream()
            outputThread.join(1_000)
        } catch (e: IllegalStateException) {
            // Not running.
        }
        running = false
        // A bitrate change under way must finish before the codec stops.
        parameterExecutor.shutdown()
        parameterExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)
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
