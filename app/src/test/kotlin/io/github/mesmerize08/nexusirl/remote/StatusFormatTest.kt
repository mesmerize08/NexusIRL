package io.github.mesmerize08.nexusirl.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusFormatTest {
    @Test
    fun bitrate() {
        assertEquals("5.0 Mbps", StatusFormat.bitsPerSecond(5_000_000))
        assertEquals("4.9 Mbps", StatusFormat.bitsPerSecond(4_912_000))
        assertEquals("800 kbps", StatusFormat.bitsPerSecond(800_400))
        assertEquals("0 bps", StatusFormat.bitsPerSecond(0))
    }

    @Test
    fun totals() {
        assertEquals("12.3 MB", StatusFormat.bytes(12_345_678))
        assertEquals("640 kB", StatusFormat.bytes(640_000))
        assertEquals("999 bytes", StatusFormat.bytes(999))
        assertEquals("1.5 GB", StatusFormat.bytes(1_500_000_000))
    }

    @Test
    fun uptime() {
        assertEquals("0s", StatusFormat.uptime(0))
        assertEquals("45s", StatusFormat.uptime(45))
        assertEquals("1m 5s", StatusFormat.uptime(65))
        assertEquals("1h", StatusFormat.uptime(3600))
        assertEquals("1d 1h 1m 1s", StatusFormat.uptime(90_061))
    }

    @Test
    fun matchesTheHarnessPattern() {
        // upstream tests/utils/moblin.py RE_BITRATE_STATUS
        val pattern = Regex("""(\S+) (\S+) ((\S+) )?\((\S+) (\S+)\)""")
        val message = "${StatusFormat.bitsPerSecond(5_012_000)} (${StatusFormat.bytes(12_300_000)})"
        assertTrue(pattern.matches(message), message)
    }
}
