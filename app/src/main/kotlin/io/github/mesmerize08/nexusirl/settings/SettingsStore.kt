// Port of the storage parts of Moblin/Various/Settings/Settings.swift `Settings` @97e26d2.
package io.github.mesmerize08.nexusirl.settings

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

    /** Applies [transform], moves any new URL secrets into the secret store, and saves. */
    @Synchronized
    fun update(transform: (Database) -> Database) {
        val updated = protectSecrets(transform(current))
        save(updated)
        mutableDatabase.value = updated
        removeUnreferencedSecrets(updated)
    }

    /** The full stream URL, with secrets filled in. Keep it in memory only. */
    fun resolveUrl(stream: SettingsStream): String = SecretRef.resolve(stream.url, secrets)

    /**
     * Replaces all settings with an imported document (upstream `importFromFile`). Secrets in it
     * move into the secret store immediately, so they never reach `settings.json`.
     */
    fun import(json: String) {
        val imported = Database.fromJson(json)
        update { imported }
    }

    /**
     * The settings document for export. Without [includeSecrets] (the default, docs/PORT_PLAN.md
     * §9.7) every stream URL is cut down to `scheme://host[:port]`; the file is still a valid
     * iOS Moblin settings file.
     */
    fun exportJson(includeSecrets: Boolean = false): String {
        val database = current
        val streams = database.streams.map { stream ->
            val url = if (includeSecrets) resolveUrl(stream) else StreamUrlSecrets.publicPart(stream.url)
            // Twitch tokens are never exported; the other device signs in itself.
            stream.copy(url = url, twitchAccessToken = "", twitchLoggedIn = false)
        }
        val password = if (includeSecrets) remoteControlPassword() else ""
        val moblinkPassword = if (includeSecrets) moblinkPassword() else ""
        return database.copy(
            streams = streams,
            // The web access token stays on this device (it is not part of upstream's settings).
            remoteControl = database.remoteControl.copy(password = password, web = database.remoteControl.web.copy(token = "")),
            moblink = database.moblink.copy(password = moblinkPassword),
        ).toJson()
    }

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
                    url = StreamUrlSecrets.protect(stream.url) { secret -> secrets.put(secret).placeholder },
                    twitchAccessToken = protect(stream.twitchAccessToken),
                )
            },
            remoteControl = database.remoteControl.copy(
                password = protect(database.remoteControl.password),
                web = database.remoteControl.web.copy(token = protect(database.remoteControl.web.token)),
            ),
        )
    }

    /** The stream's stored Twitch secret (tokens), or empty. Keep it in memory only. */
    fun twitchSecret(stream: SettingsStream): String = SecretRef.resolve(stream.twitchAccessToken, secrets)

    /** The web remote control access token, from the secret store. Keep it in memory only. */
    fun webControlToken(): String = SecretRef.resolve(current.remoteControl.web.token, secrets)

    /** The Moblink password, from the secret store. Keep it in memory only. */
    fun moblinkPassword(): String = SecretRef.resolve(current.moblink.password, secrets)

    /** The remote control password, from the secret store. Keep it in memory only. */
    fun remoteControlPassword(): String = SecretRef.resolve(current.remoteControl.password, secrets)

    private fun removeUnreferencedSecrets(database: Database) {
        val streamSecrets = database.streams.flatMap { SecretRef.findAll(it.url) + SecretRef.findAll(it.twitchAccessToken) }
        val passwords = SecretRef.findAll(database.remoteControl.password) + SecretRef.findAll(database.moblink.password) +
            SecretRef.findAll(database.remoteControl.web.token)
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
