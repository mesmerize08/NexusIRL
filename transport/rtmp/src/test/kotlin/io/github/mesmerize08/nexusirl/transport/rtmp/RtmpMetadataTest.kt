package io.github.mesmerize08.nexusirl.transport.rtmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RtmpMetadataTest {
    @Test
    fun onMetaDataDescribesVideoAndAudio() {
        // Upstream RtmpStream.createOnMetaData.
        val metadata = RtmpMetadata(1920, 1080, 30.0, false, 6_000_000, 160_000, 48_000.0).toOnMetaData()
        assertEquals(AsValue.Number(1920.0), metadata["width"])
        assertEquals(AsValue.Number(6000.0), metadata["videodatarate"])
        assertEquals(AsValue.Number(160.0), metadata["audiodatarate"])
        assertEquals(AsValue.Number(48_000.0), metadata["audiosamplerate"])
        assertEquals(AsValue.Number(Flv.AUDIO_CODEC_AAC.toDouble()), metadata["audiocodecid"])
    }

    @Test
    fun aStreamWithoutAudioDeclaresNoAudioTrack() {
        val metadata = RtmpMetadata(1280, 720, 60.0, true, 4_000_000, null, null).toOnMetaData()
        listOf("audiocodecid", "audiodatarate", "audiosamplerate").forEach { assertFalse(it in metadata, it) }
        assertEquals(AsValue.Number(60.0), metadata["framerate"])
    }
}
