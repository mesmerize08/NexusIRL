// Port of the storage parts of Moblin/Various/Settings/Settings.swift `Settings` @97e26d2.
package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.secrets.Redactor
import io.github.mesmerize08.nexusirl.core.secrets.SecretRef
import io.github.mesmerize08.nexusirl.core.secrets.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

/**
 * Loads and saves the settings document (`settings.json` in the app's private files).
 *
 * Unlike upstream, which stores stream URLs in plain JSON, the secret part of every stream URL
 * lives in the [secrets] store and the file holds only a `{secret:<uuid>}` reference
 * (docs/PORT_PLAN.md §9.3). Secrets are resolved in memory only, when a stream starts.
 */
class SettingsStore(private val file: File, private val secrets: SecretStore) {
    private val mutableDatabase = MutableStateFlow(load())
    val database: StateFlow<Database> = mutableDatabase

    val current: Database
        get() = mutableDatabase.value

    init {
        // Files written before a field became secret still hold it in plain text: move it now.
        val protected = protectSecrets(current)
        if (protected != current) {
            save(protected)
            mutableDatabase.value = protected
            removeUnreferencedSecrets(protected)
        }
    }

    /** Applies [transform], moves any new URL secrets into the secret store, and saves. */
    @Synchronized
    fun update(transform: (Database) -> Database) {
        val updated = protectSecrets(transform(current))
        save(updated)
        mutableDatabase.value = updated
        removeUnreferencedSecrets(updated)
    }

    /** Masks URLs, credential patterns and every stored secret, e.g. for logs and diagnostics. */
    fun redactor(): Redactor = Redactor(secrets)

    /** The full stream URL, with secrets filled in. Keep it in memory only. */
    fun resolveUrl(stream: SettingsStream): String = SecretRef.resolve(stream.url, secrets)

    /**
     * Replaces all settings with an imported document (upstream `importFromFile`). Secrets in it
     * move into the secret store immediately, so they never reach `settings.json`. As upstream's
     * `Database.fromString`, every quick button exists afterwards and all are off.
     */
    fun import(json: String) {
        val imported = Database.fromJson(json)
        update { imported.copy(quickButtons = addMissingQuickButtons(imported.quickButtons)) }
    }

    /**
     * The settings document for export. Without [includeSecrets] (the default, docs/PORT_PLAN.md
     * §9.7) every stream URL is cut down to `scheme://host[:port]`; the file is still a valid
     * iOS Moblin settings file.
     */
    fun exportJson(includeSecrets: Boolean = false): String {
        val database = current
        fun exported(url: String) = if (includeSecrets) SecretRef.resolve(url, secrets) else StreamUrlSecrets.publicPart(url)
        val streams = database.streams.map { stream ->
            // Twitch and Kick sign-ins are never exported; the other device signs in itself.
            stream.copy(
                url = exported(stream.url),
                twitchAccessToken = "",
                twitchLoggedIn = false,
                kickAccessToken = "",
                kickLoggedIn = false,
                discordSnapshotWebhook = exported(stream.discordSnapshotWebhook),
                discordChatBotSnapshotWebhook = exported(stream.discordChatBotSnapshotWebhook),
                multiStreaming = stream.multiStreaming.copy(
                    destinations = stream.multiStreaming.destinations.map { it.copy(url = exported(it.url)) },
                ),
            )
        }
        val snapshot = database.chat.botCommandSnapshot.let { it.copy(webRemoteControlUrl = exported(it.webRemoteControlUrl)) }
        val password = if (includeSecrets) remoteControlPassword() else ""
        val streamer = database.remoteControl.streamer.let { streamer ->
            streamer.copy(url = if (includeSecrets) remoteControlUrl() else StreamUrlSecrets.publicPart(streamer.url))
        }
        val moblinkPassword = if (includeSecrets) moblinkPassword() else ""
        return database.copy(
            streams = streams,
            // The web access token stays on this device (it is not part of upstream's settings).
            remoteControl = database.remoteControl.copy(
                password = password,
                streamer = streamer,
                web = database.remoteControl.web.copy(token = ""),
            ),
            moblink = database.moblink.copy(password = moblinkPassword),
            chat = database.chat.copy(botCommandSnapshot = snapshot),
        ).toJson()
    }

    private fun protectUrl(url: String) = StreamUrlSecrets.protect(url) { secret -> secrets.put(secret).placeholder }

