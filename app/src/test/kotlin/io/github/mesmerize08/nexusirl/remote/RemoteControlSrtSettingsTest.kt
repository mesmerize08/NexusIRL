package io.github.mesmerize08.nexusirl.remote

import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettingsSrt
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettingsSrtConnectionPriority
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.settings.SettingsStreamSrtConnectionPriorities
import io.github.mesmerize08.nexusirl.settings.SettingsStreamSrtConnectionPriority
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteControlSrtSettingsTest {
    @Test
    fun currentStreamsPriorities() {
        // Upstream handleGetSettings: id, name, priority and enabled of each entry; relay ids stay private.
        val entry = SettingsStreamSrtConnectionPriority(name = "Pixel", priority = 4, enabled = false, relayId = UUID.randomUUID())
        val stream = SettingsStream().let {
            it.copy(srt = it.srt.copy(connectionPriorities = SettingsStreamSrtConnectionPriorities(true, listOf(entry))))
        }
        assertEquals(
            RemoteControlSettingsSrt(true, listOf(RemoteControlSettingsSrtConnectionPriority(entry.id, "Pixel", 4, false))),
            remoteControlSrtSettings(stream),
        )
        assertEquals(RemoteControlSettingsSrt(false, emptyList()), remoteControlSrtSettings(null))
    }
}
