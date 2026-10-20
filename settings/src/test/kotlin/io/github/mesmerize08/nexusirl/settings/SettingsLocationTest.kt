package io.github.mesmerize08.nexusirl.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsLocationTest {
    @Test
    fun privacyRegions() {
        val home = SettingsPrivacyRegion.around(59.3293, 18.0686, 1000.0)
        assertTrue(home.contains(59.3293, 18.0686))
        // About 400 m north and east: inside the 1 km box.
        assertTrue(home.contains(59.3329, 18.0756))
        // About 1 km north: outside.
        assertFalse(home.contains(59.3383, 18.0686))
        assertEquals(360 * 1000 / 40_075_000.0, home.latitudeDelta, 1e-12)
        // Upstream's default region is 30 degrees wide.
        assertTrue(SettingsPrivacyRegion(latitude = 10.0, longitude = 10.0).contains(24.0, -4.0))
        assertFalse(SettingsPrivacyRegion(latitude = 10.0, longitude = 10.0).contains(26.0, 10.0))
    }

    @Test
    fun iosSettings() {
        val database = Database.fromJson(
            """{"location": {"enabled": true, "distance": 1234.5, "resetWhenGoingLive": true, "desiredAccuracy": {"best": {}},
               "privacyRegions": [{"id": "0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11", "latitude": 1, "longitude": 2,
                                   "latitudeDelta": 0.01, "longitudeDelta": 0.02}]}}""",
        )
        val location = database.location
        assertTrue(location.enabled && location.resetWhenGoingLive)
        assertEquals(1234.5, location.distance)
        assertTrue(location.isInPrivacyRegion(1.0, 2.0))
        assertFalse(location.isInPrivacyRegion(1.1, 2.0))
        assertTrue(database.toJson().contains("\"desiredAccuracy\":{\"best\":{}}"))
    }
}
