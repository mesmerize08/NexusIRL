package io.github.mesmerize08.nexusirl.transport.rtmp

import kotlin.test.Test
import kotlin.test.assertEquals

class RtmpUnsentLimitTest {
    private val mib = 1024L * 1024

    @Test
    fun aLargeHeapKeepsUpstreamsLimit() {
        assertEquals(100_000_000, maximumUnsentBytes(4096 * mib))
    }

    @Test
    fun aPhoneHeapAllowsAnEighthPerDestination() {
        // A typical app heap without largeHeap: four stalled destinations stay within half of it.
        assertEquals(64 * mib, maximumUnsentBytes(512 * mib))
        assertEquals(32 * mib, maximumUnsentBytes(256 * mib))
    }

    @Test
    fun aSmallHeapStillAllowsSecondsOfVideo() {
        // 16 MB is about 20 seconds at 6 Mbps: enough to ride out a short stall before disconnecting.
        assertEquals(16 * mib, maximumUnsentBytes(64 * mib))
    }
}
