package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkBacklogTest {
    private val backlog = ChunkBacklog(capacity = 3)

    /** What a codec took: the first sample (the chunk's number here) and the PTS. */
    private val queued = ArrayList<Pair<Int, Long>>()

    /** A codec with [free] input buffers. */
    private fun offer(number: Int, free: Int) {
        var left = free
        val chunk = ShortArray(4) { number.toShort() }
        backlog.offer(chunk, number * 1000L) { pcm, pts ->
            if (left == 0) return@offer false
            left--
            queued += pcm[0].toInt() to pts
            true
        }
        // The caller reuses its array: what waits must be a copy.
        chunk.fill(-1)
    }

    @Test
    fun aFreeCodecTakesEveryChunk() {
        for (i in 0 until 5) offer(i, free = 1)
        assertEquals((0 until 5).map { it to it * 1000L }, queued)
        assertEquals(0, backlog.size)
    }

    /** A codec that has no input buffer for a while gets the chunks later, in order: nothing is lost. */
    @Test
    fun chunksWaitForABusyCodec() {
        offer(0, free = 0)
        offer(1, free = 0)
        assertEquals(2, backlog.size)
        offer(2, free = 3)
        assertEquals(listOf(0 to 0L, 1 to 1000L, 2 to 2000L), queued)
        assertEquals(0, backlog.size)
        assertEquals(0, backlog.dropped)
    }

    @Test
    fun aCodecThatTakesSomeLeavesTheRestInOrder() {
        offer(0, free = 0)
        offer(1, free = 0)
        offer(2, free = 1)
        assertEquals(listOf(0 to 0L), queued)
        offer(3, free = 5)
        assertEquals(listOf(0, 1, 2, 3), queued.map { it.first })
    }

    /** Beyond the capacity the oldest waiting chunk is dropped and counted. */
    @Test
    fun theOldestIsDroppedWhenFull() {
        for (i in 0 until 5) offer(i, free = 0)
        assertEquals(3, backlog.size)
        assertEquals(2, backlog.dropped)
        offer(5, free = 10)
        assertEquals(listOf(2, 3, 4, 5), queued.map { it.first })
    }
}
