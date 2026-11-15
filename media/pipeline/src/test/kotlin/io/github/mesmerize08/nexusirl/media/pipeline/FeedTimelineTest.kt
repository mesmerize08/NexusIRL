package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedTimelineTest {
    private val timeline = FeedTimeline(48_000)

    @Test
    fun nothingIsCutBeforeTheFirstChunk() {
        assertEquals(0, timeline.framesToCut(Long.MIN_VALUE + 1, 1024))
        assertEquals(0, timeline.framesToCut(0, 1024))
    }

    @Test
    fun aChunkRightAfterTheLastIsKept() {
        timeline.fed(1_000_000_000, 1024)
        assertEquals(1_021_333_333, timeline.endNanos)
        assertEquals(0, timeline.framesToCut(1_021_333_333, 1024))
        assertEquals(0, timeline.framesToCut(1_100_000_000, 1024))
    }

    /** A microphone read whose time stepped back (re-anchored on the clock) loses its overlap, rounded up. */
    @Test
    fun anOverlapIsCut() {
        timeline.fed(1_000_000_000, 1024)
        // 5 ms before the end: 240 frames.
        assertEquals(240, timeline.framesToCut(1_016_333_333, 1024))
        // 1 ns before: one frame.
        assertEquals(1, timeline.framesToCut(1_021_333_332, 1024))
    }

    @Test
    fun aChunkEntirelyBeforeTheEndIsSkipped() {
        timeline.fed(1_000_000_000, 1024)
        assertEquals(1024, timeline.framesToCut(990_000_000, 1024))
        assertEquals(1024, timeline.framesToCut(Long.MIN_VALUE + 1, 1024))
    }
}
