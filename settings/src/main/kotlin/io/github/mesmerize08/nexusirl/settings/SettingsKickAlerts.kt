// Port of SettingsKickAlerts in Moblin/Various/Settings/SettingsStream.swift @97e26d2.
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
 * Which Kick alerts show, per stream: once for chat (`kickChatAlerts`) and once for toasts on the
 * streamer's screen (`kickToastAlerts`). Upstream's defaults: everything on, Kicks of any amount.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsKickAlerts.Serializer::class)
data class SettingsKickAlerts(
    val subscriptions: Boolean = true,
    val giftedSubscriptions: Boolean = true,
    val rewards: Boolean = true,
    val hosts: Boolean = true,
    val bans: Boolean = true,
    val kicks: Boolean = true,
    val minimumKicks: Int = 0,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsKickAlerts>(generatedSerializer())

    /** Upstream `isKicksEnabled`. */
    fun isKicksEnabled(amount: Int): Boolean = kicks && amount >= minimumKicks
}
