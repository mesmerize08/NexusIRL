package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an ingested (RTMP) AAC stream with [MediaCodec] in async mode and hands the audio on as
 * 48 kHz, [outputChannels] (1 or 2) chunks of 1024 frames ([PcmResampler]), each with the
 * nanosecond pts of its first frame.
 *
 * [configure] takes the AudioSpecificConfig; its sample rate and channels configure the codec, and
 * the codec's output format then gives the real rate and channel count for the resampler (HE-AAC
 * doubles the rate). A different config recreates the codec; the same one again is ignored. More
 * than two decoded channels (5.1 and up, when the decoder does not downmix itself, which it is
 * asked to do) are downmixed to stereo as left = (front left + centre) / 2 and
 * right = (front right + centre) / 2, the centre being the third channel in the Android order.
 *
 * [queue]d frames wait while the codec has no free input buffer: at most 50, the oldest ones are
 * dropped beyond that, with the count logged at most once per second.
 *
 * Untrusted input never throws: an empty frame or one larger than the input buffer is dropped, and
 * anything else that goes wrong (an invalid config, a codec error, an output format it cannot use)
 * ends the decoder with [Listener.onFailed]; after that every call is a no-op.
 *
 * Threading: the public methods may be called from any thread (they are serialised internally).
 * Every [Listener] callback comes on the decoder's own thread, "ingest-audio-<name>", and none comes
 * after [close] returns (close waits for a callback in progress, unless called from one).
 */
class IngestAudioDecoder(private val name: String, private val outputChannels: Int, private val listener: Listener) {
    interface Listener {
        /** 1024 frames, 48 kHz, interleaved [outputChannels]; a new array the receiver may keep. */
        fun onChunk(pcm: ShortArray, ptsNanos: Long)

        /** The decoder ended (invalid config, codec error); the source should end. */
        fun onFailed(reason: String)
    }

    init {
        require(outputChannels in 1..2) { "outputChannels must be 1 or 2: $outputChannels" }
    }

    private class Frame(val data: ByteArray, val ptsNanos: Long)

    private val lock = Any()

    /** Held while a listener callback runs, so [close] can wait for it. Taken before [lock], never inside it. */
    private val deliveryLock = Any()
    private val thread = HandlerThread("ingest-audio-$name").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var generation = 0
    private var currentConfig: ByteArray? = null
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Frame>()
    private var dropped = 0
    private var dropLoggedAt = 0L

    /** The decoded format: rate, channels (any count) and PCM encoding; the resampler matches it. */
    private var decodedRate = 0
    private var decodedChannels = 0
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var resampler: PcmResampler? = null

