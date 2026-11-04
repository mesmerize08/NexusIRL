package io.github.mesmerize08.nexusirl.obs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObsSceneSwitcherTest {
    private var now = 0L
    private val switcher = ObsSceneSwitcher { now }

    private fun update(current: String, disconnected: Boolean = false, droppedPackets: Boolean = false) =
        switcher.update(current, mainScene = "Main", brbScene = "BRB", disconnected = disconnected, droppedPackets = droppedPackets)

    @Test
    fun brbWhileDisconnectedAndBackToMainOnceConnected() {
        assertNull(update("Main"))
        assertEquals("BRB", update("Main", disconnected = true))
        assertNull(update("BRB", disconnected = true))
        assertEquals("Main", update("BRB"))
    }

    @Test
    fun otherScenesAreLeftAlone() {
        assertNull(update("Starting soon", disconnected = true))
        assertNull(update("Starting soon"))
    }

    @Test
    fun droppedPacketsKeepBrbForFifteenSeconds() {
        assertEquals("BRB", update("Main", droppedPackets = true))
        now = 14_000
        assertNull(update("BRB"))
        now = 15_000
        assertEquals("Main", update("BRB"))
        assertNull(update("Main"))
    }
}
