package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.settings.SettingsTwitchAlerts
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwitchAlertFilterTest {
    private fun alert(type: TwitchAlertType, bits: Int? = null, streak: Int? = null, sharedChat: Boolean = false) = TwitchAlert(
        type,
        "Cool_User",
        "text",
        bits,
        post = ChatPost(
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
        streak = streak,
        sharedChat = sharedChat,
    )

    @Test
    fun everythingByDefault() {
        val settings = SettingsTwitchAlerts()
        TwitchAlertType.entries.forEach { assertTrue(settings.allows(alert(it, bits = 1, streak = 5)), "$it") }
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

    /** Upstream `isWatchStreakEnabled`: on, and at least the minimum streak (5 by default). */
    @Test
    fun watchStreaks() {
        assertFalse(SettingsTwitchAlerts().allows(alert(TwitchAlertType.WATCH_STREAK, streak = 4)))
        assertTrue(SettingsTwitchAlerts().allows(alert(TwitchAlertType.WATCH_STREAK, streak = 5)))
        assertTrue(SettingsTwitchAlerts(minimumWatchStreak = 1).allows(alert(TwitchAlertType.WATCH_STREAK, streak = 1)))
        assertFalse(SettingsTwitchAlerts(watchStreaks = false).allows(alert(TwitchAlertType.WATCH_STREAK, streak = 50)))
    }

    /** Upstream `isTwitchSharedChatAlertEnabled`: alerts from a shared chat partner only when shared chat is on (off by default). */
    @Test
    fun sharedChat() {
        assertFalse(SettingsTwitchAlerts().allows(alert(TwitchAlertType.SUBSCRIPTION, sharedChat = true)))
        assertTrue(SettingsTwitchAlerts(sharedChat = true).allows(alert(TwitchAlertType.SUBSCRIPTION, sharedChat = true)))
        assertFalse(
            SettingsTwitchAlerts(sharedChat = true, subscriptions = false).allows(alert(TwitchAlertType.SUBSCRIPTION, sharedChat = true)),
        )
        assertTrue(SettingsTwitchAlerts().allows(alert(TwitchAlertType.SUBSCRIPTION)))
    }
}
