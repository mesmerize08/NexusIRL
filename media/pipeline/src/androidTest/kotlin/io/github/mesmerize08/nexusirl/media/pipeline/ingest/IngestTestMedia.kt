package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.graphics.SurfaceTexture
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.mesmerize08.nexusirl.media.mux.nal.annexBToLengthPrefixed
import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A test stream as an RTMP publisher sends it: an avcC/hvcC record and length-prefixed access units. */
class EncodedStream(val codec: IngestVideoCodec, val config: ByteArray, val units: List<IngestVideoAccessUnit>)

/** Encodes a moving test pattern with the device's encoder, with exact presentation times. */
object TestEncoder {
    fun has(codec: IngestVideoCodec): Boolean = EncoderCapabilities.best(codec.mimeType) != null

    /**
     * [frames] frames at 30 fps, a key frame every second. Each unit's PTS is the encoder's (whole
     * microseconds) plus 7 ns, so a test sees whether the decoder keeps nanoseconds.
     */
    fun encode(codec: IngestVideoCodec, width: Int, height: Int, frames: Int = 60, startMicros: Long = 1_000_000): EncodedStream {
        val info = EncoderCapabilities.best(codec.mimeType) ?: error("no ${codec.mimeType} encoder")
        val encoder = MediaCodec.createByCodecName(info.name)
        val format = MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        var parameterSets: ByteArray? = null
        val units = ArrayList<IngestVideoAccessUnit>()
        val info2 = MediaCodec.BufferInfo()
        var queued = 0
        var done = false
        val deadline = System.nanoTime() + 60_000_000_000
        try {
            while (!done) {
                check(System.nanoTime() < deadline) { "encoder timed out after ${units.size} units" }
                if (queued <= frames) {
                    val index = encoder.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val pts = startMicros + queued * 1_000_000L / 30
                        if (queued == frames) {
                            encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            fill(encoder.getInputImage(index)!!, queued)
                            encoder.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
                        }
                        queued++
                    }
                }
                val index = encoder.dequeueOutputBuffer(info2, 5_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = ByteArrayOutputStream()
                    for (key in listOf("csd-0", "csd-1", "csd-2")) {
                        val buffer = encoder.outputFormat.getByteBuffer(key) ?: continue
                        output.write(ByteArray(buffer.remaining()).also { buffer.get(it) })
                    }
                    parameterSets = output.toByteArray()
                } else if (index >= 0) {
                    val buffer = encoder.getOutputBuffer(index)!!
                    buffer.position(info2.offset)
                    val bytes = ByteArray(info2.size).also { buffer.get(it) }
                    if (info2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        parameterSets = parameterSets ?: bytes
                    } else if (bytes.isNotEmpty()) {
                        units += IngestVideoAccessUnit(
                            isKeyFrame = info2.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                            ptsNanos = info2.presentationTimeUs * 1000 + 7,
                            dtsNanos = info2.presentationTimeUs * 1000 + 7,
                            data = annexBToLengthPrefixed(bytes),
                        )
                    }
                    encoder.releaseOutputBuffer(index, false)
                    done = info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        } finally {
            encoder.stop()
            encoder.release()
        }
        val nalUnits = splitAnnexB(checkNotNull(parameterSets) { "encoder gave no parameter sets" })
        val config = when (codec) {
            IngestVideoCodec.H264 -> avcC(
                sps = nalUnits.first { it[0].toInt() and 0x1F == 7 },
                pps = nalUnits.first { it[0].toInt() and 0x1F == 8 },
            )
            IngestVideoCodec.H265 -> hvcC(nalUnits)
        }
        return EncodedStream(codec, config, units)
    }

    private fun fill(image: Image, frame: Int) {
        for ((plane, value) in image.planes.withIndex()) {
            val subsampling = if (plane == 0) 1 else 2
            val width = image.width / subsampling
            val height = image.height / subsampling
            val buffer = value.buffer
            val row = ByteArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    row[x] = if (plane == 0) (x + 2 * y + 8 * frame).toByte() else (128 + plane * 20 + (x / 16 + frame) % 32).toByte()
                }
                if (value.pixelStride == 1) {
                    buffer.position(y * value.rowStride)
                    buffer.put(row)
                } else {
                    for (x in 0 until width) {
                        buffer.put(y * value.rowStride + x * value.pixelStride, row[x])
                    }
                }
            }
        }
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> =
        getNalUnits(data).map { data.copyOfRange(it.dataOffset(), it.dataOffset() + it.dataLength) }

    /** An avcC record with 4-byte NAL lengths. */
    fun avcC(sps: ByteArray, pps: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte()))
        write16(sps.size)
        write(sps)
        write(1)
        write16(pps.size)
        write(pps)
    }.toByteArray()

    /** An hvcC record with 4-byte NAL lengths (the profile fields are left zero: only parsing reads it). */
    fun hvcC(nalUnits: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        val header = ByteArray(22)
        header[0] = 1
        header[21] = 0x0F
        write(header)
        val arrays = listOf(32, 33, 34).map { type -> type to nalUnits.filter { (it[0].toInt() shr 1) and 0x3F == type } }
        write(arrays.size)
        for ((type, units) in arrays) {
            write(0x80 or type)
            write16(units.size)
            for (unit in units) {
                write16(unit.size)
                write(unit)
            }
        }
    }.toByteArray()

    private fun ByteArrayOutputStream.write16(value: Int) {
        write(value ushr 8)
        write(value and 0xFF)
    }
}

/**
 * A [SurfaceTexture] with its own GL context on its own thread, which takes every frame queued to
 * [surface] and records its timestamp (nanoseconds) in [timestamps].
 */
class TextureSink : AutoCloseable {
    private val thread = HandlerThread("texture-sink").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var eglSurface: EGLSurface
    private lateinit var texture: SurfaceTexture
    lateinit var surface: Surface
        private set
    val timestamps = LinkedBlockingQueue<Long>()

    init {
        onThread {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            eglSurface =
                EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = SurfaceTexture(textures[0])
            texture.setOnFrameAvailableListener({
                it.updateTexImage()
                timestamps.add(it.timestamp)
            }, handler)
            surface = Surface(texture)
        }
    }

    private fun onThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                block()
            } catch (e: Throwable) {
                error = e
            }
            done.countDown()
        }
        check(done.await(10, TimeUnit.SECONDS))
        error?.let { throw it }
    }

    override fun close() {
        onThread {
            surface.release()
            texture.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
        }
        thread.quitSafely()
    }
}
