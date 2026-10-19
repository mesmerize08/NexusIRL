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
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Microphone capture and AAC-LC encoding (upstream `AudioUnit` + `AudioEncoder`).
 *
 * One source at a time feeds the encoder, like upstream (there is no mixer). PTS are on the
 * monotonic host clock via [AudioRecord.getTimestamp], so they line up with video.
 */
class AudioPipeline(
    private val sink: MediaSink,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private val bitrate: Int = 128_000,
) {
    private val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var captureThread: Thread? = null
    private var outputThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var muted = false

    /** Linear gain applied before encoding (upstream `gainDb`). */
    @Volatile
    var gain = 1.0f

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
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed (permission?)" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * channels * 2 * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        this.record = record
        this.codec = codec
        running = true
        sink.setAudioConfig(MpegTsAudioConfig.aacLc(sampleRate, channels), sampleRate)
        record.startRecording()
        captureThread = Thread({ capture(record, codec) }, "audio-capture").apply { start() }
        outputThread = Thread({ drain(codec) }, "audio-encoder").apply { start() }
    }

    fun stop() {
        running = false
        captureThread?.join(1_000)
        outputThread?.join(1_000)
        record?.run {
            stop()
            release()
        }
        codec?.run {
            try {
                stop()
            } catch (e: IllegalStateException) {
                // Already stopped.
            }
            release()
        }
        record = null
        codec = null
    }

    private fun capture(record: AudioRecord, codec: MediaCodec) {
        val bytesPerFrame = channels * 2
        val chunkFrames = 1024 // One AAC frame worth of samples per encoder input.
        val pcm = ShortArray(chunkFrames * channels)
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        var droppedChunks = 0
        var windowStart = System.nanoTime()
        var windowFrames = 0L
        while (running) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) {
                continue
            }
            val frames = read / channels
            val pts = presentationTimeNanos(record, timestamp, framesRead)
            framesRead += frames
            windowFrames += frames
            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000) {
                android.util.Log.d("AudioPipeline", "Captured ${windowFrames * 1_000_000_000L / (now - windowStart)} frames/s, dropped chunks $droppedChunks")
                windowStart = now
                windowFrames = 0
            }
            applyGainAndMeasure(pcm, read)
            val inputIndex = try {
                codec.dequeueInputBuffer(10_000)
            } catch (e: IllegalStateException) {
                return
            }
            if (inputIndex < 0) {
                droppedChunks++
                continue // Encoder backed up; drop this chunk rather than block capture.
            }
            val buffer = codec.getInputBuffer(inputIndex) ?: continue
            buffer.clear()
            // PCM must be in native (little-endian) order; ByteBuffer defaults to big-endian.
            buffer.order(java.nio.ByteOrder.nativeOrder())
            for (i in 0 until read) {
                buffer.putShort(pcm[i])
            }
            codec.queueInputBuffer(inputIndex, 0, read * 2, pts / 1000, 0)
            check(read * 2 == frames * bytesPerFrame)
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

    private fun drain(codec: MediaCodec) {
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
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                sink.writeAudio(data, MediaTime(info.presentationTimeUs, 1_000_000))
            }
            codec.releaseOutputBuffer(index, false)
        }
    }
}