    private fun protectSecrets(database: Database): Database {
        // Remote control and Moblink passwords are secret as a whole (docs/PORT_PLAN.md §9.2).
        fun protect(password: String) = if (password.isEmpty() || SecretRef.findAll(password).isNotEmpty()) {
            password
        } else {
            secrets.put(password).placeholder
        }
        return database.copy(
            moblink = database.moblink.copy(password = protect(database.moblink.password)),
            streams = database.streams.map { stream ->
                stream.copy(
                    url = protectUrl(stream.url),
                    twitchAccessToken = protect(stream.twitchAccessToken),
                    // Kick's website session: full access to the account.
                    kickAccessToken = protect(stream.kickAccessToken),
                    // A Discord webhook's path holds its token: anyone with it can post.
                    discordSnapshotWebhook = protectUrl(stream.discordSnapshotWebhook),
                    discordChatBotSnapshotWebhook = protectUrl(stream.discordChatBotSnapshotWebhook),
                    // Extra RTMP destinations hold stream keys like the stream's own URL.
                    multiStreaming = stream.multiStreaming.copy(
                        destinations = stream.multiStreaming.destinations.map { it.copy(url = protectUrl(it.url)) },
                    ),
                )
            },
            // May carry the other phone's web remote control token.
            chat = database.chat.copy(
                botCommandSnapshot = database.chat.botCommandSnapshot.let {
                    it.copy(
                        webRemoteControlUrl = protectUrl(it.webRemoteControlUrl),
                    )
                },
            ),
            remoteControl = database.remoteControl.copy(
                password = protect(database.remoteControl.password),
                // A relay URL's path holds the bridge ID, which lets anyone take the assistant's place.
                streamer = database.remoteControl.streamer.copy(
                    url = protectUrl(database.remoteControl.streamer.url),
                ),
                web = database.remoteControl.web.copy(token = protect(database.remoteControl.web.token)),
            ),
        )
    }

    /** The stream's stored Twitch secret (tokens), or empty. Keep it in memory only. */
    fun twitchSecret(stream: SettingsStream): String = SecretRef.resolve(stream.twitchAccessToken, secrets)

    /** The stream's stored Kick session token, or empty. Keep it in memory only. */
    fun kickSecret(stream: SettingsStream): String = SecretRef.resolve(stream.kickAccessToken, secrets)

    /** The web remote control access token, from the secret store. Keep it in memory only. */
    fun webControlToken(): String = SecretRef.resolve(current.remoteControl.web.token, secrets)

    /** The Moblink password, from the secret store. Keep it in memory only. */
    fun moblinkPassword(): String = SecretRef.resolve(current.moblink.password, secrets)

    /** The assistant (or relay) URL the streamer connects to, with its secret path. Keep it in memory only. */
    fun remoteControlUrl(): String = SecretRef.resolve(current.remoteControl.streamer.url, secrets)

    /** A setting holding a secret reference (such as a Discord webhook), filled in. Keep it in memory only. */
    fun resolve(value: String): String = SecretRef.resolve(value, secrets)

    /** The remote control password, from the secret store. Keep it in memory only. */
    fun remoteControlPassword(): String = SecretRef.resolve(current.remoteControl.password, secrets)

    private fun removeUnreferencedSecrets(database: Database) {
        val streamSecrets = database.streams.flatMap { stream ->
            (
                listOf(
                    stream.url,
                    stream.twitchAccessToken,
                    stream.kickAccessToken,
                    stream.discordSnapshotWebhook,
                    stream.discordChatBotSnapshotWebhook,
                ) +
                    stream.multiStreaming.destinations.map { it.url }
                ).flatMap { SecretRef.findAll(it) }
        } + SecretRef.findAll(database.chat.botCommandSnapshot.webRemoteControlUrl)
        val passwords = SecretRef.findAll(database.remoteControl.password) + SecretRef.findAll(database.moblink.password) +
            SecretRef.findAll(database.remoteControl.web.token) + SecretRef.findAll(database.remoteControl.streamer.url)
        val referenced = (streamSecrets + passwords).toSet()
        for (ref in secrets.refs()) {
            if (ref !in referenced) {
                secrets.remove(ref)
            }
        }
    }

    private fun load(): Database {
        if (!file.exists()) {
            return createDefault()
        }
        return try {
            Database.fromJson(file.readText())
        } catch (e: Exception) {
            // Upstream: "Failed to load ... Using default." Keep the broken file for inspection.
            file.copyTo(File(file.parentFile, "${file.name}.broken"), overwrite = true)
            createDefault()
        }
    }

    private fun save(database: Database) {
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(database.toJson())
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                throw IOException("Failed to write ${file.name}")
            }
        }
    }

    private companion object {
        /** Upstream `createDefault()` starts without streams; the user adds the first one. */
        fun createDefault(): Database = Database()
    }
}
