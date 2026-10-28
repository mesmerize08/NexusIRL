// Port of SettingsTwitchAlerts in Moblin/Various/Settings/SettingsStream.swift @97e26d2.
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Which Twitch alerts show, per stream: once for chat (`twitchChatAlerts`) and once for toasts on
 * the streamer's screen (`twitchToastAlerts`). Upstream's defaults: everything on, cheers of any
 * size, watch streaks from 5, shared chat off.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsTwitchAlerts.Serializer::class)
data class SettingsTwitchAlerts(
    val follows: Boolean = true,
    val subscriptions: Boolean = true,
    val giftSubscriptions: Boolean = true,
    val resubscriptions: Boolean = true,
    val rewards: Boolean = true,
    val raids: Boolean = true,
    val cheers: Boolean = true,
    val minimumCheerBits: Int = 0,
    val watchStreaks: Boolean = true,
    val minimumWatchStreak: Int = 5,
    val sharedChat: Boolean = false,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsTwitchAlerts>(generatedSerializer())

    /** Upstream `isBitsEnabled`. */
    fun isBitsEnabled(amount: Int): Boolean = cheers && amount >= minimumCheerBits
}
