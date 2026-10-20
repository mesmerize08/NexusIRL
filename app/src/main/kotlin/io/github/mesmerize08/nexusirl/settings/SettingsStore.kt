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
            stream.copy(url = url)
        }
        val password = if (includeSecrets) remoteControlPassword() else ""
        return database.copy(streams = streams, remoteControl = database.remoteControl.copy(password = password)).toJson()
    }

    private fun protectSecrets(database: Database): Database {
        val password = database.remoteControl.password
        // The remote control password is secret as a whole (docs/PORT_PLAN.md §9.2).
        val protectedPassword = if (password.isEmpty() || SecretRef.findAll(password).isNotEmpty()) {
            password
        } else {
            secrets.put(password).placeholder
        }
        return database.copy(
            streams = database.streams.map { stream ->
                stream.copy(url = StreamUrlSecrets.protect(stream.url) { secret -> secrets.put(secret).placeholder })
            },
            remoteControl = database.remoteControl.copy(password = protectedPassword),
        )
    }

    /** The remote control password, from the secret store. Keep it in memory only. */
    fun remoteControlPassword(): String = SecretRef.resolve(current.remoteControl.password, secrets)

    private fun removeUnreferencedSecrets(database: Database) {
        val streamSecrets = database.streams.flatMap { SecretRef.findAll(it.url) }
        val referenced = (streamSecrets + SecretRef.findAll(database.remoteControl.password)).toSet()
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
