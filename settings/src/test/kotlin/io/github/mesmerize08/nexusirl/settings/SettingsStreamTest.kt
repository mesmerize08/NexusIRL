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

    @Test
    fun defaultBitratePresetsLikeUpstream() {
        assertEquals(
            listOf(15, 12, 9, 7, 6, 5, 4, 3, 2, 1).map { it * 1_000_000L },
            SettingsBitratePreset.defaults().map { it.bitrate },
        )
    }

    @Test
    fun bitratePresetsRoundTripIosJson() {
        val json = """{"streams":[],"bitratePresets":[{"id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","bitrate":3000000}]}"""
        val database = io.github.mesmerize08.nexusirl.core.codable.SwiftJson.decodeFromString(Database.Serializer, json)
        assertEquals(3_000_000L, database.bitratePresets.single().bitrate)
    }

    @Test
    fun twitchAlertSettingsFromIos() {
        val alerts = """"twitchToastAlerts":{"follows":false,"minimumCheerBits":100},"twitchChatAlerts":{"raids":false}"""
        val json = """{"streams":[{"name":"S",$alerts}]}"""
        val database = io.github.mesmerize08.nexusirl.core.codable.SwiftJson.decodeFromString(Database.Serializer, json)
        val stream = database.streams.single()
        kotlin.test.assertFalse(stream.twitchToastAlerts.follows)
        kotlin.test.assertTrue(stream.twitchToastAlerts.subscriptions)
        kotlin.test.assertFalse(stream.twitchToastAlerts.isBitsEnabled(99))
        kotlin.test.assertTrue(stream.twitchToastAlerts.isBitsEnabled(100))
        kotlin.test.assertFalse(stream.twitchChatAlerts.raids)
        // Missing in older exports: upstream's defaults.
        kotlin.test.assertTrue(SettingsStream().twitchChatAlerts.follows)
    }

    @Test
    fun kickAlertSettingsFromIos() {
        val alerts = """"kickToastAlerts":{"hosts":false,"minimumKicks":100,"future":1},"kickChatAlerts":{"bans":false}"""
        val json = """{"streams":[{"name":"S",$alerts}]}"""
        val database = io.github.mesmerize08.nexusirl.core.codable.SwiftJson.decodeFromString(Database.Serializer, json)
        val stream = database.streams.single()
        kotlin.test.assertFalse(stream.kickToastAlerts.hosts)
        kotlin.test.assertTrue(stream.kickToastAlerts.subscriptions)
        kotlin.test.assertFalse(stream.kickToastAlerts.isKicksEnabled(99))
        kotlin.test.assertTrue(stream.kickToastAlerts.isKicksEnabled(100))
        kotlin.test.assertFalse(stream.kickChatAlerts.bans)
        // Unknown keys survive a round trip, as everywhere in the settings.
        kotlin.test.assertTrue("\"future\":1" in database.toJson())
        kotlin.test.assertTrue(SettingsStream().kickChatAlerts.bans)
    }
}
