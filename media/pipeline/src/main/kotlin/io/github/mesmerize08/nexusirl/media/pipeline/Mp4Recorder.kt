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
class Mp4Recorder(
    fileDescriptor: FileDescriptor,
    private val width: Int,
    private val height: Int,
    /** Video from an encoder with B-frames: in decode order, so presentation times go back and forth. */
    private val reorderedVideo: Boolean = false,
) : MediaSink {
    private val muxer = MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()
    private var audioFormat: MediaFormat? = null
    private var videoFormat: MediaFormat? = null
    private var audioTrack = -1
    private var videoTrack = -1
    private var started = false
    private var stopped = false
    private var gotKeyFrame = false
    private var firstVideoUs = Long.MAX_VALUE
    private var lastVideoUs = Long.MIN_VALUE
    private var lastAudioUs = Long.MIN_VALUE

    /** Video frames written, for the UI and tests. */
    @Volatile
    var videoFrames = 0L
        private set

    override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {
        if (config.isOpus) {
            // Opus is not offered for recording in v1; keep a video-only file rather than fail.
            return
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, config.channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.encode()))
        }
        synchronized(lock) {
            if (audioFormat == null) {
                audioFormat = format
                maybeStart()
            }
        }
    }

    override fun setVideoConfig(config: MpegTsVideoConfig) {
        val format = when (config) {
            is MpegTsVideoConfig.Avc -> {
                val sps = config.sequenceParameterSet ?: return
                val pps = config.pictureParameterSet ?: return
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
                    setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
                }
            }
            is MpegTsVideoConfig.Hevc -> {
                val vps = config.videoParameterSet ?: return
                val sps = config.sequenceParameterSet ?: return
                val pps = config.pictureParameterSet ?: return
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + vps + START_CODE + sps + START_CODE + pps))
                }
            }
        }
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
        videoTrack = muxer.addTrack(video)
        audioTrack = muxer.addTrack(audio)
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
            write(audioTrack, frame, us, 0)
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
            write(videoTrack, accessUnit, us, if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            videoFrames++
        }
    }

    private fun write(track: Int, data: ByteArray, presentationUs: Long, flags: Int) {
        val info = MediaCodec.BufferInfo().apply { set(0, data.size, presentationUs, flags) }
        try {
            muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
        } catch (e: IllegalStateException) {
            // Muxer failed (e.g. storage full); stop writing, stop() finalises what is there.
            stopped = true
        }
    }

    /** Finalises the file. Returns true when it holds playable video. */
    fun stop(): Boolean = synchronized(lock) {
        stopped = true
        val playable = started && videoFrames > 0
        try {
            if (started) {
                muxer.stop()
            }
        } catch (e: IllegalStateException) {
            // Nothing written; the file is unusable.
        } finally {
            muxer.release()
        }
        playable
    }

    private fun MediaTime.toMicroseconds(): Long = value * 1_000_000 / timescale

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
