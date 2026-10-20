package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickButtonsTest {
    @Test
    fun defaultsLikeUpstream() {
        val buttons = addMissingQuickButtons(emptyList())
        assertEquals(61, buttons.size)
        assertEquals(QuickButtonType.entries.size - 3, buttons.map { it.type }.toSet().size)
        val torch = buttons.first()
        assertEquals(QuickButtonType.TORCH, torch.type)
        assertEquals("flashlight.on.fill", torch.imageOn)
        assertEquals("flashlight.off.fill", torch.imageOff)
        assertEquals(1, torch.page)
        val live = buttons.first { it.type == QuickButtonType.LIVE }
        assertEquals("Stream", live.name)
        assertEquals(live.imageOn, live.imageOff)
        assertEquals(2, buttons.first { it.type == QuickButtonType.GRAY_SCALE }.page)
        assertEquals(3, buttons.first { it.type == QuickButtonType.OBS }.page)
        assertTrue(buttons.all { it.enabled && !it.isOn })
    }

    @Test
    fun keepsIosButtons() {
        val database = Database.fromJson(
            """{"globalButtons": [
                 {"name": "Gray", "id": "0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11", "type": "Gray scale", "systemImageNameOn": "x",
                  "systemImageNameOff": "y", "isOn": true, "enabled": false, "backgroundColor": {"red": 1, "green": 2, "blue": 3}, "page": 4},
                 {"type": "Something new", "name": "?"}
               ],
               "quickButtons": {"twoColumns": false}}""",
        )
        assertEquals(QuickButtonType.UNKNOWN, database.quickButtons[1].type)
        val buttons = addMissingQuickButtons(database.quickButtons)
        val gray = buttons.first()
        assertEquals(QuickButtonType.GRAY_SCALE, gray.type)
        assertEquals("Gray scale", gray.name)
        assertEquals("moon.fill", gray.imageOn)
        assertFalse(gray.isOn)
        assertFalse(gray.enabled)
        assertEquals(4, gray.page)
        assertEquals(RgbColor(1, 2, 3), gray.backgroundColor)
        assertEquals(61, buttons.size)
        val json = SwiftJson.parseToJsonElement(database.copy(quickButtons = buttons).toJson()).jsonObject
        assertEquals("false", json["quickButtons"]!!.jsonObject["twoColumns"].toString())
        assertEquals("\"Gray scale\"", json["globalButtons"]!!.jsonArray[0].jsonObject["type"].toString())
    }
}
