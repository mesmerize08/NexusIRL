package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BufferedStatsTest {
    @Test
    fun nothingCountedGivesNull() {
        assertNull(BufferedStats().getStats(10.0))
    }

    @Test
    fun nullBeforeFiveSecondsSinceLastReport() {
        val stats = BufferedStats()
        stats.incrementDuplicated()
        assertNull(stats.getStats(4.9))
    }

    @Test
    fun reportsCountsThenResets() {
        val stats = BufferedStats()
        stats.incrementDuplicated()
        stats.incrementDuplicated()
        stats.incrementDropped(3)
        assertEquals(2 to 3, stats.getStats(5.1))
        assertNull(stats.getStats(11.0))
    }
}
