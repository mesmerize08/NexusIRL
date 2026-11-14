// Port of MoblinTests/Moblin/Media/HaishinKit/Media/Audio/BufferedAudioSuite.swift @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UpstreamBufferedAudioSuiteTest {
    private class Frame(val pts: Double)

    private fun frame(pts: Double) = Frame(pts)

    private fun createBufferedAudio(driftTracker: DriftTracker = DriftTracker("")) =
        BufferedAudio<Frame>("", 0.1, true, driftTracker, { it.pts })

    private fun appendAll(audio: BufferedAudio<Frame>, frames: List<Frame>) {
        for (f in frames) audio.appendFrame(f)
    }

    private class Clock(var t: Double)

    private fun expectSequence(audio: BufferedAudio<Frame>, frames: List<Frame>, clock: Clock, range: IntRange) {
        for (i in range) {
            assertSame(frames[i], audio.getFrame(clock.t)?.frame, "Failed at index $i, timestamp ${clock.t}.")
            clock.t += 0.021
        }
    }

    private fun createOa6Frames() = listOf(
        565.064, 565.097, 565.097, 565.131, 565.164, 565.164, 565.197, 565.197, 565.231, 565.231,
        565.264, 565.298, 565.331, 565.331, 565.364, 565.398, 565.398, 565.431, 565.464, 565.464,
        565.498, 565.531, 565.531, 565.531, 565.565, 565.598,
    ).map(::frame)

    @Test
    fun processNormal() {
        val audio = createBufferedAudio()
        val f1 = frame(1.000)
        val f2 = frame(1.021)
        val f3 = frame(1.042)
        audio.appendFrame(f1)
        audio.appendFrame(f2)
        audio.appendFrame(f3)
        assertEquals(3, audio.numberOfFrames())
        assertSame(f1, audio.getFrame(1.000)?.frame)
        assertSame(f2, audio.getFrame(1.021)?.frame)
        val last = audio.getFrame(1.042)
        assertSame(f3, last?.frame)
        assertFalse(last!!.silent)
        assertEquals(0, audio.numberOfFrames())
        val repeated = audio.getFrame(1.063)
        assertSame(f3, repeated?.frame)
        assertTrue(repeated!!.silent)
        assertEquals(0, audio.numberOfFrames())
    }

    @Test
    fun processGap() {
        val audio = createBufferedAudio()
        val frames = listOf(1.000, 1.021, 1.210, 1.231, 1.252, 1.273, 1.294).map(::frame)
        appendAll(audio, frames)
        assertEquals(7, audio.numberOfFrames())
        val clock = Clock(1.000)
        expectSequence(audio, frames, clock, 0..1)
        for (n in 0 until 10) {
            val out = audio.getFrame(clock.t)
            assertSame(frames[2], out?.frame)
            // The first call consumes the frame; the nine repeats are silence.
            assertEquals(n != 0, out!!.silent, "repeat $n")
            clock.t += 0.021
        }
        expectSequence(audio, frames, clock, 3..6)
        assertEquals(0, audio.numberOfFrames())
    }

    @Test
    fun processDriftChange() {
        val driftTracker = DriftTracker("")
        val audio = createBufferedAudio(driftTracker)
        val frames = listOf(
            1.000, 1.021, 1.042, 1.063, 1.084, 1.105, 1.126, 1.147, 1.168, 1.189,
            1.210, 1.231, 1.252, 1.273, 1.294,
        ).map(::frame)
        appendAll(audio, frames)
        assertEquals(15, audio.numberOfFrames())
        val clock = Clock(1.000)
        expectSequence(audio, frames, clock, 0..1)
        driftTracker.setDrift(0.1)
        for (i in listOf(2, 2, 2, 2, 2, 2, 3, 4, 5, 6)) {
            assertSame(frames[i], audio.getFrame(clock.t)?.frame)
            clock.t += 0.021
        }
        driftTracker.setDrift(0.0)
        for (i in listOf(7, 12, 13, 14, 14, 14)) {
            assertSame(frames[i], audio.getFrame(clock.t)?.frame)
            clock.t += 0.021
        }
        assertEquals(0, audio.numberOfFrames())
    }

    @Test
    fun oa6BadAudioTimestamps() {
        val audio = createBufferedAudio()
        val frames = createOa6Frames()
        appendAll(audio, frames)
        assertEquals(26, audio.numberOfFrames())
        expectSequence(audio, frames, Clock(565.064), 0..25)
        assertEquals(0, audio.numberOfFrames())
    }

    @Test
    fun oa6BadAudioTimestampsOffsetOutputTime() {
        val audio = createBufferedAudio()
        val frames = createOa6Frames()
        appendAll(audio, frames)
        assertEquals(26, audio.numberOfFrames())
        val clock = Clock(565.064 - 0.01)
        assertSame(frames[0], audio.getFrame(clock.t)?.frame)
        clock.t += 0.021
        expectSequence(audio, frames, clock, 0..25)
        assertEquals(0, audio.numberOfFrames())
    }

    @Test
    fun oa6BadAudioTimestampsOffsetOutputTime2() {
        val audio = createBufferedAudio()
        val frames = createOa6Frames()
        appendAll(audio, frames)
        assertEquals(26, audio.numberOfFrames())
        expectSequence(audio, frames, Clock(565.064 + 0.01), 0..25)
        assertEquals(0, audio.numberOfFrames())
    }

    @Test
    fun oa6BadAudioTimestampsBigOffsetOutputTime() {
        val audio = createBufferedAudio()
        val frames = createOa6Frames()
        appendAll(audio, frames)
        assertEquals(26, audio.numberOfFrames())
        val clock = Clock(565.064 - 0.2)
        repeat(10) {
            assertSame(frames[0], audio.getFrame(clock.t)?.frame)
            clock.t += 0.021
        }
        expectSequence(audio, frames, clock, 0..25)
        assertEquals(0, audio.numberOfFrames())
    }

    @Test
    fun capOf300DropsOldest() {
        val audio = createBufferedAudio()
        repeat(400) { audio.appendFrame(frame(1.0 + it * 0.021)) }
        audio.getFrame(1.0)
        assertTrue(audio.numberOfFrames() <= 300)
    }

    @Test
    fun emptyBeforeFirstFrame() {
        assertNull(createBufferedAudio().getFrame(1.0))
    }
}
