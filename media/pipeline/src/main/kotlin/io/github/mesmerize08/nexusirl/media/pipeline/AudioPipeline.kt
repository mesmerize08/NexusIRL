package io.github.mesmerize08.nexusirl.media.pipeline

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.pipeline.ingest.BufferedAudioClock
import io.github.mesmerize08.nexusirl.media.pipeline.ingest.IngestSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Microphone capture feeding AAC-LC and Opus encoders (upstream `AudioUnit` + `AudioEncoder`).
 *
 * One source at a time feeds the encoders, like upstream (there is no mixer). PTS are on the
 * monotonic host clock via [AudioRecord.getTimestamp], so they line up with video. Each encoder
 * gets the same samples, cut to its own frame size.
 */
class AudioPipeline(
    private val sampleRate: Int = 48_000,
    /** 1 (mono) or 2 (stereo). */
    val channels: Int = 1,
) {
    private val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val encoders = CopyOnWriteArrayList<Encoder>()

    @Volatile
    private var running = false

    /** Serialises [switchIngest] calls. */
    private val switchLock = Any()

    /**
     * Held while one chunk is gained, measured and fed, so the microphone and the ingest output
     * never feed at once and a switch takes effect between two chunks.
     */
    private val feedLock = Any()

    /** The ingest audio sent instead of the microphone; written with [feedLock] held. */
    @Volatile
    private var ingestOutput: IngestOutput? = null

    /** Where the chunk fed last ends (PTS plus duration), from either source (guarded by [feedLock]). */
    private var lastFedEndNanos = Long.MIN_VALUE

    /**
     * Where the ingest chunk fed last ends; microphone audio before it is cut off, so chunks never
     * overlap and an encoder's own chunking never steps back (guarded by [feedLock]).
     */
    private var lastIngestEndNanos = Long.MIN_VALUE

    /** Hears every chunk queued to an encoder: format, PTS (ns), frames (for tests). */
    @Volatile
    internal var encoderInputListener: ((AudioEncoderFormat, Long, Int) -> Unit)? = null

    @Volatile
    var muted = false

    /** Linear gain applied before encoding (upstream `gainDb`). */
    @Volatile
    var gain = 1.0f

    @Volatile
    private var preferredDevice: android.media.AudioDeviceInfo? = null

    /** Routes capture to [device] (null: system default). Works while recording. */
    fun setPreferredDevice(device: android.media.AudioDeviceInfo?) {
        preferredDevice = device
        record?.setPreferredDevice(device)
    }

    /** False when the microphone could not be opened and silence is sent instead. */
    @Volatile
    var microphoneAvailable = true
        private set

    /** Peak level in dBFS over the latest buffer (upstream level meter), for the UI. */
    @Volatile
    var levelDbfs = -160f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minimum, sampleRate * channels * 2 / 10),
        )
        // No permission, or the microphone is busy: send silence so the stream stays valid (MPEG-TS
        // output needs an audio track, and many RTMP ingests reject video-only streams).
        val microphone = record.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: run {
            record.release()
            null
        }
        microphoneAvailable = microphone != null
        microphone?.let { mic -> preferredDevice?.let { mic.setPreferredDevice(it) } }
        this.record = microphone
        running = true
        captureThread = if (microphone != null) {
            microphone.startRecording()
            Thread({ capture(microphone) }, "audio-capture")
        } else {
            Thread({ silence() }, "audio-silence")
        }.apply { start() }
    }

    /**
     * Starts a [key] encoder whose output goes to [sink]. An Opus encoder that cannot be
     * created falls back to AAC, so the stream keeps its audio; [sink] then gets the AAC config.
     * Returns the format and bitrate encoded. Throws when AAC fails too.
     */
    fun addEncoder(key: AudioEncoderKey, sink: MediaSink): AudioEncoderKey {
        val encoder = try {
            Encoder(key.format, key.bitrate, sink)
        } catch (e: Exception) {
            if (key.format == AudioEncoderFormat.AAC) {
                throw e
            }
            android.util.Log.w("AudioPipeline", "No ${key.format} encoder, using AAC: $e")
            Encoder(AudioEncoderFormat.AAC, key.bitrate, sink, requested = key)
        }
        encoders.add(encoder)
        return AudioEncoderKey(encoder.format, encoder.bitrate)
    }

    fun removeEncoder(key: AudioEncoderKey) {
        encoders.filter { it.requested == key }.forEach {
            encoders.remove(it)
            it.release()
        }
    }

    /**
     * Sends [source]'s audio to the encoders instead of the microphone; null goes back to the
     * microphone. The output for time t is the source's chunk for t - [videoDelayNanos], so it
     * lines up with a picture shown that much after its selection time (the video pipeline's
     * one-frame latch lag). The source must have [channels] channels. [stop] forgets the source.
     */
    fun useIngestAudio(source: IngestSource?, videoDelayNanos: Long = 0) {
        require(source == null || source.outputChannels == channels) {
            "The ingest source has ${source?.outputChannels} audio channels, the pipeline $channels"
        }
        switchIngest(source, videoDelayNanos, source?.let { it::audioChunk })
    }

    /** [useIngestAudio] with any chunk provider (for tests). */
    internal fun useIngestChunks(chunks: ((outputNanos: Long) -> ShortArray?)?, videoDelayNanos: Long = 0) {
        switchIngest(chunks, videoDelayNanos, chunks)
    }

    private fun switchIngest(key: Any?, videoDelayNanos: Long, chunks: ((Long) -> ShortArray?)?) {
        synchronized(switchLock) {
            val old = ingestOutput
            if (old?.key === key && (old == null || (old.videoDelayNanos == videoDelayNanos && old.isAlive))) {
                return
            }
            val output = if (key != null && chunks != null) IngestOutput(key, videoDelayNanos, chunks) else null
            // Replaced in one step: the old output cannot feed any more, and either the new one
            // feeds next or (null) the microphone does from its next read. Nothing in between.
            synchronized(feedLock) {
                ingestOutput = output
                if (output == null) resetSilentLevel()
            }
            old?.stop()
            try {
                output?.start()
            } catch (e: Throwable) {
                // A thread that cannot start (OutOfMemoryError): back to the microphone.
                synchronized(feedLock) { if (ingestOutput === output) ingestOutput = null }
                throw e
            }
        }
    }

    /** The no-microphone silence does not measure: do not leave a source's level up (feedLock held). */
    private fun resetSilentLevel() {
        if (!microphoneAvailable) levelDbfs = -160f
    }

    private fun framesToNanos(frames: Int) = frames * 1_000_000_000L / sampleRate

    fun stop() {
        switchIngest(null, 0, null)
        running = false
        captureThread?.join(1_000)
        record?.run {
            stop()
            release()
        }
        record = null
        encoders.forEach { it.release() }
        encoders.clear()
    }

    private fun capture(record: AudioRecord) {
        val chunkFrames = 1024
        val pcm = ShortArray(chunkFrames * channels)
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        // Times come from the sample count, like upstream, so frames are evenly spaced; Android's
        // per-read timestamps jitter by several milliseconds.
        var anchorNanos: Long? = null
        var anchorFrames = 0L
        var windowStart = System.nanoTime()
        var windowFrames = 0L
        while (running) {
            val read = record.read(pcm, 0, pcm.size)
            if (read < 0) {
                // E.g. ERROR_DEAD_OBJECT after the audio device went away: don't spin.
                Thread.sleep(20)
                continue
            }
            if (read == 0) {
                continue
            }
            val frames = read / channels
            val measured = presentationTimeNanos(record, timestamp, framesRead)
            val counted = anchorNanos?.let { it + (framesRead - anchorFrames) * 1_000_000_000L / sampleRate }
            val pts = if (counted == null || abs(measured - counted) > RESYNC_NANOS) {
                // First read, or the count drifted from the clock (e.g. after a device change).
                anchorNanos = measured
                anchorFrames = framesRead
                measured
            } else {
                counted
            }
            framesRead += frames
            windowFrames += frames
            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000) {
                val dropped = encoders.joinToString { "${it.format} ${it.droppedChunks}" }
                android.util.Log.d(
                    "AudioPipeline",
                    "Captured ${windowFrames * 1_000_000_000L / (now - windowStart)} frames/s, dropped chunks $dropped",
                )
                windowStart = now
                windowFrames = 0
            }
            feedFromMicrophone(pcm, read, pts, measure = true)
        }
    }

    /**
     * Feeds a microphone chunk (or the no-microphone silence), unless ingest audio is sent instead.
     * After ingest audio, the frames before the end of the last ingest chunk are cut off (a chunk
     * entirely before it is skipped), so chunks never overlap. Without ingest audio nothing is cut.
     */
    private fun feedFromMicrophone(pcm: ShortArray, count: Int, pts: Long, measure: Boolean) {
        synchronized(feedLock) {
            if (ingestOutput != null) {
                return
            }
            var samples = pcm
            var sampleCount = count
            var start = pts
            if (pts < lastIngestEndNanos) {
                val frames = count / channels
                // Rounded up, so the rest starts at or after the end.
                val cut = ((lastIngestEndNanos - pts) * sampleRate + 999_999_999L) / 1_000_000_000L
                if (cut >= frames) {
                    return
                }
                samples = pcm.copyOfRange(cut.toInt() * channels, count)
                sampleCount = samples.size
                start = pts + framesToNanos(cut.toInt())
            }
            if (measure) {
                applyGainAndMeasure(samples, sampleCount)
            }
            encoders.forEach { it.feed(samples, sampleCount, start) }
            lastFedEndNanos = start + framesToNanos(sampleCount / channels)
        }
    }

    private companion object {
        const val TAG = "AudioPipeline"

        /** Re-anchor sample-counted times when they are this far from the measured clock. */
        const val RESYNC_NANOS = 20_000_000L

        /** Ingest output: frames per chunk (spec §5). */
        const val INGEST_CHUNK_FRAMES = 1024

        /** Ingest output: further behind schedule than this, the missed ticks are skipped. */
        const val INGEST_MAX_BEHIND_NANOS = 100_000_000L
    }

    /**
     * Sends an ingest source's audio: a thread ("audio-ingest") that ticks every 1024 frames on an
     * absolute schedule of the host clock, takes each PTS from a [BufferedAudioClock] grid
     * (upstream `BufferedAudio.output()`), and feeds the chunk for that PTS minus
     * [videoDelayNanos], or silence, through the microphone's path (gain, mute, level meter). A
     * tick that would start before the end of the chunk fed last (a step back of the grid) is
     * skipped. Nothing thrown escapes the thread; when it ends on its own the microphone takes over.
     */
    private inner class IngestOutput(val key: Any, val videoDelayNanos: Long, private val chunks: (Long) -> ShortArray?) {
        private val thread = Thread({ run() }, "audio-ingest")

        @Volatile
        private var active = true

        fun start() = thread.start()

        fun stop() {
            active = false
            if (Thread.currentThread() !== thread) {
                thread.join(1_000)
            }
        }

        val isAlive: Boolean get() = thread.isAlive

        private fun run() {
            try {
                loop()
            } catch (e: Throwable) {
                // Nothing may escape this thread.
                android.util.Log.e(TAG, "Ingest audio output stopped", e)
            } finally {
                // Ended on its own (a failure): the microphone, or its silence, takes over at once.
                synchronized(feedLock) {
                    if (ingestOutput === this) {
                        ingestOutput = null
                        resetSilentLevel()
                    }
                }
            }
        }

        private fun loop() {
            val clock = BufferedAudioClock(sampleRate, INGEST_CHUNK_FRAMES) { android.util.Log.i(TAG, it) }
            val pcm = ShortArray(INGEST_CHUNK_FRAMES * channels)
            var start = System.nanoTime()
            var tick = 0L
            var failures = 0
            while (active) {
                val now = System.nanoTime()
                if (now - tickNanos(start, tick) > INGEST_MAX_BEHIND_NANOS) {
                    // Far behind (the thread was not scheduled): skip the missed ticks.
                    start = now
                    tick = 0
                }
                val pts = Math.round(clock.next(now / 1e9) * 1e9)
                val chunk = try {
                    chunks(pts - videoDelayNanos)
                } catch (e: Exception) {
                    if (failures++ == 0) android.util.Log.w(TAG, "Ingest audio chunk failed, sending silence", e)
                    null
                }
                // Copied: gain and mute must not change the source's own buffer.
                val count = chunk?.let { minOf(it.size, pcm.size) } ?: 0
                chunk?.let { System.arraycopy(it, 0, pcm, 0, count) }
                pcm.fill(0, count, pcm.size)
                feed(pts, pcm)
                tick++
                val wait = tickNanos(start, tick) - System.nanoTime()
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            }
        }

        private fun tickNanos(start: Long, tick: Long) = start + tick * INGEST_CHUNK_FRAMES * 1_000_000_000L / sampleRate

        private fun feed(pts: Long, pcm: ShortArray) {
            synchronized(feedLock) {
                // A grid PTS within a microsecond of the end is its rounding, not an overlap.
                if (ingestOutput !== this || pts + 1_000 < lastFedEndNanos) {
                    return
                }
                applyGainAndMeasure(pcm, pcm.size)
                encoders.forEach { it.feed(pcm, pcm.size, pts) }
                lastFedEndNanos = pts + framesToNanos(INGEST_CHUNK_FRAMES)
                lastIngestEndNanos = lastFedEndNanos
            }
        }
    }

    /** Silence in real time on the host clock, for when there is no microphone. */
    private fun silence() {
        val chunkFrames = 1024
        val chunkNanos = chunkFrames * 1_000_000_000L / sampleRate
        val pcm = ShortArray(chunkFrames * channels)
        val start = System.nanoTime()
        var chunks = 0L
        levelDbfs = -160f
        while (running) {
            val pts = start + chunks * chunkNanos
            val wait = pts + chunkNanos - System.nanoTime()
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            chunks++
            feedFromMicrophone(pcm, pcm.size, pts, measure = false)
        }
    }

    /** PTS of the first frame of the next read, from AudioRecord's monotonic timestamp. */
    private fun presentationTimeNanos(record: AudioRecord, timestamp: AudioTimestamp, framesRead: Long): Long =
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            timestamp.nanoTime + (framesRead - timestamp.framePosition) * 1_000_000_000L / sampleRate
        } else {
            // Fallback: the buffer just read ends roughly now.
            System.nanoTime() - 1024L * 1_000_000_000L / sampleRate
        }

    private fun applyGainAndMeasure(pcm: ShortArray, count: Int) {
        var peak = 0
        val gain = this.gain
        val muted = this.muted
        for (i in 0 until count) {
            val value = if (muted) 0 else (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = value.toShort()
            peak = max(peak, abs(value))
        }
        levelDbfs = if (peak == 0) -160f else (20 * log10(peak / 32768.0)).toFloat()
    }

    /** One MediaCodec encoder with its output thread, fed from the capture thread. */
    private inner class Encoder(
        val format: AudioEncoderFormat,
        private val requestedBitrate: Int,
        private val sink: MediaSink,
        /** What the consumers asked for ([format] differs after a fallback to AAC). */
        val requested: AudioEncoderKey = AudioEncoderKey(format, requestedBitrate),
    ) {
        private val rechunker = PcmRechunker(channels, format.framesPerChunk, sampleRate)
        private val codec: MediaCodec
        private val outputThread: Thread

        @Volatile
        private var released = false

        @Volatile
        var droppedChunks = 0
            private set

        /** What the codec can take, like upstream's clamp to the applicable encode bitrates. */
        val bitrate: Int

        init {
            // Opus: c2.android.opus.encoder, on every phone since Android 10.
            val codec = MediaCodec.createEncoderByType(format.mimeType)
            this.codec = codec
            // Everything after creation can fail (configure, start, the sink, the thread): the
            // codec is released then, or it leaks until the process ends.
            val (bitrate, thread) = try {
                val bitrate = configure(codec)
                // The muxers build the OpusHead / AudioSpecificConfig from this, so the encoder's
                // own codec config buffers are not needed.
                sink.setAudioConfig(format.config(sampleRate, channels), sampleRate)
                bitrate to Thread({ drain() }, "audio-encoder-$format").apply { start() }
            } catch (e: Throwable) {
                // Throwable: a thread that cannot start throws OutOfMemoryError.
                codec.release()
                throw e
            }
            this.bitrate = bitrate
            outputThread = thread
        }

        /** Configures and starts [codec]; returns the bitrate it got. */
        private fun configure(codec: MediaCodec): Int {
            val supported = try {
                codec.codecInfo.getCapabilitiesForType(format.mimeType).audioCapabilities?.bitrateRange?.let { it.lower..it.upper }
            } catch (e: IllegalArgumentException) {
                null
            }
            val bitrate = format.bitrate(requestedBitrate, channels, supported)
            val mediaFormat = MediaFormat.createAudioFormat(format.mimeType, sampleRate, channels).apply {
                if (format == AudioEncoderFormat.AAC) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.framesPerChunk * channels * 2 * 4)
            }
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            return bitrate
        }

        /** Capture thread. The lock keeps [release] from freeing buffers being filled. */
        fun feed(pcm: ShortArray, count: Int, ptsNanos: Long) {
            synchronized(this) {
                if (released) {
                    return
                }
                try {
                    rechunker.append(pcm, count, ptsNanos) { chunk, pts -> queue(chunk, pts) }
                } catch (e: IllegalStateException) {
                    // The codec failed; it is released with the pipeline.
                }
            }
        }

        private fun queue(chunk: ShortArray, ptsNanos: Long) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) {
                droppedChunks++
                return // Encoder backed up; drop this chunk rather than block capture.
            }
            val buffer = codec.getInputBuffer(inputIndex) ?: return
            buffer.clear()
            // PCM must be in native (little-endian) order; ByteBuffer defaults to big-endian.
            buffer.order(java.nio.ByteOrder.nativeOrder())
            buffer.asShortBuffer().put(chunk)
            codec.queueInputBuffer(inputIndex, 0, chunk.size * 2, ptsNanos / 1000, 0)
            encoderInputListener?.invoke(format, ptsNanos, chunk.size / channels)
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            while (!released) {
                val index = try {
                    codec.dequeueOutputBuffer(info, 10_000)
                } catch (e: IllegalStateException) {
                    return
                }
                if (index < 0) {
                    continue
                }
                // Copied out and handed back before the sink gets it: a sink stuck on a stalled
                // connection must not hold the codec, which [release] frees after a second.
                val data = try {
                    val buffer = codec.getOutputBuffer(index)
                    val copy = if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        ByteArray(info.size).also {
                            buffer.position(info.offset)
                            buffer.get(it)
                        }
                    } else {
                        null
                    }
                    codec.releaseOutputBuffer(index, false)
                    copy
                } catch (e: IllegalStateException) {
                    return // Released meanwhile.
                }
                data?.let { sink.writeAudio(it, MediaTime(info.presentationTimeUs, 1_000_000)) }
            }
        }

        fun release() {
            synchronized(this) {
                released = true
            }
            outputThread.join(1_000)
            try {
                codec.stop()
            } catch (e: IllegalStateException) {
                // Already stopped.
            }
            codec.release()
        }
    }
}
