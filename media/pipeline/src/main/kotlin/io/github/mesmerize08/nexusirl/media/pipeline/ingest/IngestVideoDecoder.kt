package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.mesmerize08.nexusirl.media.mux.nal.lengthPrefixedToAnnexB
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig

enum class IngestVideoCodec(val mimeType: String) { H264("video/avc"), H265("video/hevc") }

/**
 * Decodes an ingested (RTMP) H.264 or HEVC stream into a [Surface] with [MediaCodec] in async
 * mode. Decoded frames wait in the decoder until [release]d, so the caller decides when each one is
 * shown (or dropped).
 *
 * A new [configure] with a different codec or config recreates the codec: a new generation, and
 * frames of the old one are ignored by [release]. With [softwareDecoding] the first software-only
 * decoder is used; otherwise the default decoder, falling back to software once if it cannot be
 * configured or started, or reports an error before its first decoded frame. That fallback keeps
 * the config and surface; units queued since the last key frame are lost, so the picture returns at
 * the next key frame.
 *
 * Untrusted input never throws: an access unit that is not valid length-prefixed data is dropped,
 * and anything else that goes wrong (a codec error, a bad config, a picture larger than 3840×2160
 * in either orientation or than the decoder supports, a [setSurface] the decoder refuses) ends the
 * decoder with [Listener.onFailed]; after that every call is a no-op.
 *
 * Threading: the public methods may be called from any thread (they are serialised internally).
 * Every [Listener] callback comes on the decoder's own thread, "ingest-video-<name>", and none
 * comes after [close] returns (close waits for a callback in progress, unless called from one).
 */
class IngestVideoDecoder(private val name: String, private val softwareDecoding: Boolean, private val listener: Listener) {
    interface Listener {
        /** A decoded frame waits in the decoder: release it with [release] (render or drop). */
        fun onFrame(frame: DecodedFrame)

        /** The decoded picture size (cropped), when it is first known and whenever it changes. */
        fun onFormat(width: Int, height: Int)

        /** The decoder ended (codec error, size over 3840×2160, surface refused); the source should end. */
        fun onFailed(reason: String)
    }

    class DecodedFrame(val ptsNanos: Long, internal val index: Int, internal val generation: Int) {
        internal var released = false
    }

    private val lock = Any()

    /** Held while a listener callback runs, so [close] can wait for it. Taken before [lock], never inside it. */
    private val deliveryLock = Any()
    private val thread = HandlerThread("ingest-video-$name").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var generation = 0
    private var currentCodec: IngestVideoCodec? = null
    private var currentConfig: ByteArray? = null
    private var format: MediaFormat? = null
    private var surface: Surface? = null
    private var nalLengthSize = 4
    private val freeInputs = ArrayDeque<Int>()
    private val held = HashSet<Int>()
    private var reportedWidth = 0
    private var reportedHeight = 0
    private var fellBack = false
    private var frameSeen = false

