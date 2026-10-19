package io.github.mesmerize08.nexusirl.media.mux

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsTimecode
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsTimecodeGenerator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of MoblinTests/Moblin/Media/HaishinKit/Mpeg/MpegTsTimecodeGeneratorSuite.swift @97e26d2. */
class UpstreamTimecodeGeneratorSuiteTest {
    private val noon = 1_755_864_000.0
    private val fpsValues = listOf(24, 25, 30, 50, 60)

    private fun pts(frameNumber: Int, fps: Int) = MediaTime(frameNumber.toLong(), fps)

    private fun makeTimecodes(
        generator: MpegTsTimecodeGenerator,
        fps: Int,
        count: Int,
        firstFrameNumber: Int = 0,
    ): List<MpegTsTimecode> = (firstFrameNumber until firstFrameNumber + count).mapNotNull {
        generator.makeTimecode(pts(it, fps), pts(it, fps))
    }

    private fun makeGenerator(base: Double = noon) =
        MpegTsTimecodeGenerator().apply { setReference(base, 0.0) }

    /** Seconds of the UTC day from whole-second clock plus frame, like upstream's calendar components. */
    private fun timeOfDay(timecode: MpegTsTimecode, fps: Int): Double {
        val sei = timecode.toSeiTimecode()
        return (3600 * sei.hours + 60 * sei.minutes + sei.seconds) + timecode.frame.toDouble() / fps
    }

    @Test
    fun noTimecodesBeforeReferenceIsSet() {
        val generator = MpegTsTimecodeGenerator()
        assertFalse(generator.hasReference())
        assertNull(generator.makeTimecode(pts(0, 30), pts(0, 30)))
        generator.setReference(noon, 0.0)
        assertTrue(generator.hasReference())
        assertNotNull(generator.makeTimecode(pts(0, 30), pts(0, 30)))
    }

    @Test
    fun resetForgetsTheReference() {
        val generator = makeGenerator()
        makeTimecodes(generator, 30, 30)
        generator.reset()
        assertFalse(generator.hasReference())
        assertNull(generator.makeTimecode(pts(0, 30), pts(0, 30)))
    }

    @Test
    fun clockIsReferenceTimePlusPresentationTimeStamp() {
        val timecodes = makeTimecodes(makeGenerator(), 30, 300)
        assertEquals(noon, timecodes[0].clock)
        for ((frameNumber, timecode) in timecodes.withIndex().drop(1)) {
            val offset = timecode.clock - noon - frameNumber.toDouble() / 30
            assertTrue(offset >= 0, "frame $frameNumber")
            assertTrue(offset < 1.0 / 30, "frame $frameNumber")
        }
    }

    @Test
    fun referenceIsPresentationTimeStampBaseNotTheFirstFrame() {
        val generator = MpegTsTimecodeGenerator()
        generator.setReference(noon, 100.0)
        val timecodes = makeTimecodes(generator, 30, 1, firstFrameNumber = 30 * 110)
        assertEquals(noon + 10, timecodes[0].clock)
    }

    @Test
    fun clockIsSplitIntoHoursMinutesAndSecondsInUtc() {
        val timecodes = makeTimecodes(makeGenerator(), 30, 30 * 62)
        assertEquals(12.0 * 3600, timeOfDay(timecodes[0], 30))
        assertEquals(12.0 * 3600 + 61, timeOfDay(timecodes[30 * 61], 30))
    }

    @Test
    fun frameNumberCountsUpAndWrapsOncePerSecond() {
        for (fps in fpsValues) {
            val generator = makeGenerator()
            makeTimecodes(generator, fps, fps)
            val frames = makeTimecodes(generator, fps, 5 * fps, firstFrameNumber = fps).map { it.frame }
            assertTrue(frames.all { it < fps }, "fps $fps")
            assertEquals(5 * fps, frames.size)
            for ((previous, current) in frames.zipWithNext()) {
                assertEquals((previous + 1) % fps, current, "fps $fps")
            }
            assertEquals(5, frames.count { it == 0L }, "fps $fps")
        }
    }

    @Test
    fun timeOfDayTracksThePresentationTimeStamp() {
        for (fps in fpsValues) {
            val generator = makeGenerator()
            makeTimecodes(generator, fps, fps)
            val timecodes = makeTimecodes(generator, fps, 5 * fps, firstFrameNumber = fps)
            val offsets = timecodes.mapIndexed { index, timecode ->
                timeOfDay(timecode, fps) - (fps + index).toDouble() / fps
            }
            val median = offsets.sorted()[offsets.size / 2]
            assertTrue(offsets.all { abs(it - median) < 1.0 / fps }, "fps $fps")
            assertTrue(abs(median - 12 * 3600) < 1.0 / fps, "fps $fps")
        }
    }

    @Test
    fun timeOfDayTracksThePresentationTimeStampOverAMinute() {
        val timecodes = makeTimecodes(makeGenerator(), 30, 30 * 60)
        val offsets = timecodes.mapIndexed { index, timecode -> timeOfDay(timecode, 30) - index.toDouble() / 30 }
        val first = offsets.take(30).sorted()[15]
        val last = offsets.takeLast(30).sorted()[15]
        assertTrue(abs(last - first) < 2.0 / 30)
    }

    @Test
    fun invalidDecodeTimeStampFallsBackToPresentationTimeStamp() {
        val withDecodeTimeStamps = makeGenerator()
        val withoutDecodeTimeStamps = makeGenerator()
        for (frameNumber in 0 until 90) {
            val timeStamp = pts(frameNumber, 30)
            assertEquals(
                withDecodeTimeStamps.makeTimecode(timeStamp, timeStamp),
                withoutDecodeTimeStamps.makeTimecode(timeStamp, null),
            )
        }
    }

    @Test
    fun reorderedPresentationTimeStampsDoNotBreakFrameNumbers() {
        val generator = makeGenerator()
        val timecodes = ArrayList<MpegTsTimecode>()
        for (groupOfPictures in 0 until 20) {
            for ((presentationOffset, decodeOffset) in listOf(2 to 0, 0 to 1, 1 to 2)) {
                val presentation = pts(3 * groupOfPictures + presentationOffset, 30)
                val decode = pts(3 * groupOfPictures + decodeOffset, 30)
                generator.makeTimecode(presentation, decode)?.let { timecodes.add(it) }
            }
        }
        assertEquals(60, timecodes.size)
        assertTrue(timecodes.all { it.frame < 30 })
        for ((index, timecode) in timecodes.withIndex().drop(30)) {
            assertTrue(abs(timeOfDay(timecode, 30) - timecode.clock % 86400) < 1.0 / 30, "frame $index")
        }
    }
}
