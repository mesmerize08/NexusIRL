package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmRechunkerTest {
    private class Chunk(val samples: ShortArray, val ptsNanos: Long)

    private fun collect(rechunker: PcmRechunker, pcm: ShortArray, count: Int, ptsNanos: Long): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        rechunker.append(pcm, count, ptsNanos) { samples, pts -> chunks += Chunk(samples.copyOf(), pts) }
        return chunks
    }

    @Test
    fun captureReadsAreCutIntoOpusFrames() {
        // 1024-frame reads into 960-frame (20 ms at 48 kHz) Opus frames, like upstream's ring buffer.
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val first = ShortArray(1024) { it.toShort() }
        val chunks = collect(rechunker, first, 1024, 1_000_000_000)
        assertEquals(1, chunks.size)
        assertContentEquals(first.copyOf(960), chunks[0].samples)
        assertEquals(1_000_000_000, chunks[0].ptsNanos)

        // The 64 frames left over start the next chunk; its time is where they were captured.
        val secondStart = 1_000_000_000 + 1024 * 1_000_000_000L / 48_000
        val second = ShortArray(1024) { (1024 + it).toShort() }
        val next = collect(rechunker, second, 1024, secondStart)
        assertEquals(1, next.size)
        assertContentEquals(ShortArray(960) { (960 + it).toShort() }, next[0].samples)
        assertEquals(1_000_000_000 + 960 * 1_000_000_000L / 48_000, next[0].ptsNanos)
    }

    @Test
    fun oneReadCanFillSeveralChunks() {
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val chunks = collect(rechunker, ShortArray(2048), 2048, 0)
        assertEquals(listOf(0L, 20_000_000L), chunks.map { it.ptsNanos })
    }

    @Test
    fun stereoCountsFramesNotSamples() {
        val rechunker = PcmRechunker(channels = 2, framesPerChunk = 2, sampleRate = 48_000)
        val pcm = shortArrayOf(1, -1, 2, -2, 3, -3)
        val chunks = collect(rechunker, pcm, pcm.size, 0)
        assertEquals(1, chunks.size)
        assertContentEquals(shortArrayOf(1, -1, 2, -2), chunks[0].samples)
        val next = collect(rechunker, shortArrayOf(4, -4, 9, 9), 2, 3 * 1_000_000_000L / 48_000)
        assertContentEquals(shortArrayOf(3, -3, 4, -4), next.single().samples)
        assertEquals(2 * 1_000_000_000L / 48_000, next.single().ptsNanos)
    }

    @Test
    fun matchingChunkSizesPassThrough() {
        // AAC: 1024-frame reads map one to one, with the read's own time.
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 1024, sampleRate = 48_000)
        assertEquals(listOf(5L), collect(rechunker, ShortArray(1024), 1024, 5).map { it.ptsNanos })
        assertEquals(listOf(30_000_005L), collect(rechunker, ShortArray(1024), 1024, 30_000_005).map { it.ptsNanos })
    }

    private fun assertNear(expected: Long, actual: Long) = assertTrue(kotlin.math.abs(expected - actual) <= 1_000, "$expected vs $actual")

    private fun nanos(frames: Long) = frames * 1_000_000_000L / 48_000

    /** One 1024-frame capture read: its time, its first sample's value, and whether capture re-anchored. */
    private class Read(val ptsNanos: Long, val first: Int, val resync: Boolean = false)

    /** Feeds reads like the capture loop; returns every chunk's samples and time. */
    private fun feed(rechunker: PcmRechunker, reads: List<Read>): List<Chunk> = reads.flatMap { read ->
        val chunks = ArrayList<Chunk>()
        val pcm = ShortArray(1024) { (read.first + it).toShort() }
        rechunker.append(pcm, 1024, read.ptsNanos, read.resync) { samples, pts -> chunks += Chunk(samples.copyOf(), pts) }
        chunks
    }

    private fun assertMonotonic(chunks: List<Chunk>) {
        val times = chunks.map { it.ptsNanos }
        // Each chunk starts no earlier than the previous one ends: no overlap, nothing backwards.
        times.zipWithNext().forEach { (a, b) -> assertTrue(b >= a + nanos(960) - 1_000, "$times") }
    }

    @Test
    fun continuousInputKeepsEvenlySpacedChunks() {
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val chunks = feed(rechunker, (0 until 15).map { Read(nanos(it * 1024L), it * 1024) })
        assertEquals(16, chunks.size)
        // Within a microsecond: times are whole nanoseconds, so a 1 ns rounding step is expected.
        chunks.forEachIndexed { index, chunk -> assertNear(nanos(index * 960L), chunk.ptsNanos) }
        assertContentEquals(ShortArray(960) { (960 + it).toShort() }, chunks[1].samples)
    }

    @Test
    fun withoutAResyncSignalTheRechunkerDoesNotGuess() {
        // Only the capture loop knows when its times re-anchored; small irregularities pass through.
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val chunks = feed(rechunker, listOf(Read(0, 0), Read(nanos(1024) + 15_000_000, 1024)))
        assertEquals(listOf(0L, nanos(960)), chunks.map { it.ptsNanos })
    }

    @Test
    fun aForwardResyncMidChunkStartsTheChunkOnTheNewTimeline() {
        // The capture loop re-anchors (e.g. after a device change) 50 ms later than counted.
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val jump = 50_000_000L
        val chunks = feed(rechunker, listOf(Read(0, 0), Read(nanos(1024) + jump, 1024, resync = true), Read(nanos(2048) + jump, 2048)))
        // The 64 frames left from before the jump are dropped rather than stamped on the old timeline.
        assertEquals(3, chunks.size, "${chunks.map { it.ptsNanos }}")
        assertEquals(0L, chunks[0].ptsNanos)
        assertNear(nanos(1024) + jump, chunks[1].ptsNanos)
        assertNear(nanos(1024 + 960) + jump, chunks[2].ptsNanos)
        assertEquals(1024, chunks[1].samples[0].toInt())
    }

    @Test
    fun aSmallBackwardResyncSkipsLessThanAChunkAndStaysOnTheNewTimeline() {
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val jump = -10_000_000L
        val reads = (0 until 6).map { if (it == 0) Read(0, 0) else Read(nanos(it * 1024L) + jump, it * 1024, resync = it == 1) }
        val chunks = feed(rechunker, reads)
        assertMonotonic(chunks)
        // The chunk after the jump starts at the first new-timeline frame after the previous chunk's end.
        assertTrue(chunks[1].ptsNanos in nanos(960)..nanos(961), "${chunks[1].ptsNanos}")
        chunks.drop(1).forEach { chunk -> assertNear(nanos(chunk.samples[0].toLong()) + jump, chunk.ptsNanos) }
        // At most one chunk of audio is lost: 5 reads after the first.
        assertTrue(chunks.size >= (6 * 1024) / 960 - 1, "${chunks.size}")
    }

    @Test
    fun aLastingBackwardStepRealignsWithTheClockAfterABoundedSkip() {
        // E.g. a Bluetooth microphone switch: the clock says capture is 300 ms earlier than counted.
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val jump = -300_000_000L
        // Under 32 reads: sample values (the frame index) must fit a Short.
        val reads = (0 until 30).map {
            if (it < 8) Read(nanos(it * 1024L), it * 1024) else Read(nanos(it * 1024L) + jump, it * 1024, resync = it == 8)
        }
        val chunks = feed(rechunker, reads)
        assertMonotonic(chunks)
        // Back on the clock: no lasting offset, so audio stays aligned with video.
        val after = chunks.filter { it.samples[0] >= 8 * 1024 }
        assertTrue(after.size > 3)
        after.forEach { chunk -> assertNear(nanos(chunk.samples[0].toLong()) + jump, chunk.ptsNanos) }
        // Only the overlap (300 ms) and the partial chunk are skipped.
        assertTrue(chunks.size >= 30 * 1024 / 960 - 300 / 20 - 2, "${chunks.size}")
    }

    @Test
    fun driftResyncsNeverAccumulateAnOffset() {
        // A microphone clock running fast: every 8 reads the count is 25 ms ahead of the clock.
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val step = -25_000_000L
        val reads = (0 until 30).map { Read(nanos(it * 1024L) + (it / 8) * step, it * 1024, resync = it > 0 && it % 8 == 0) }
        val chunks = feed(rechunker, reads)
        assertMonotonic(chunks)
        chunks.forEach { chunk ->
            val sample = chunk.samples[0].toLong()
            assertNear(nanos(sample) + (sample / (8 * 1024)) * step, chunk.ptsNanos)
        }
    }

    @Test
    fun aJumpSecondsBackIsOneDiscontinuityNotSecondsOfSilence() {
        // Pathological: the capture times move 5 s ahead, then back (CaptureTimeline ignores a single bad stamp).
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val ahead = 5_000_000_000L
        val reads = (0 until 30).map {
            when (it) {
                in 1..4 -> Read(nanos(it * 1024L) + ahead, it * 1024, resync = it == 1)
                5 -> Read(nanos(5 * 1024L), 5 * 1024, resync = true)
                else -> Read(nanos(it * 1024L), it * 1024)
            }
        }
        val chunks = feed(rechunker, reads)
        // Audio keeps flowing on the clock; only the partial chunks dropped at each resync are lost.
        assertTrue(chunks.size >= 30 * 1024 / 960 - 2, "${chunks.size} chunks")
        chunks.filter { it.samples[0] >= 5 * 1024 }.forEach { assertNear(nanos(it.samples[0].toLong()), it.ptsNanos) }
    }
}
