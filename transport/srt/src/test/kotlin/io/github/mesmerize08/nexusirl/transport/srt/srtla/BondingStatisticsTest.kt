package io.github.mesmerize08.nexusirl.transport.srt.srtla

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BondingStatisticsTest {
    @Test
    fun sharesAddUpTo100() {
        val statistics = formatBondingStatistics(
            listOf(BondingConnection("Cellular", 1, 48), BondingConnection("WiFi", 1, 12), BondingConnection("Relay", 1, 30)),
        )
        assertEquals("33% Cellular, 33% WiFi, 34% Relay", statistics?.usage)
        assertEquals("48 ms Cellular, 12 ms WiFi, 30 ms Relay", statistics?.rtts)
    }

    @Test
    fun idlePaths() {
        assertEquals(
            "0% Cellular, 100% WiFi",
            formatBondingStatistics(listOf(BondingConnection("Cellular", 0, 0), BondingConnection("WiFi", 0, 0)))?.usage,
        )
        assertEquals(
            "60% Cellular, 40% WiFi",
            formatBondingStatistics(listOf(BondingConnection("Cellular", 600, 0), BondingConnection("WiFi", 400, 0)))?.usage,
        )
        assertNull(formatBondingStatistics(emptyList()))
    }
}
