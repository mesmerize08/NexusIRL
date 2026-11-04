package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
        assertEquals(listOf(77L), collect(rechunker, ShortArray(1024), 1024, 77).map { it.ptsNanos })
    }
}
