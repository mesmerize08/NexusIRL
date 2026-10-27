// Port of SettingsChatBotPermissionsCommand, SettingsChatBotPermissions, SettingsChatBotAlias and
// SettingsChatBotCommandSnapshot in
// Moblin/Various/Settings/SettingsChat.swift @97e26d2. Keys and defaults match upstream.
package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftPreserving
import io.github.mesmerize08.nexusirl.core.codable.SwiftPreservingSerializer
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnum
import io.github.mesmerize08.nexusirl.core.codable.SwiftRawValueEnumSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * Who may use one chat bot command. The channel owner always may. [cooldown] (seconds) only
 * applies to subscribers and others, and [sendChatMessages] makes the bot answer in chat when a
 * command is refused or on cooldown.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsChatBotPermissionsCommand.Serializer::class)
data class SettingsChatBotPermissionsCommand(
    val moderatorsEnabled: Boolean = true,
    val subscribersEnabled: Boolean = false,
    val minimumSubscriberTier: Int = 1,
    val othersEnabled: Boolean = false,
    val sendChatMessages: Boolean = false,
    val cooldown: Int? = null,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsChatBotPermissionsCommand>(generatedSerializer())
}

private val MODERATORS_OFF = SettingsChatBotPermissionsCommand(moderatorsEnabled = false)

/**
 * Upstream `SettingsChatBotPermissions`: one entry per command group, all of them kept so iOS
 * settings survive a round trip. Scene, stream and macro start with moderators off, as upstream's
 * defaults for a new install.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsChatBotPermissions.Serializer::class)
data class SettingsChatBotPermissions(
    val tts: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val fix: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val map: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val alert: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val fax: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val snapshot: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val filter: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val zoom: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val tesla: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val audio: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val reaction: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val scene: SettingsChatBotPermissionsCommand = MODERATORS_OFF,
    val stream: SettingsChatBotPermissionsCommand = MODERATORS_OFF,
    val widget: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val location: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val ai: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val twitch: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val gimbal: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val macro: SettingsChatBotPermissionsCommand = MODERATORS_OFF,
    val send: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val music: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val torch: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    val vibrate: SettingsChatBotPermissionsCommand = SettingsChatBotPermissionsCommand(),
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsChatBotPermissions>(generatedSerializer())
}

/** A chat word that stands for a longer command, for example "!screen" for "!moblin scene Screen". */
@KeepGeneratedSerializer
@Serializable(with = SettingsChatBotAlias.Serializer::class)
data class SettingsChatBotAlias(
    val alias: String = "",
    val replacement: String = "",
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsChatBotAlias>(generatedSerializer())
}

/** Upstream `SettingsChatBotCommandSnapshotSource`: where the snapshot command takes its picture. */
@Serializable(with = ChatBotSnapshotSource.Serializer::class)
enum class ChatBotSnapshotSource(override val rawValue: String) : SwiftRawValueEnum {
    THIS_DEVICE("This device"),
    WEB_REMOTE_CONTROL("Web remote control"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<ChatBotSnapshotSource>("SettingsChatBotCommandSnapshotSource", entries, THIS_DEVICE)
}

/** Upstream `SettingsChatBotCommandSnapshotWebRemoteControlMode`. */
@Serializable(with = ChatBotSnapshotWebRemoteControlMode.Serializer::class)
enum class ChatBotSnapshotWebRemoteControlMode(override val rawValue: String) : SwiftRawValueEnum {
    FALLBACK_WHEN_NOT_LIVE("fallbackWhenNotLive"),
    ALWAYS("always"),
    ;

    object Serializer : SwiftRawValueEnumSerializer<ChatBotSnapshotWebRemoteControlMode>(
        "SettingsChatBotCommandSnapshotWebRemoteControlMode",
        entries,
        FALLBACK_WHEN_NOT_LIVE,
    )
}

/**
 * Upstream `SettingsChatBotCommandSnapshot`: the snapshot command can take the picture from
 * another phone's web remote control (for example a camera phone, while this one is a chat
 * phone). Its URL may carry that phone's access token, so it is kept secret.
 */
@KeepGeneratedSerializer
@Serializable(with = SettingsChatBotCommandSnapshot.Serializer::class)
data class SettingsChatBotCommandSnapshot(
    val source: ChatBotSnapshotSource = ChatBotSnapshotSource.THIS_DEVICE,
    val webRemoteControlUrl: String = "",
    val webRemoteControlMode: ChatBotSnapshotWebRemoteControlMode = ChatBotSnapshotWebRemoteControlMode.FALLBACK_WHEN_NOT_LIVE,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<SettingsChatBotCommandSnapshot>(generatedSerializer())

    /** Upstream `effectiveSource`. */
    fun effectiveSource(isLive: Boolean): ChatBotSnapshotSource = when {
        source == ChatBotSnapshotSource.THIS_DEVICE -> ChatBotSnapshotSource.THIS_DEVICE
        webRemoteControlMode == ChatBotSnapshotWebRemoteControlMode.ALWAYS -> ChatBotSnapshotSource.WEB_REMOTE_CONTROL
        isLive -> ChatBotSnapshotSource.THIS_DEVICE
        else -> ChatBotSnapshotSource.WEB_REMOTE_CONTROL
    }
}
