// Recording to MP4 (upstream Moblin/Media/HaishinKit/Media/Recorder.swift on AVAssetWriter).
package io.github.mesmerize08.nexusirl.media.pipeline

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * A [MediaSink] that writes an MP4 file with [MediaMuxer].
 *
 * The muxer starts once both track formats are known; samples before that are dropped, and video
 * starts at the first key frame (audio from then on), so the file always begins decodable.
 * Encoder output is Annex-B, which MediaMuxer's MP4 writer accepts for H.264 and H.265.
 * Called from the audio and video encoder threads.
 */
class Mp4Recorder internal constructor(
    private val muxer: Mp4Muxer,
    private val width: Int,
    private val height: Int,
    /** Video from an encoder with B-frames: in decode order, so presentation times go back and forth. */
    private val reorderedVideo: Boolean = false,
) : MediaSink {
    constructor(fileDescriptor: FileDescriptor, width: Int, height: Int, reorderedVideo: Boolean = false) :
        this(MediaMuxerMp4(fileDescriptor), width, height, reorderedVideo)

    private val lock = Any()

    /** Adds the track to the muxer, once both are known. */
    private var audioFormat: (() -> Int)? = null
    private var videoFormat: (() -> Int)? = null
    private var audioTrack = -1
    private var videoTrack = -1
    private var started = false
    private var stopped = false

    private var gotKeyFrame = false
    private var firstVideoUs = Long.MAX_VALUE
    private var lastVideoUs = Long.MIN_VALUE
    private var lastAudioUs = Long.MIN_VALUE

    /** A write or finalising failed: the file may be cut short or unreadable, so it is not kept. */
    @Volatile
    var failed = false
        private set

    /** Video frames written, for the UI and tests. */
    @Volatile
    var videoFrames = 0L
        private set

    override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {
        if (config.isOpus) {
            // Opus is not offered for recording in v1; keep a video-only file rather than fail.
            return
        }
        val audioSpecificConfig = config.encode()
        val format = { muxer.addAudioTrack(sampleRate, config.channels, audioSpecificConfig) }
        synchronized(lock) {
            if (audioFormat == null) {
                audioFormat = format
                maybeStart()
            }
        }
    }

    override fun setVideoConfig(config: MpegTsVideoConfig) {
        val (mime, csd) = when (config) {
            is MpegTsVideoConfig.Avc -> {
                val sps = config.sequenceParameterSet ?: return
                val pps = config.pictureParameterSet ?: return
                MediaFormat.MIMETYPE_VIDEO_AVC to listOf(START_CODE + sps, START_CODE + pps)
            }
            is MpegTsVideoConfig.Hevc -> {
                val vps = config.videoParameterSet ?: return
                val sps = config.sequenceParameterSet ?: return
                val pps = config.pictureParameterSet ?: return
                MediaFormat.MIMETYPE_VIDEO_HEVC to listOf(START_CODE + vps + START_CODE + sps + START_CODE + pps)
            }
        }
        val format = { muxer.addVideoTrack(mime, width, height, csd) }
        synchronized(lock) {
            if (videoFormat == null) {
                videoFormat = format
                maybeStart()
            }
        }
    }

    private fun maybeStart() {
        val audio = audioFormat
        val video = videoFormat ?: return
        if (started || stopped || audio == null) {
            return
        }
        videoTrack = video()
        audioTrack = audio()
        muxer.start()
        started = true
    }

    override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) {
        val us = presentationTimeStamp.toMicroseconds()
        synchronized(lock) {
            // Audio starts with the first video key frame so both tracks begin together. Audio
            // captured just before it can arrive after it: drop that too, or the file starts with
            // audio and the video a few milliseconds in.
            if (!started || stopped || !gotKeyFrame || us < firstVideoUs || us <= lastAudioUs) {
                return
            }
            lastAudioUs = us
            write(audioTrack, frame, us, false)
        }
    }

    override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {
        val us = presentationTimeStamp.toMicroseconds()
        synchronized(lock) {
            if (!started || stopped || (!reorderedVideo && us <= lastVideoUs) || (gotKeyFrame && us < firstVideoUs)) {
                return
            }
            if (!gotKeyFrame) {
                if (!isSync) {
                    return
                }
                gotKeyFrame = true
                firstVideoUs = us
            }
            lastVideoUs = us
            if (write(videoTrack, accessUnit, us, isSync)) {
                videoFrames++
            }
        }
    }

    private fun write(track: Int, data: ByteArray, presentationUs: Long, isKeyFrame: Boolean): Boolean = try {
        muxer.writeSample(track, data, presentationUs, isKeyFrame)
        true
    } catch (e: RuntimeException) {
        // Muxer failed (e.g. storage full: MPEG4Writer has stopped); stop writing.
        stopped = true
        failed = true
        false
    }

    /**
     * Finalises the file and releases the muxer. Returns true when it holds playable video; false
     * also when a write or finalising failed, as the file is then likely broken.
     */
    fun stop(): Boolean = synchronized(lock) {
        stopped = true
        try {
            if (started) {
                muxer.stop()
            }
        } catch (e: RuntimeException) {
            failed = true
        } finally {
            muxer.release()
        }
        started && videoFrames > 0 && !failed
    }

    private fun MediaTime.toMicroseconds(): Long = value * 1_000_000 / timescale

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

/** What [Mp4Recorder] needs of [MediaMuxer], so its bookkeeping can be tested on the JVM. */
interface Mp4Muxer {
    /** Returns the track index. */
    fun addAudioTrack(sampleRate: Int, channels: Int, audioSpecificConfig: ByteArray): Int

    /** Returns the track index. [codecSpecificData] are Annex-B csd-0, csd-1. */
    fun addVideoTrack(mime: String, width: Int, height: Int, codecSpecificData: List<ByteArray>): Int

    fun start()

    fun writeSample(track: Int, data: ByteArray, presentationUs: Long, isKeyFrame: Boolean)

    fun stop()

    fun release()
}

private class MediaMuxerMp4(fileDescriptor: FileDescriptor) : Mp4Muxer {
    private val muxer = MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    override fun addAudioTrack(sampleRate: Int, channels: Int, audioSpecificConfig: ByteArray): Int = muxer.addTrack(
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(audioSpecificConfig))
        },
    )

    override fun addVideoTrack(mime: String, width: Int, height: Int, codecSpecificData: List<ByteArray>): Int = muxer.addTrack(
        MediaFormat.createVideoFormat(mime, width, height).apply {
            codecSpecificData.forEachIndexed { index, data -> setByteBuffer("csd-$index", ByteBuffer.wrap(data)) }
        },
    )

    override fun start() = muxer.start()

    override fun writeSample(track: Int, data: ByteArray, presentationUs: Long, isKeyFrame: Boolean) {
        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, presentationUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
    }

    override fun stop() = muxer.stop()

    override fun release() = muxer.release()
}
