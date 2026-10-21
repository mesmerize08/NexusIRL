package io.github.mesmerize08.nexusirl.media.pipeline

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig

/** Video encoder settings (subset of upstream `VideoEncoderSettings`). */
data class VideoEncoderSettings(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Long,
    val hevc: Boolean = false,
    /** Upstream default `maxKeyFrameInterval` is 2 seconds. */
    val keyFrameIntervalSeconds: Int = 2,
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

    init {
        val format = MediaFormat.createVideoFormat(mimeType, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // 0 = real-time priority (the old spec had this inverted).
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, settings.fps)
            // No frame reordering (upstream default: B-frames off).
            setInteger("max-bframes", 0)
            // Re-send the last frame if the input surface stalls, so the stream keeps its cadence.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / settings.fps * 2)
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
