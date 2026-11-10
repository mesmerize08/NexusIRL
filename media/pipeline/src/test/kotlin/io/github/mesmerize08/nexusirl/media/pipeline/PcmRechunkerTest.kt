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

    /** Feeds 1024-frame reads like the capture loop; returns every chunk's samples and time. */
    private fun feed(rechunker: PcmRechunker, reads: List<Pair<Long, Int>>): List<Chunk> = reads.flatMap { (pts, first) ->
        collect(rechunker, ShortArray(1024) { (first + it).toShort() }, 1024, pts)
    }

    @Test
    fun continuousInputKeepsEvenlySpacedChunks() {
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val chunks = feed(rechunker, (0 until 15).map { nanos(it * 1024L) to it * 1024 })
        assertEquals(16, chunks.size)
        // Within a microsecond: times are whole nanoseconds, so a 1 ns rounding step is expected.
        chunks.forEachIndexed { index, chunk -> assertNear(nanos(index * 960L), chunk.ptsNanos) }
        assertContentEquals(ShortArray(960) { (960 + it).toShort() }, chunks[1].samples)
    }

    @Test
    fun aForwardResyncMidChunkStartsTheChunkOnTheNewTimeline() {
        // The capture loop re-anchors (e.g. after a device change) 50 ms later than counted.
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val jump = 50_000_000L
        val chunks = feed(rechunker, listOf(0L to 0, nanos(1024) + jump to 1024, nanos(2048) + jump to 2048))
        // The 64 frames left from before the jump are dropped rather than stamped on the old timeline.
        assertEquals(3, chunks.size, "${chunks.map { it.ptsNanos }}")
        assertEquals(0L, chunks[0].ptsNanos)
        assertNear(nanos(1024) + jump, chunks[1].ptsNanos)
        assertNear(nanos(1024 + 960) + jump, chunks[2].ptsNanos)
        assertEquals(1024, chunks[1].samples[0].toInt())
    }

    @Test
    fun aBackwardResyncMidChunkNeverGoesBackInTime() {
        val rechunker = PcmRechunker(channels = 1, framesPerChunk = 960, sampleRate = 48_000)
        val jump = -30_000_000L
        val reads = (0 until 6).map { if (it == 0) 0L to 0 else nanos(it * 1024L) + jump to it * 1024 }
        val chunks = feed(rechunker, reads)
        val times = chunks.map { it.ptsNanos }
        // Each chunk starts no earlier than the previous one ends: no overlap, nothing backwards.
        times.zipWithNext().forEach { (a, b) -> assertTrue(b >= a + nanos(960) - 1_000, "$times") }
        // After the jump, chunks are on the new timeline: a sample's time is its read's time plus its offset.
        val after = chunks.drop(1)
        assertTrue(after.isNotEmpty())
        after.forEach { chunk ->
            val sample = chunk.samples[0].toLong()
            assertNear(nanos(sample) + jump, chunk.ptsNanos)
        }
    }
}
