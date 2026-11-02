package io.github.mesmerize08.nexusirl.media.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MicrophoneChoiceTest {
    private val builtIn = "built-in" to 10
    private val car = "car" to 20
    private val usb = "usb" to 40

    private fun choose(
        mics: List<Pair<String, Int>>,
        current: String?,
        previous: Set<String> = mics.map { it.first }.toSet(),
        preferred: String? = null,
        autoSwitch: Boolean = true,
    ) = MicrophoneChoice.choose(mics, { it.first }, { it.second }, current, previous, preferred, autoSwitch)?.first

    @Test
    fun startsWithTheRememberedMicWhenConnected() {
        assertEquals("built-in", choose(listOf(builtIn, usb), current = null, preferred = "built-in"))
        assertEquals("usb", choose(listOf(builtIn, usb), current = null, preferred = "gone"))
        assertNull(choose(emptyList(), current = null))
    }

    @Test
    fun aChosenMicStaysWhileNothingNewIsConnected() {
        // Upstream manualSelectMicById: picking the built-in mic with USB connected sticks.
        assertEquals("built-in", choose(listOf(builtIn, usb), current = "built-in"))
    }

    @Test
    fun aNewHigherRankedMicTakesOverWithAutoSwitch() {
        assertEquals("usb", choose(listOf(builtIn, usb), current = "built-in", previous = setOf("built-in")))
        assertEquals(
            "built-in",
            choose(listOf(builtIn, usb), current = "built-in", previous = setOf("built-in"), autoSwitch = false),
        )
    }

    @Test
    fun aNewLowerRankedMicDoesNotTakeOver() {
        assertEquals("usb", choose(listOf(builtIn, car, usb), current = "usb", previous = setOf("built-in", "usb")))
    }

    @Test
    fun whenTheMicGoesTheHighestRankedRemainingOneIsUsed() {
        assertEquals("car", choose(listOf(builtIn, car), current = "usb", previous = setOf("built-in", "car", "usb")))
        assertEquals("car", choose(listOf(builtIn, car), current = "usb", autoSwitch = false))
    }
}
