package io.github.mesmerize08.nexusirl.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TwitchBannersViewTest {
    /** Upstream `formatPercentage`: no decimals, rounded toward zero. */
    @Test
    fun percentage() {
        assertEquals("0%", formatBannerPercentage(0.0))
        assertEquals("66%", formatBannerPercentage(2.0 / 3))
        assertEquals("99%", formatBannerPercentage(0.999))
        assertEquals("100%", formatBannerPercentage(1.0))
    }
}
