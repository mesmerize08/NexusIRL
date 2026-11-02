package io.github.mesmerize08.nexusirl.media.pipeline

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Mp4RecorderTest {
    /** Records calls; writes fail from [failFromWrite] (counting from 1) on, like MPEG4Writer on a full disk. */
    private class FakeMuxer(val failFromWrite: Int = Int.MAX_VALUE, val failStop: Boolean = false) : Mp4Muxer {
        var writes = 0
        var started = false
        var stopped = false
        var released = false

        override fun addAudioTrack(sampleRate: Int, channels: Int, audioSpecificConfig: ByteArray) = 1

        override fun addVideoTrack(mime: String, width: Int, height: Int, codecSpecificData: List<ByteArray>) = 0

        override fun start() {
            started = true
        }

        override fun writeSample(track: Int, data: ByteArray, presentationUs: Long, isKeyFrame: Boolean) {
            writes++
            if (writes >= failFromWrite) {
                throw IllegalStateException("writer stopped")
            }
        }

        override fun stop() {
            stopped = true
            if (failStop) {
                throw IllegalStateException("stop failed")
            }
        }

        override fun release() {
            released = true
        }
    }

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    private fun started(muxer: FakeMuxer) = Mp4Recorder(muxer, 1280, 720).apply {
        setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2), 48000)
        setVideoConfig(MpegTsVideoConfig.Avc(sps, pps))
    }

    private fun Mp4Recorder.video(frames: IntRange) {
        for (frame in frames) {
            writeVideo(ByteArray(10), MediaTime(frame.toLong(), 30), null, frame == 0)
        }
    }

    @Test
    fun normalRecordingIsPlayable() {
        val muxer = FakeMuxer()
        val recorder = started(muxer)
        recorder.video(0 until 5)
        assertTrue(recorder.stop())
        assertEquals(5, recorder.videoFrames)
        assertTrue(muxer.stopped)
        assertTrue(muxer.released)
    }

    @Test
    fun failedFirstWriteIsNotPlayable() {
        val muxer = FakeMuxer(failFromWrite = 1)
        val recorder = started(muxer)
        recorder.video(0 until 5)
        assertFalse(recorder.stop())
        assertEquals(0, recorder.videoFrames)
        assertTrue(muxer.released)
    }

    @Test
    fun failureAfterGoodFramesIsNotPlayable() {
        val muxer = FakeMuxer(failFromWrite = 4)
        val recorder = started(muxer)
        recorder.video(0 until 10)
        // The writer stops at the first failure; frames after it are not attempted.
        assertEquals(4, muxer.writes)
        assertEquals(3, recorder.videoFrames)
        assertFalse(recorder.stop())
        assertTrue(muxer.released)
    }

    @Test
    fun failedStopIsNotPlayable() {
        val muxer = FakeMuxer(failStop = true)
        val recorder = started(muxer)
        recorder.video(0 until 5)
        assertFalse(recorder.stop())
        assertTrue(muxer.released)
    }

    @Test
    fun nothingRecordedIsNotPlayable() {
        val muxer = FakeMuxer()
        val recorder = started(muxer)
        assertFalse(recorder.stop())
        assertTrue(muxer.released)
    }
}
