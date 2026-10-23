package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.settings.SettingsTwitchAlerts

/** Upstream's per-kind Twitch alert switches, with the minimum bits for cheers. */
fun SettingsTwitchAlerts.allows(alert: TwitchAlert): Boolean = when (alert.type) {
    TwitchAlertType.FOLLOW -> follows
    TwitchAlertType.SUBSCRIPTION -> subscriptions
    TwitchAlertType.RESUBSCRIPTION -> resubscriptions
    TwitchAlertType.GIFT_SUBSCRIPTION -> giftSubscriptions
    TwitchAlertType.CHEER -> isBitsEnabled(alert.bits ?: 0)
    TwitchAlertType.RAID -> raids
    TwitchAlertType.REWARD -> rewards
}
