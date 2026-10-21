package io.github.mesmerize08.nexusirl.stream

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyFrameGateTest {
    private class Recorder : MediaSink {
        val events = mutableListOf<String>()

        override fun setAudioConfig(config: MpegTsAudioConfig, sampleRate: Int) {
            events += "audioConfig"
        }

        override fun setVideoConfig(config: MpegTsVideoConfig) {
            events += "videoConfig"
        }

        override fun writeAudio(frame: ByteArray, presentationTimeStamp: MediaTime) {
            events += "audio"
        }

        override fun writeVideo(accessUnit: ByteArray, presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?, isSync: Boolean) {
            events += if (isSync) "key" else "delta"
        }
    }

    @Test
    fun videoStartsOnAKeyFrame() {
        val recorder = Recorder()
        val gate = KeyFrameGate(recorder)
        val time = MediaTime(0, 1)
        gate.writeVideo(ByteArray(1), time, null, false)
        gate.writeAudio(ByteArray(1), time)
        assertFalse(gate.started)
        gate.writeVideo(ByteArray(1), time, null, true)
        gate.writeVideo(ByteArray(1), time, null, false)
        assertTrue(gate.started)
        assertEquals(listOf("audio", "key", "delta"), recorder.events)
    }
}
