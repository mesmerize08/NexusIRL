package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.settings.SettingsTwitchAlerts
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwitchAlertFilterTest {
    private fun alert(type: TwitchAlertType, bits: Int? = null) = TwitchAlert(
        type,
        "Cool_User",
        "text",
        bits,
        ChatPost(
            platform = ChatPlatform.TWITCH,
            messageId = null,
            displayName = "Cool_User",
            user = "Cool_User",
            userId = null,
            userColor = null,
            userBadges = emptyList(),
            segments = emptyList(),
            isAction = false,
            isSubscriber = false,
            isModerator = false,
            bits = null,
            highlight = null,
        ),
    )

    @Test
    fun everythingByDefault() {
        val settings = SettingsTwitchAlerts()
        TwitchAlertType.entries.forEach { assertTrue(settings.allows(alert(it, bits = 1)), "$it") }
    }

    @Test
    fun perKindAndMinimumBits() {
        val settings = SettingsTwitchAlerts(follows = false, raids = false, minimumCheerBits = 100)
        assertFalse(settings.allows(alert(TwitchAlertType.FOLLOW)))
        assertFalse(settings.allows(alert(TwitchAlertType.RAID)))
        assertTrue(settings.allows(alert(TwitchAlertType.SUBSCRIPTION)))
        assertFalse(settings.allows(alert(TwitchAlertType.CHEER, bits = 99)))
        assertTrue(settings.allows(alert(TwitchAlertType.CHEER, bits = 100)))
        assertFalse(SettingsTwitchAlerts(cheers = false).allows(alert(TwitchAlertType.CHEER, bits = 5000)))
    }
}
