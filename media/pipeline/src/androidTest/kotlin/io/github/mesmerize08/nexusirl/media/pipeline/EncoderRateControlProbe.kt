package io.github.mesmerize08.nexusirl.media.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Measures how a phone's hardware encoder holds its bitrate (field test 2026-11-01: the S26's HEVC
 * encoder sent 7-14 Mbps for 0.25-5 Mbps targets). Feeds a test picture at a start bitrate for 6 s,
 * then a lower one for 8 s, and logs the output per second (logcat tag "EncoderProbe"). Does
 * nothing unless asked, so it never slows down other device tests:
 *
 *     adb install -t media/pipeline/build/outputs/apk/androidTest/debug/pipeline-debug-androidTest.apk
 *     adb shell am instrument -w -e probe raw|limited [-e content noise|blocks|pan|shake]
 *         [-e modes VBR,CBR] [-e start 5000000] [-e low 250000] [-e mime avc] [-e int.<key> <value>]
 *         -e class io.github.mesmerize08.nexusirl.media.pipeline.EncoderRateControlProbe
 *         io.github.mesmerize08.nexusirl.media.pipeline.test/androidx.test.runner.AndroidJUnitRunner
 *
 * "raw" configures MediaCodec directly for each mode; "limited" runs [VideoEncoder] as streams use
 * it (CBR with the [DataRateLimiter]). On the S26 (c2.qti.hevc.encoder), "blocks" makes the encoder
 * give up on its target (about 8.4 Mbps for any target of 2 Mbps or less) in CBR and VBR alike,
 * while the limited encoder stays within 1.2x; "pan" and "shake" are held to target by CBR alone.
 */
@RunWith(AndroidJUnit4::class)
class EncoderRateControlProbe {
    private val width = 1920
    private val height = 1080
    private val fps = 30

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun probe() {
        if (args.getString("probe") != "raw") {
            return
        }
        // adb shell am instrument -e mime avc -e content blocks -e modes CBR -e qpMax 51 ...
        val mime = if (args.getString("mime") == "avc") MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
        val info = EncoderCapabilities.best(mime) ?: return
        val allModes = mapOf(
            "VBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            "CBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            "CBR_FD" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD,
        )
        val modes = args.getString("modes")?.split(',') ?: allModes.keys.toList()
        val contents = args.getString("content")?.split(',') ?: listOf("noise", "blocks")
        val options = Options(
            start = args.getString("start")?.toInt() ?: 5_000_000,
            low = args.getString("low")?.toInt() ?: 250_000,
            qpMax = args.getString("qpMax")?.toInt(),
            // -e int.<format key> <value> sets any other integer format key.
            extra = args.keySet().filter { it.startsWith("int.") }.associate { it.removePrefix("int.") to args.getString(it)!!.toInt() },
        )
        val caps = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .first { it.name == info.name }.getCapabilitiesForType(mime).encoderCapabilities ?: return
        for (content in contents) {
            for (name in modes) {
                val mode = allModes.getValue(name)
                if (!caps.isBitrateModeSupported(mode)) {
                    Log.i(TAG, "${info.name} $name not supported")
                    continue
                }
                run(info.name, mime, name, mode, content, options)
            }
        }
    }

    private data class Options(val start: Int, val low: Int, val qpMax: Int?, val extra: Map<String, Int> = emptyMap())

