// Port of the storage parts of Moblin/Various/Settings/Settings.swift `Settings` @97e26d2.
package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import io.github.mesmerize08.nexusirl.core.secrets.MissingSecretException
import io.github.mesmerize08.nexusirl.core.secrets.Redactor
import io.github.mesmerize08.nexusirl.core.secrets.SecretRef
import io.github.mesmerize08.nexusirl.core.secrets.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException

/**
 * Loads and saves the settings document (`settings.json` in the app's private files).
 *
 * Unlike upstream, which stores credentials in plain JSON, every credential ([SettingsCredentials]:
 * stream keys, passwords, tokens, the secret part of URLs), also in iOS-only settings, lives in the
 * [secrets] store and the file holds only a `{secret:<uuid>}` reference (docs/PORT_PLAN.md §9.3).
 * Secrets are resolved in memory only, when they are used.
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
            val json = save(protected)
            mutableDatabase.value = protected
            removeUnreferencedSecrets(json)
        }
    }

    /** Applies [transform], moves any new secrets into the secret store, and saves. */
    @Synchronized
    fun update(transform: (Database) -> Database) {
        val updated = protectSecrets(transform(current), previous = current)
        val json = save(updated)
        mutableDatabase.value = updated
        removeUnreferencedSecrets(json)
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
        update { current ->
            // Whether an assistant may import settings is this phone's choice; a file never changes it.
            val allowSettingsImport = current.remoteControl.streamer.allowSettingsImport
            val streamer = imported.remoteControl.streamer.copy(allowSettingsImport = allowSettingsImport)
            imported.copy(
                quickButtons = addMissingQuickButtons(imported.quickButtons),
                remoteControl = imported.remoteControl.copy(streamer = streamer),
            )
        }
    }

    /**
     * The settings document for export. Without [includeSecrets] (the default, docs/PORT_PLAN.md
     * §9.7) every credential is left out and every URL holding one is cut down to
     * `scheme://host[:port]`; the file is still a valid iOS Moblin settings file.
     */
    fun exportJson(includeSecrets: Boolean = false): String {
        val database = current.let { database ->
            database.copy(
                // Twitch and Kick sign-ins are never exported; the other device signs in itself.
                streams = database.streams.map {
                    it.copy(twitchAccessToken = "", twitchLoggedIn = false, kickAccessToken = "", kickLoggedIn = false)
                },
                // The web access token stays on this device (it is not part of upstream's settings).
                // So does the permission for remote settings imports.
                remoteControl = database.remoteControl.copy(
                    web = database.remoteControl.web.copy(token = ""),
                    streamer = database.remoteControl.streamer.copy(allowSettingsImport = false),
                ),
            )
        }
        val tree = SwiftJson.encodeToJsonElement(Database.Serializer, database)
        val exported = SettingsCredentials.map(tree) { kind, value ->
            when {
                includeSecrets -> resolveOrPublicPart(value)
                kind == CredentialKind.WHOLE -> ""
                kind == CredentialKind.URL || SecretRef.findAll(value).isNotEmpty() -> withoutSecrets(value)
                else -> value
            }
        }
        return SwiftJson.encodeToString(JsonElement.serializer(), exported)
    }

    /** [value] without its secret parts: a URL's public part, else empty if it holds a secret. */
    private fun withoutSecrets(value: String) = when {
        "://" in value -> StreamUrlSecrets.publicPart(value)
        SecretRef.findAll(value).isNotEmpty() -> ""
        // Not a URL, such as the ID of a selected entry.
        else -> value
    }

    /**
     * Moves every credential in [database], wherever it is, into the secret store. Values that
     * already hold a placeholder stay as they are. A secret [previous] already held keeps its
     * reference: editors save the plain values back, and a new reference would look like a change
     * (reconnecting OBS, for one).
     */
    private fun protectSecrets(database: Database, previous: Database? = null): Database {
        val tree = SwiftJson.encodeToJsonElement(Database.Serializer, database)
        val previousRefs by lazy {
            val refs = previous?.let { SecretRef.findAll(it.toJson()) }.orEmpty().distinct()
            refs.mapNotNull { ref -> secrets.get(ref)?.let { value -> value to ref } }.toMap()
        }
        fun store(secret: String) = (previousRefs[secret] ?: secrets.put(secret)).placeholder
        val protected = SettingsCredentials.map(tree) { kind, value ->
            when {
                value.isEmpty() || SecretRef.findAll(value).isNotEmpty() -> value
                kind == CredentialKind.WHOLE -> store(value)
                kind == CredentialKind.URL -> StreamUrlSecrets.protect(value, ::store)
                else -> value
            }
        }
        return if (protected == tree) database else SwiftJson.decodeFromJsonElement(Database.Serializer, protected)
    }

    /** The stream's stored Twitch secret (tokens), or empty. Keep it in memory only. */
    fun twitchSecret(stream: SettingsStream): String = SecretRef.resolve(stream.twitchAccessToken, secrets)

    /** The stream's stored Kick session token, or empty. Keep it in memory only. */
    fun kickSecret(stream: SettingsStream): String = SecretRef.resolve(stream.kickAccessToken, secrets)

    /** The web remote control access token, from the secret store. Keep it in memory only. */
    fun webControlToken(): String = SecretRef.resolve(current.remoteControl.web.token, secrets)

    /** The stream's OBS WebSocket URL, with any user info. Keep it in memory only. */
    fun obsWebSocketUrl(stream: SettingsStream): String = SecretRef.resolve(stream.obsWebSocketUrl, secrets)

    /** The stream's OBS WebSocket password, from the secret store. Keep it in memory only. */
    fun obsWebSocketPassword(stream: SettingsStream): String = SecretRef.resolve(stream.obsWebSocketPassword, secrets)

    /** The Moblink password, from the secret store. Keep it in memory only. */
    fun moblinkPassword(): String = SecretRef.resolve(current.moblink.password, secrets)

    /** The assistant (or relay) URL the streamer connects to, with its secret path. Keep it in memory only. */
    fun remoteControlUrl(): String = SecretRef.resolve(current.remoteControl.streamer.url, secrets)

    /** A setting holding a secret reference (such as a Discord webhook), filled in. Keep it in memory only. */
    fun resolve(value: String): String = SecretRef.resolve(value, secrets)

    /**
     * As [resolve], but a secret missing from the store (e.g. after a restore to another phone)
     * gives the URL's public part, or nothing, instead of failing.
     */
    fun resolveOrPublicPart(value: String): String = try {
        resolve(value)
    } catch (e: MissingSecretException) {
        withoutSecrets(value)
    }

    /** The remote control password, from the secret store. Keep it in memory only. */
    fun remoteControlPassword(): String = SecretRef.resolve(current.remoteControl.password, secrets)

    /** Deletes the secrets [json] (the saved document) no longer refers to. */
    private fun removeUnreferencedSecrets(json: String) {
        val referenced = SecretRef.findAll(json).toSet()
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

    /** Writes [database] and returns the JSON written. */
    private fun save(database: Database): String {
        val json = database.toJson()
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(json)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                throw IOException("Failed to write ${file.name}")
            }
        }
        return json
    }

    private companion object {
        /** Upstream `createDefault()` starts without streams; the user adds the first one. */
        fun createDefault(): Database = Database()
    }
}
