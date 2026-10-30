package io.github.mesmerize08.nexusirl.chat

import io.github.mesmerize08.nexusirl.settings.SettingsKickAlerts
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

/** Upstream's per-kind Kick alert switches, with the minimum Kicks for gifts. */
fun SettingsKickAlerts.allows(alert: KickAlert): Boolean = when (alert.type) {
    KickAlertType.SUBSCRIPTION -> subscriptions
    KickAlertType.GIFTED_SUBSCRIPTIONS -> giftedSubscriptions
    KickAlertType.REWARD -> rewards
    KickAlertType.HOST -> hosts
    KickAlertType.BAN -> bans
    KickAlertType.KICKS -> isKicksEnabled(alert.kicks ?: 0)
}
