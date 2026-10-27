// Port of Moblin/Various/Settings/SettingsChat.swift `SettingsChat` and SettingsScene.swift
// `SettingsWidgetChat` @97e26d2 (v1 subsets). Keys and defaults match upstream.
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SDouble
import io.github.mesmerize08.nexusirl.core.codable.SFloat
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/** Upstream `SettingsChat`: the chat shown over the preview. [height] and [width] are fractions of the screen. */
@KeepGeneratedSerializer
@Serializable(with = SettingsChat.Serializer::class)
data class SettingsChat(
    val fontSize: SFloat = 19f,
    val usernameColor: RgbColor = RgbColor(255, 163, 0),
    val sameUsernameColor: Boolean = false,
    val messageColor: RgbColor = RgbColor(255, 255, 255),
    val backgroundColor: RgbColor = RgbColor(0, 0, 0),
    val backgroundColorEnabled: Boolean = false,
    val shadowColor: RgbColor = RgbColor(0, 0, 0),
    val shadowColorEnabled: Boolean = true,
    val boldUsername: Boolean = true,
    val boldMessage: Boolean = true,
    val timestampColor: RgbColor = RgbColor(180, 180, 180),
    val timestampColorEnabled: Boolean = false,
    val height: SDouble = 0.7,
    val width: SDouble = 1.0,
    val maximumAge: Int = 30,
    val maximumAgeEnabled: Boolean = false,
    val enabled: Boolean = true,
    val mirrored: Boolean = false,
    val badges: Boolean = true,
    val botEnabled: Boolean = false,
    val botCommandPermissions: SettingsChatBotPermissions = SettingsChatBotPermissions(),
    val aliases: List<SettingsChatBotAlias> = emptyList(),
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsChat>(generatedSerializer())
}

/** Upstream `SettingsWidgetChat`: a chat drawn into the stream. */
@KeepGeneratedSerializer
@Serializable(with = SettingsWidgetChat.Serializer::class)
data class SettingsWidgetChat(
    val fontSize: SFloat = 19f,
    val usernameColor: RgbColor = RgbColor(255, 163, 0),
    val messageColor: RgbColor = RgbColor(255, 255, 255),
    val backgroundColor: RgbColor = RgbColor(0, 0, 0),
    val backgroundColorEnabled: Boolean = false,
    val shadowColor: RgbColor = RgbColor(0, 0, 0),
    val shadowColorEnabled: Boolean = true,
    val boldUsername: Boolean = true,
    val boldMessage: Boolean = true,
    val badges: Boolean = true,
    val height: SFloat = 1f,
    val maximumNumberOfMessages: Int = 5,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsWidgetChat>(generatedSerializer())
}
