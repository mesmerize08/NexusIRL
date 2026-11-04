package io.github.mesmerize08.nexusirl.obs

import kotlin.test.Test
import kotlin.test.assertEquals

class ObsStateTest {
    @Test
    fun statusTextLikeUpstream() {
        assertEquals("Not configured", ObsState().statusText)
        val connected = ObsState(configured = true, connected = true, currentScene = "Main")
        assertEquals("Main", connected.statusText)
        assertEquals("Main (Streaming)", connected.copy(streaming = true).statusText)
        assertEquals("Main (Recording)", connected.copy(recording = true).statusText)
        assertEquals("Main (Streaming, Recording)", connected.copy(streaming = true, recording = true).statusText)
        assertEquals("Authentication failed.", ObsState(configured = true, error = "Authentication failed.").statusText)
        assertEquals("Connecting", ObsState(configured = true).statusText)
    }

    @Test
    fun audioLevelsText() {
        assertEquals("-20.0 dB, Muted", obsAudioLevelsText(listOf(-20f, Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun outputStateFromStatus() {
        assertEquals(ObsOutputState.STARTED, outputState(active = true, state = null))
        assertEquals(ObsOutputState.STOPPED, outputState(active = false, state = null))
        assertEquals(ObsOutputState.STOPPING, outputState(active = true, state = ObsOutputState.STOPPING))
    }

    @Test
    fun audioInputsKeepTheGlobalMicsWithoutSceneItems() {
        val inputs = listOf("Mic/Aux", "Moblin", "Music", "Hidden")
        val items = listOf(ObsSceneItem("Moblin", "ffmpeg_source", true), ObsSceneItem("Hidden", "ffmpeg_source", false))
        assertEquals(listOf("Mic/Aux", "Moblin"), obsSceneAudioInputNames(inputs, mics = listOf("Mic/Aux"), items))
        assertEquals(listOf("Mic/Aux"), obsSceneAudioInputNames(inputs, mics = listOf("Mic/Aux"), emptyList()))
    }
}