    /** Microsecond PTS (what MediaCodec keeps) to the unit's nanosecond PTS. */
    private val ptsNanos = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > MAX_PENDING_PTS
    }
    private var failed = false
    private var closed = false
    private var loggedBadUnit = false

    /** The name of the running codec, or null. */
    val codecName: String?
        get() = synchronized(lock) { guarded { codec?.name } }

    /** Decoded frames not yet released. */
    val heldFrames: Int
        get() = synchronized(lock) { held.size }

    /** Starts decoding [config] (an avcC or hvcC record) into [surface]; the same config again is ignored. */
    fun configure(codec: IngestVideoCodec, config: ByteArray, surface: Surface) {
        synchronized(lock) {
            if (failed || closed) return
            if (codec == currentCodec && config.contentEquals(currentConfig)) return
            stopCodec()
            currentCodec = codec
            currentConfig = config.copyOf()
            val (format, lengthSize) = createFormat(codec, config) ?: run {
                fail("Invalid ${if (codec == IngestVideoCodec.H264) "avcC" else "hvcC"} video config")
                return
            }
            nalLengthSize = lengthSize
            this.format = format
            this.surface = surface
            fellBack = false
            if (softwareDecoding) {
                val software = softwareDecoderName(codec)
                if (software == null) {
                    fail("No software ${codec.mimeType} decoder")
                    return
                }
                start(software)?.let { fail(describe(it.second)) }
            } else {
                start(null)?.let { (tried, error) -> fallBack(tried, describe(error)) }
            }
        }
    }

    /**
     * Moves the output to [surface] (the pipeline was re-created) without restarting decoding. If
     * the decoder refuses it, the decoder ends ([Listener.onFailed]) and the source should end.
     */
    fun setSurface(surface: Surface) {
        synchronized(lock) {
            if (failed || closed) return
            this.surface = surface
            val codec = codec ?: return
            guardedOrFail { codec.setOutputSurface(surface) }
        }
    }

    /** True when an input buffer is free, so [queue] decodes the unit. */
    fun canQueue(): Boolean = synchronized(lock) { codec != null && freeInputs.isNotEmpty() }

    /** Queues one access unit (length-prefixed NAL units); call only when [canQueue]. */
    fun queue(unit: IngestVideoAccessUnit) {
        synchronized(lock) {
            val codec = codec ?: return
            val index = freeInputs.firstOrNull() ?: return
            val annexB = lengthPrefixedToAnnexB(unit.data, nalLengthSize)
            if (annexB == null) {
                logBadUnit("an access unit that is not valid length-prefixed data")
                return
            }
            guardedOrFail {
                val buffer = codec.getInputBuffer(index) ?: error("no input buffer $index")
                if (annexB.size > buffer.capacity()) {
                    logBadUnit("an access unit of ${annexB.size} bytes (input buffer ${buffer.capacity()})")
                    return@guardedOrFail
                }
                buffer.clear()
                buffer.put(annexB)
                val ptsMicros = unit.ptsNanos / 1000
                ptsNanos[ptsMicros] = unit.ptsNanos
                freeInputs.removeFirst()
                codec.queueInputBuffer(index, 0, annexB.size, ptsMicros, 0)
            }
        }
    }

    /** Renders or drops a decoded frame. Idempotent; frames of an older generation are ignored. */
    fun release(frame: DecodedFrame, render: Boolean) {
        synchronized(lock) {
            if (frame.released || frame.generation != generation) return
            frame.released = true
            if (!held.remove(frame.index)) return
            val codec = codec ?: return
            guardedOrFail { codec.releaseOutputBuffer(frame.index, render) }
        }
    }

    /** Releases the codec. No listener callback comes after this returns. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stopCodec()
        }
        // Waits for a callback in progress (re-entrant when close is called from one).
        synchronized(deliveryLock) {}
        thread.quitSafely()
    }

    // Everything below runs with the lock held, except deliver().

    private fun createFormat(codec: IngestVideoCodec, config: ByteArray): Pair<MediaFormat, Int>? {
        if (config.isEmpty() || config[0].toInt() != 1) return null
        val format = MediaFormat.createVideoFormat(codec.mimeType, 1920, 1080)
        val lengthSize: Int
        when (codec) {
            IngestVideoCodec.H264 -> {
                lengthSize = MpegTsVideoConfig.Avc.nalLengthSize(config) ?: return null
                val parsed = MpegTsVideoConfig.Avc.fromAvcC(config)
                val sps = parsed.sequenceParameterSet?.takeIf { it.isNotEmpty() && it[0].toInt() and 0x1F == 7 } ?: return null
                val pps = parsed.pictureParameterSet?.takeIf { it.isNotEmpty() && it[0].toInt() and 0x1F == 8 } ?: return null
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(withStartCodes(sps)))
                format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(withStartCodes(pps)))
            }
            IngestVideoCodec.H265 -> {
                lengthSize = MpegTsVideoConfig.Hevc.nalLengthSize(config) ?: return null
                val parsed = MpegTsVideoConfig.Hevc.fromHvcC(config)
                fun ByteArray?.ofType(type: Int) = this?.takeIf { it.size >= 2 && (it[0].toInt() shr 1) and 0x3F == type }
                val vps = parsed.videoParameterSet.ofType(32) ?: return null
                val sps = parsed.sequenceParameterSet.ofType(33) ?: return null
                val pps = parsed.pictureParameterSet.ofType(34) ?: return null
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(withStartCodes(vps, sps, pps)))
            }
        }
        // A hint only: the real limit is checked when the decoder reports the size.
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, MAX_WIDTH)
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, MAX_HEIGHT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return format to lengthSize
    }

    /**
     * Creates, configures and starts a codec ([decoderName], or the default for the type) with the
     * current format and surface; on failure returns the decoder tried and the error.
     */
    private fun start(decoderName: String?): Pair<String?, Throwable>? {
        val type = checkNotNull(currentCodec)
        var created: MediaCodec? = null
        return try {
            val codec = decoderName?.let { MediaCodec.createByCodecName(it) } ?: MediaCodec.createDecoderByType(type.mimeType)
            created = codec
            generation++
            codec.setCallback(Callback(generation), handler)
            this.codec = codec
            codec.configure(checkNotNull(format), checkNotNull(surface), null, 0)
            codec.start()
            Log.i(TAG, "$name: decoding ${type.mimeType} with ${codec.name}")
            null
        } catch (e: Throwable) {
            val tried = decoderName ?: guarded { created?.name }
            stopCodec()
            guarded { created?.release() }
            tried to e
        }
    }

    /**
     * The default decoder [failedName] failed ([reason]): moves to the first software decoder, once
     * per [configure], or ends the decoder.
     */
    private fun fallBack(failedName: String?, reason: String) {
        val fallback = if (softwareDecoding || fellBack) null else softwareDecoderName(checkNotNull(currentCodec))
        if (fallback == null || fallback == failedName) {
            fail(reason)
            return
        }
        fellBack = true
        Log.w(TAG, "$name: $failedName failed ($reason), using $fallback")
        stopCodec()
        start(fallback)?.let { fail(describe(it.second)) }
    }

    private fun stopCodec() {
        val codec = codec ?: return
        this.codec = null
        generation++
        freeInputs.clear()
        held.clear()
        ptsNanos.clear()
        reportedWidth = 0
        reportedHeight = 0
        frameSeen = false
        guarded { codec.release() }
    }

    private fun fail(reason: String) {
        if (failed || closed) return
        failed = true
        Log.w(TAG, "$name: $reason")
        stopCodec()
        handler.post {
            try {
                deliver { listener.onFailed(reason) }
            } catch (e: Throwable) {
                Log.e(TAG, "$name: onFailed threw", e)
            }
        }
    }

    /** Runs a listener callback (on the decoder thread, without [lock]) unless the decoder is closed; [close] waits for it. */
    private inline fun deliver(block: () -> Unit) {
        synchronized(deliveryLock) {
            if (synchronized(lock) { closed }) return
            block()
        }
    }

    private inline fun guardedOrFail(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            fail(describe(e))
        }
    }

    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        null
    }

    private fun logBadUnit(what: String) {
        if (!loggedBadUnit) {
            loggedBadUnit = true
            Log.w(TAG, "$name: dropped $what (logged once)")
        }
    }

    /**
     * The cropped output size when it changed (to report), else null. A size over the limit, or one
     * that [codec] does not support, ends the decoder.
     */
    private fun reportFormat(codec: MediaCodec, format: MediaFormat): Pair<Int, Int>? {
        fun int(key: String) = if (format.containsKey(key)) format.getInteger(key) else null
        val left = int("crop-left")
        val right = int("crop-right")
        val top = int("crop-top")
        val bottom = int("crop-bottom")
        val codedWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val codedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        val width = if (left != null && right != null) right - left + 1 else codedWidth
        val height = if (top != null && bottom != null) bottom - top + 1 else codedHeight
        if (!isSizeAllowed(width, height)) {
            fail("Video size $width×$height is too large")
            return null
        }
        if (width == reportedWidth && height == reportedHeight) return null
        val video = codec.codecInfo.getCapabilitiesForType(checkNotNull(currentCodec).mimeType).videoCapabilities
        // The coded size counts too: a decoder's alignment rule may refuse the cropped size it decodes.
        if (video != null && !video.isSizeSupported(width, height) && !video.isSizeSupported(codedWidth, codedHeight)) {
            fail("Video size $width×$height is not supported by ${codec.name}")
            return null
        }
        reportedWidth = width
        reportedHeight = height
        return width to height
    }

    private inner class Callback(private val generation: Int) : MediaCodec.Callback() {
        /** Runs [block] with the lock held if this codec is still the current one; anything thrown ends the decoder. */
        private inline fun <T> current(block: () -> T): T? = synchronized(lock) {
            if (generation != this@IngestVideoDecoder.generation || failed || closed) return null
            try {
                block()
            } catch (e: Throwable) {
                fail(describe(e))
                null
            }
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            current { freeInputs.addLast(index) }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            var size: Pair<Int, Int>? = null
            val frame = current {
                if (reportedWidth == 0) size = reportFormat(codec, codec.outputFormat)
                if (failed) return@current null
                frameSeen = true
                held.add(index)
                val pts = ptsNanos.remove(info.presentationTimeUs) ?: (info.presentationTimeUs * 1000)
                DecodedFrame(pts, index, generation)
            }
            size?.let { (width, height) -> guardedDeliver { listener.onFormat(width, height) } }
            frame?.let { guardedDeliver { listener.onFrame(it) } }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            current { reportFormat(codec, format) }?.let { (width, height) -> guardedDeliver { listener.onFormat(width, height) } }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            current {
                // Before its first frame a default decoder may just not handle this stream: software, once.
                if (frameSeen) fail(describe(e)) else fallBack(guarded { codec.name }, describe(e))
            }
        }

        private inline fun guardedDeliver(block: () -> Unit) {
            try {
                deliver(block)
            } catch (e: Throwable) {
                synchronized(lock) { fail("Listener: ${describe(e)}") }
            }
        }
    }

    internal companion object {
        private const val TAG = "IngestVideoDecoder"
        private const val MAX_WIDTH = 3840
        private const val MAX_HEIGHT = 2160
        private const val MAX_PENDING_PTS = 64

        /** At most 3840×2160 in either orientation (landscape or portrait). */
        fun isSizeAllowed(width: Int, height: Int): Boolean = maxOf(width, height) <= MAX_WIDTH && minOf(width, height) <= MAX_HEIGHT

        private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"

        private fun withStartCodes(vararg nalUnits: ByteArray): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            for (nalUnit in nalUnits) {
                output.write(byteArrayOf(0, 0, 0, 1))
                output.write(nalUnit)
            }
            return output.toByteArray()
        }

        /** The first software-only decoder for the type. */
        private fun softwareDecoderName(codec: IngestVideoCodec): String? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.isSoftwareOnly && info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) }
            }?.name
        } catch (e: Throwable) {
            null
        }
    }
}