    /** Microsecond PTS (what MediaCodec keeps) to the frame's nanosecond PTS. */
    private val ptsNanos = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>?): Boolean = size > MAX_PENDING_PTS
    }
    private var failed = false
    private var closed = false
    private var loggedBadFrame = false

    /** Starts decoding with [audioSpecificConfig]; the same config again is ignored. */
    fun configure(audioSpecificConfig: ByteArray) {
        synchronized(lock) {
            if (failed || closed) return
            if (audioSpecificConfig.contentEquals(currentConfig)) return
            stopCodec()
            currentConfig = audioSpecificConfig.copyOf()
            val format = createFormat(audioSpecificConfig) ?: run {
                fail("Invalid AudioSpecificConfig")
                return
            }
            start(format)?.let { fail(describe(it)) }
        }
    }

    /** Queues one raw AAC frame (no ADTS header); [aac] must not change afterwards. Ignored before [configure]. */
    fun queue(aac: ByteArray, ptsNanos: Long) {
        synchronized(lock) {
            val codec = codec ?: return
            pending.addLast(Frame(aac, ptsNanos))
            while (pending.size > MAX_PENDING_FRAMES) {
                pending.removeFirst()
                dropped++
            }
            logDrops()
            guardedOrFail { drain(codec) }
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

    private fun createFormat(config: ByteArray): MediaFormat? {
        val (sampleRate, channels) = parseAudioSpecificConfig(config) ?: return null
        return MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.copyOf()))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            // Lets the AAC decoder downmix multichannel audio itself (the key before API 30, and since).
            setInteger("aac-max-output-channel_count", 2)
            setInteger("max-output-channel-count", 2)
        }
    }

    /** Creates, configures and starts the default AAC decoder; on failure returns the error. */
    private fun start(format: MediaFormat): Throwable? {
        var created: MediaCodec? = null
        return try {
            val codec = MediaCodec.createDecoderByType(MIME)
            created = codec
            generation++
            codec.setCallback(Callback(generation), handler)
            this.codec = codec
            codec.configure(format, null, null, 0)
            codec.start()
            Log.i(TAG, "$name: decoding AAC with ${codec.name}")
            null
        } catch (e: Throwable) {
            stopCodec()
            guarded { created?.release() }
            e
        }
    }

    private fun stopCodec() {
        val codec = codec ?: return
        this.codec = null
        generation++
        freeInputs.clear()
        pending.clear()
        ptsNanos.clear()
        resampler = null
        decodedRate = 0
        decodedChannels = 0
        guarded { codec.release() }
    }

    /** Moves waiting frames into free input buffers. */
    private fun drain(codec: MediaCodec) {
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val frame = pending.removeFirst()
            val index = freeInputs.first()
            val buffer = codec.getInputBuffer(index) ?: error("no input buffer $index")
            if (frame.data.isEmpty() || frame.data.size > buffer.capacity()) {
                logBadFrame("a frame of ${frame.data.size} bytes (input buffer ${buffer.capacity()})")
                continue
            }
            buffer.clear()
            buffer.put(frame.data)
            val ptsMicros = Math.floorDiv(frame.ptsNanos, 1000L)
            ptsNanos[ptsMicros] = frame.ptsNanos
            freeInputs.removeFirst()
            codec.queueInputBuffer(index, 0, frame.data.size, ptsMicros, 0)
        }
    }

    /** Takes the decoder's output [format]; a format it cannot use ends the decoder. */
    private fun applyFormat(format: MediaFormat) {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        if (rate !in 1..MAX_SAMPLE_RATE || channels !in 1..MAX_CHANNELS) {
            fail("Unsupported decoded audio: $rate Hz, $channels channels")
            return
        }
        if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            fail("Unsupported decoded PCM encoding $encoding")
            return
        }
        pcmEncoding = encoding
        if (rate == decodedRate && channels == decodedChannels && resampler != null) return
        if (decodedRate != 0) Log.i(TAG, "$name: decoded audio now $rate Hz, $channels channels")
        decodedRate = rate
        decodedChannels = channels
        resampler = PcmResampler(rate, minOf(channels, 2), outputChannels)
    }

    /** The decoded samples of [buffer] as interleaved 16-bit mono or stereo (more channels downmixed). */
    private fun readPcm(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ShortArray {
        buffer.limit(info.offset + info.size)
        buffer.position(info.offset)
        buffer.order(ByteOrder.nativeOrder())
        val channels = decodedChannels
        val bytesPerSample = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frames = info.size / (channels * bytesPerSample)
        val all = ShortArray(frames * channels)
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            for (i in all.indices) all[i] = (floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        } else {
            buffer.asShortBuffer().get(all)
        }
        if (channels <= 2) return all
        val stereo = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            val base = frame * channels
            val centre = all[base + 2].toInt()
            stereo[frame * 2] = ((all[base].toInt() + centre) / 2).toShort()
            stereo[frame * 2 + 1] = ((all[base + 1].toInt() + centre) / 2).toShort()
        }
        return stereo
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

    private fun logBadFrame(what: String) {
        if (!loggedBadFrame) {
            loggedBadFrame = true
            Log.w(TAG, "$name: dropped $what (logged once)")
        }
    }

    private fun logDrops() {
        if (dropped == 0) return
        val now = SystemClock.elapsedRealtime()
        if (dropLoggedAt != 0L && now - dropLoggedAt < 1_000) return
        Log.w(TAG, "$name: dropped $dropped frames waiting for the decoder")
        dropped = 0
        dropLoggedAt = now
    }

    private inner class Callback(private val generation: Int) : MediaCodec.Callback() {
        /** Runs [block] with the lock held if this codec is still the current one; anything thrown ends the decoder. */
        private inline fun <T> current(block: () -> T): T? = synchronized(lock) {
            if (generation != this@IngestAudioDecoder.generation || failed || closed) return null
            try {
                block()
            } catch (e: Throwable) {
                fail(describe(e))
                null
            }
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            current {
                freeInputs.addLast(index)
                drain(codec)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val chunks = current {
                val chunks = ArrayList<Pair<ShortArray, Long>>(2)
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    if (resampler == null) applyFormat(codec.outputFormat)
                    val resampler = resampler
                    if (resampler != null) {
                        val pcm = readPcm(buffer, info)
                        val pts = ptsNanos.remove(info.presentationTimeUs) ?: (info.presentationTimeUs * 1000)
                        resampler.process(pcm, pcm.size / minOf(decodedChannels, 2), pts) { chunk, chunkPts ->
                            chunks += chunk to chunkPts
                        }
                    }
                }
                if (!failed) codec.releaseOutputBuffer(index, false)
                chunks
            }
            chunks?.forEach { (pcm, pts) -> guardedDeliver { listener.onChunk(pcm, pts) } }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            current { applyFormat(format) }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            current { fail(describe(e)) }
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
        private const val TAG = "IngestAudioDecoder"
        private const val MIME = "audio/mp4a-latm"
        private const val MAX_PENDING_FRAMES = 50
        private const val MAX_PENDING_PTS = 128
        private const val MAX_SAMPLE_RATE = 192_000
        private const val MAX_CHANNELS = 24

        private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** Channel configuration (ISO 14496-3 table 1.19, 0 = in a program config element) to the channel count. */
        private val CHANNELS = mapOf(0 to 2, 1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 6, 7 to 8, 11 to 7, 12 to 8, 13 to 24, 14 to 8)

        private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"

        /** The sample rate and channel count of an AudioSpecificConfig, or null when it is not one. */
        fun parseAudioSpecificConfig(config: ByteArray): Pair<Int, Int>? {
            var bit = 0
            fun read(count: Int): Int? {
                if (bit + count > config.size * 8) return null
                var value = 0
                repeat(count) {
                    value = (value shl 1) or ((config[bit / 8].toInt() shr (7 - bit % 8)) and 1)
                    bit++
                }
                return value
            }
            var objectType = read(5) ?: return null
            if (objectType == 31) objectType = 32 + (read(6) ?: return null)
            if (objectType == 0) return null
            val frequencyIndex = read(4) ?: return null
            val sampleRate = when {
                frequencyIndex < SAMPLE_RATES.size -> SAMPLE_RATES[frequencyIndex]
                frequencyIndex == 15 -> read(24)?.takeIf { it in 1..MAX_SAMPLE_RATE } ?: return null
                else -> return null
            }
            val channels = CHANNELS[read(4) ?: return null] ?: return null
            return sampleRate to channels
        }
    }
}
