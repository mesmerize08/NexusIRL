package io.github.mesmerize08.nexusirl.obs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObsSceneSwitcherTest {
    private var now = 0L
    private val switcher = ObsSceneSwitcher { now }

    private fun update(current: String, disconnected: Boolean = false, droppedPackets: Boolean = false) =
        switcher.update(current, mainScene = "Main", brbScene = "BRB", disconnected = disconnected, droppedPackets = droppedPackets)

    /** The target, as each switch succeeds at once. */
    private fun switchTo(current: String, disconnected: Boolean = false, droppedPackets: Boolean = false) =
        update(current, disconnected, droppedPackets)?.also { switcher.finished(it.scene, success = true) }?.scene

    @Test
    fun brbWhileDisconnectedAndBackToMainOnceConnected() {
        assertNull(switchTo("Main"))
        assertEquals("BRB", switchTo("Main", disconnected = true))
        assertNull(switchTo("BRB", disconnected = true))
        assertEquals("Main", switchTo("BRB"))
    }

    @Test
    fun otherScenesAreLeftAlone() {
        assertNull(switchTo("Starting soon", disconnected = true))
        assertNull(switchTo("Starting soon"))
    }

    @Test
    fun droppedPacketsKeepBrbForFifteenSeconds() {
        assertEquals("BRB", switchTo("Main", droppedPackets = true))
        now = 14_000
        assertNull(switchTo("BRB"))
        now = 15_000
        assertEquals("Main", switchTo("BRB"))
        assertNull(switchTo("Main"))
    }

    @Test
    fun noSecondRequestWhileOneIsInFlight() {
        assertEquals(ObsSceneSwitch("BRB", toast = true), update("Main", disconnected = true))
        now = 1_000
        assertNull(update("Main", disconnected = true))
        switcher.finished("BRB", success = true)
        assertNull(update("BRB", disconnected = true))
    }

    @Test
    fun failedSwitchIsRetriedLaterWithoutAnotherToast() {
        assertEquals(ObsSceneSwitch("BRB", toast = true), update("Main", disconnected = true))
        switcher.finished("BRB", success = false)
        now = 1_000
        assertNull(update("Main", disconnected = true))
        now = 9_000
        assertNull(update("Main", disconnected = true))
        now = 10_000
        assertEquals(ObsSceneSwitch("BRB", toast = false), update("Main", disconnected = true))
        switcher.finished("BRB", success = true)
        // Working again: a new target, so a new toast.
        assertEquals(ObsSceneSwitch("Main", toast = true), update("BRB"))
    }
}