    private fun run(codecName: String, mime: String, modeName: String, mode: Int, content: String, options: Options) {
        val codec = MediaCodec.createByCodecName(codecName)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, options.start)
            options.qpMax?.let { setInteger(MediaFormat.KEY_VIDEO_QP_MAX, it) }
            options.extra.forEach { (key, value) -> setInteger(key, value) }
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        Log.i(TAG, "input format ${codec.inputFormat}")
        if (args.getString("vendor") != null) {
            for (name in codec.supportedVendorParameters) {
                val description = codec.getParameterDescriptor(name)
                Log.i(TAG, "vendor $name type=${description?.type}")
            }
        }
        val bytesPerSecond = LongArray(14)
        val keyFramesPerSecond = IntArray(14)
        val start = System.nanoTime()
        val drain = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val index = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: IllegalStateException) {
                    return@Thread
                }
                if (index < 0) {
                    continue
                }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val second = ((System.nanoTime() - start) / 1_000_000_000).toInt()
                    if (second in bytesPerSecond.indices) {
                        bytesPerSecond[second] += bufferInfo.size.toLong()
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                            keyFramesPerSecond[second]++
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    return@Thread
                }
            }
        }.apply { start() }
        val picture = Picture(content)
        var lowered = false
        var frame = 0
        while (true) {
            val elapsed = System.nanoTime() - start
            if (elapsed >= 14_000_000_000L) {
                break
            }
            if (!lowered && elapsed >= 6_000_000_000L) {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, options.low) })
                lowered = true
            }
            picture.draw(surface, frame)
            frame++
            val next = start + frame * 1_000_000_000L / fps
            val wait = next - System.nanoTime()
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
        }
        codec.signalEndOfInputStream()
        drain.join(2_000)
        codec.stop()
        codec.release()
        surface.release()
        val kbps = bytesPerSecond.map { it * 8 / 1000 }
        Log.i(
            TAG,
            "$codecName $content $modeName $options frames=$frame kbps/s start:${kbps.take(
                6,
            )} low:${kbps.drop(6)} keyframes/s ${keyFramesPerSecond.toList()}",
        )
    }

    /** The test picture, drawn with a fixed seed so runs compare. */
    private inner class Picture(private val content: String) {
        private val random = Random(1)
        private val tile: Bitmap

        init {
            // Full-resolution noise; coarse blocks jumping around; or a detailed picture (smoothed noise
            // at several scales, like foliage and buildings) panning steadily, as from a moving car.
            tile = when (content) {
                "noise" -> Bitmap.createBitmap(width + 64, height + 64, Bitmap.Config.ARGB_8888)
                "pan", "shake" -> Bitmap.createBitmap(width * 3, height + 64, Bitmap.Config.ARGB_8888)
                else -> Bitmap.createBitmap(244, 140, Bitmap.Config.ARGB_8888)
            }
            if (content == "pan" || content == "shake") {
                val coarse = Bitmap.createScaledBitmap(
                    Bitmap.createBitmap(
                        IntArray(96 * 18) {
                            Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                        },
                        96,
                        18,
                        Bitmap.Config.ARGB_8888,
                    ),
                    tile.width,
                    tile.height,
                    true,
                )
                val fine = Bitmap.createScaledBitmap(
                    Bitmap.createBitmap(
                        IntArray(1440 * 270) {
                            Color.argb(90, random.nextInt(256), random.nextInt(256), random.nextInt(256))
                        },
                        1440,
                        270,
                        Bitmap.Config.ARGB_8888,
                    ),
                    tile.width,
                    tile.height,
                    true,
                )
                android.graphics.Canvas(tile).apply {
                    drawBitmap(coarse, 0f, 0f, null)
                    drawBitmap(fine, 0f, 0f, null)
                }
            } else {
                val pixels = IntArray(tile.width * tile.height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
                tile.setPixels(pixels, 0, tile.width, 0, 0, tile.width, tile.height)
            }
        }

        fun draw(surface: Surface, frame: Int) {
            val canvas = surface.lockHardwareCanvas()
            val dx = random.nextInt(64)
            val dy = random.nextInt(64)
            if (content == "pan") {
                canvas.drawBitmap(tile, -((frame * 12) % (width * 2)).toFloat(), 0f, null)
            } else if (content == "shake") {
                // The pan with a phone mount's vibration: the picture jumps a little every frame.
                canvas.drawBitmap(tile, -((frame * 12) % (width * 2) + dx / 3).toFloat(), -(dy / 3).toFloat(), null)
            } else if (content == "noise") {
                canvas.drawBitmap(tile, -dx.toFloat(), -dy.toFloat(), null)
            } else {
                canvas.drawBitmap(tile, Rect(dx / 8, dy / 8, dx / 8 + 240, dy / 8 + 135), Rect(0, 0, width, height), null)
            }
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * The real [VideoEncoder] with the stream settings for ABR (CBR and the data rate limit), fed
     * only the frames it wants, as the pipeline does. Logs output and held-back frames per second.
     */
    @Test
    fun limitedEncoder() {
        if (args.getString("probe") != "limited") {
            return
        }
        val content = args.getString("content") ?: "blocks"
        val start = args.getString("start")?.toLong() ?: 5_000_000
        val low = args.getString("low")?.toLong() ?: 250_000
        val bytesPerSecond = LongArray(14)
        val began = System.nanoTime()
        val sink = object : MediaSink {
            override fun setAudioConfig(config: io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig, sampleRate: Int) = Unit

            override fun setVideoConfig(config: io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig) = Unit

            override fun writeAudio(frame: ByteArray, presentationTimeStamp: io.github.mesmerize08.nexusirl.core.clock.MediaTime) = Unit

            override fun writeVideo(
                accessUnit: ByteArray,
                presentationTimeStamp: io.github.mesmerize08.nexusirl.core.clock.MediaTime,
                decodeTimeStamp: io.github.mesmerize08.nexusirl.core.clock.MediaTime?,
                isSync: Boolean,
            ) {
                val second = ((System.nanoTime() - began) / 1_000_000_000).toInt()
                if (second in bytesPerSecond.indices) {
                    bytesPerSecond[second] += accessUnit.size.toLong()
                }
            }
        }
        val settings = VideoEncoderSettings(
            width,
            height,
            fps,
            start,
            hevc = args.getString("mime") != "avc",
            rateControl = VideoRateControl.CBR,
            limitDataRate = true,
        )
        val encoder = VideoEncoder(settings, sink)
        val picture = Picture(content)
        val limitedPerSecond = LongArray(14)
        var frame = 0
        while (true) {
            val elapsed = System.nanoTime() - began
            if (elapsed >= 14_000_000_000L) {
                break
            }
            if (elapsed >= 6_000_000_000L) {
                encoder.setBitrate(low)
            }
            if (encoder.wantsFrame()) {
                picture.draw(encoder.inputSurface, frame)
            } else {
                limitedPerSecond[(elapsed / 1_000_000_000).toInt()]++
            }
            frame++
            val wait = began + frame * 1_000_000_000L / fps - System.nanoTime()
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
        }
        encoder.release()
        val kbps = bytesPerSecond.map { it * 8 / 1000 }
        Log.i(
            TAG,
            "VideoEncoder ${encoder.info?.name} ${encoder.rateControl} $content $start->$low kbps/s start:${kbps.take(
                6,
            )} low:${kbps.drop(6)} held back/s ${limitedPerSecond.toList()}",
        )
    }

    private companion object {
        const val TAG = "EncoderProbe"
    }
}
