package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufferedAudioTest {
    private class Frame(val pts: Double)

    @Test
    fun appendWithoutConsumerStaysWithinHardCap() {
        val audio = BufferedAudio<Frame>("", 0.1, true, DriftTracker(""), { it.pts })
        repeat(1000) { audio.appendFrame(Frame(1.0 + it * 0.021)) }
        assertTrue(audio.numberOfFrames() <= 600, "${audio.numberOfFrames()} frames held")
    }

    @Test
    fun clearForgetsEveryFrameAndTheLatest() {
        val audio = BufferedAudio<Frame>("", 0.1, true, DriftTracker(""), { it.pts })
        repeat(10) { audio.appendFrame(Frame(1.0 + it * 0.021)) }
        audio.getFrame(1.05)
        audio.clear()
        assertEquals(0, audio.numberOfFrames())
        assertNull(audio.getFrame(1.1))
    }
}
