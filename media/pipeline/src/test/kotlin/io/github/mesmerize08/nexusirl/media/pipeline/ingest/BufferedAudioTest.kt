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

    /** Output paused (nobody consumed for a while): the next frame is the one for the output time, not the next queued. */
    @Test
    fun resyncAfterPauseGivesTheFrameForTheOutputTime() {
        val audio = BufferedAudio<Frame>("", 0.1, true, DriftTracker(""), { it.pts })
        val step = 0.021
        repeat(200) { audio.appendFrame(Frame(1.0 + it * step)) }
        // Consumed on the grid for a while: out of the syncing state.
        var t = 1.0
        repeat(20) {
            audio.getFrame(t)
            t += step
        }
        // A second and a half without consuming.
        t += 1.5
        val expectedLeft = (0 until 200).count { 1.0 + it * step >= t - 0.3 }
        audio.resync(dropBefore = t - 0.3)
        assertEquals(expectedLeft, audio.numberOfFrames())
        val output = audio.getFrame(t)
        assertTrue(output != null && !output.silent)
        assertTrue(t - output.frame.pts in 0.0..step, "played ${output.frame.pts} for $t")
    }
}
