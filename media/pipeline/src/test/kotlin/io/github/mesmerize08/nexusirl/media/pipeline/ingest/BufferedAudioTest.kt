package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertTrue

class BufferedAudioTest {
    private class Frame(val pts: Double)

    @Test
    fun appendWithoutConsumerStaysWithinHardCap() {
        val audio = BufferedAudio<Frame>("", 0.1, true, DriftTracker(""), { it.pts })
        repeat(1000) { audio.appendFrame(Frame(1.0 + it * 0.021)) }
        assertTrue(audio.numberOfFrames() <= 600, "${audio.numberOfFrames()} frames held")
    }
}
