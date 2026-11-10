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

    fun stop() {
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
        val timeline = CaptureTimeline(sampleRate)
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
            val pts = timeline.next(presentationTimeNanos(record, timestamp, timeline.framesRead), frames)
            val resync = timeline.resync
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
            applyGainAndMeasure(pcm, read)
            encoders.forEach { it.feed(pcm, read, pts, resync) }
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
            encoders.forEach { it.feed(pcm, pcm.size, pts) }
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
            // Released on any failure after creation, or it leaks until the process ends.
            bitrate = try {
                configure(codec)
            } catch (e: Exception) {
                codec.release()
                throw e
            }
            this.codec = codec
            // The muxers build the OpusHead / AudioSpecificConfig from this, so the encoder's own
            // codec config buffers are not needed.
            sink.setAudioConfig(format.config(sampleRate, channels), sampleRate)
            outputThread = Thread({ drain() }, "audio-encoder-$format").apply { start() }
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
        fun feed(pcm: ShortArray, count: Int, ptsNanos: Long, resync: Boolean = false) {
            synchronized(this) {
                if (released) {
                    return
                }
                try {
                    rechunker.append(pcm, count, ptsNanos, resync) { chunk, pts -> queue(chunk, pts) }
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
