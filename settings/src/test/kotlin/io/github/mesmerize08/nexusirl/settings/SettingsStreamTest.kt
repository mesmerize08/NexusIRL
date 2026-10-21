package io.github.mesmerize08.nexusirl.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStreamTest {
    @Test
    fun portraitSwapsCanvas() {
        val landscape = SettingsStream(resolution = StreamResolution.R1920X1080)
        assertEquals(1920 to 1080, landscape.canvasWidth to landscape.canvasHeight)
        val portrait = landscape.copy(portrait = true)
        assertEquals(1080 to 1920, portrait.canvasWidth to portrait.canvasHeight)
    }
}
