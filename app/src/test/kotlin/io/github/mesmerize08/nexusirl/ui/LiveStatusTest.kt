package io.github.mesmerize08.nexusirl.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveStatusTest {
    @Test
    fun uptime() {
        assertEquals("0:00", formatUptime(0))
        assertEquals("2:03", formatUptime(123))
        assertEquals("1:02:03", formatUptime(3723))
    }

    @Test
    fun audioMeter() {
        assertEquals(0f, audioMeterFraction(-160f))
        assertEquals(0.5f, audioMeterFraction(-30f))
        assertEquals(1f, audioMeterFraction(3f))
    }
}
