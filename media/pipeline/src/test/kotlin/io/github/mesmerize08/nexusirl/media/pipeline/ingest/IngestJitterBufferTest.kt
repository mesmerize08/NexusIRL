package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestJitterBufferTest {
    private fun unit(key: Boolean, dts: Long, pts: Long = dts, size: Int = 1) = IngestVideoAccessUnit(key, pts, dts, ByteArray(size))

    @Test
    fun takesInDtsOrderWhenDue() {
        val buffer = IngestJitterBuffer()
        buffer.add(unit(true, 10))
        buffer.add(unit(false, 20))
        buffer.add(unit(false, 30))
        assertNull(buffer.takeDue(9))
        assertEquals(10, buffer.takeDue(25)?.dtsNanos)
        assertEquals(20, buffer.takeDue(25)?.dtsNanos)
        assertNull(buffer.takeDue(25))
        assertEquals(30, buffer.takeDue(100)?.dtsNanos)
        assertNull(buffer.takeDue(100))
        assertEquals(0, buffer.size)
    }

    @Test
    fun overflowKeepsTheLastGop() {
        val buffer = IngestJitterBuffer(maxFrames = 10)
        var dts = 0L
        buffer.add(unit(true, dts++))
        repeat(5) { buffer.add(unit(false, dts++)) }
        val secondKey = dts
        buffer.add(unit(true, dts++))
        repeat(5) { buffer.add(unit(false, dts++)) }
        assertEquals(6, buffer.size)
        assertEquals(secondKey, buffer.takeDue(Long.MAX_VALUE)?.dtsNanos)
    }

    @Test
    fun overflowWithoutKeyFrameWaitsForNext() {
        val buffer = IngestJitterBuffer(maxFrames = 4)
        buffer.add(unit(true, 0))
        for (i in 1L..5L) buffer.add(unit(false, i))
        assertEquals(0, buffer.size)
        buffer.add(unit(false, 6))
        assertEquals(0, buffer.size)
        buffer.add(unit(true, 7))
        buffer.add(unit(false, 8))
        assertEquals(2, buffer.size)
        assertEquals(7, buffer.takeDue(Long.MAX_VALUE)?.dtsNanos)
        assertEquals(8, buffer.takeDue(Long.MAX_VALUE)?.dtsNanos)
    }

    @Test
    fun byteBound() {
        val buffer = IngestJitterBuffer(maxBytes = 1000)
        for (i in 0L until 20L) {
            buffer.add(unit(i % 4 == 0L, i, size = 400))
            assertTrue(buffer.bytes <= 1000, "held ${buffer.bytes}")
        }
        buffer.add(unit(true, 100, size = 400))
        assertTrue(buffer.size > 0 && buffer.bytes <= 1000)
    }

    @Test
    fun newestPts() {
        val buffer = IngestJitterBuffer()
        assertNull(buffer.newestPtsNanos())
        buffer.add(unit(true, 0, pts = 20))
        buffer.add(unit(false, 1, pts = 40))
        buffer.add(unit(false, 2, pts = 30))
        assertEquals(40, buffer.newestPtsNanos())
        buffer.takeDue(1)
        buffer.takeDue(1)
        assertEquals(30, buffer.newestPtsNanos())
    }
}
