// Credential-bearing keys of upstream's settings document, from Moblin/Various/Settings/*.swift @97e26d2.
package io.github.mesmerize08.nexusirl.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How a settings string holds a credential. */
enum class CredentialKind {
    /** The whole value is secret (a password, token or key). */
    WHOLE,

    /** A URL whose path, query or user info is secret; `scheme://host[:port]` is not (see [StreamUrlSecrets]). */
    URL,
}

/**
 * Finds credentials anywhere in the settings document, including the iOS-only settings Android
 * keeps in `extra` (docs/PORT_PLAN.md §9.2). Keys are matched by name, not by place, so settings
 * upstream adds later are covered when they follow its naming. Only string values count.
 */
object SettingsCredentials {
    /** Name parts of whole-value credentials, lowercase: `obsWebSocketPassword`, `apiKey`, `realtimeIrlPushKey`, `bridgeId`... */
    private val wholeParts = listOf(
        "password",
        "passphrase",
        "secret",
        "apikey",
        "privatekey",
        "streamkey",
        "pushkey",
        "bridgeid",
    )

    /** URL keys whose path is public configuration, such as an API base URL. */
    private val publicUrlKeys = setOf("baseUrl", "realtimeIrlBaseUrl")

    /** The kind of credential under [key] (in objects named [parents], outermost first), or null. */
    fun kind(parents: List<String>, key: String): CredentialKind? {
        val lower = key.lowercase()
        return when {
            wholeParts.any { it in lower } || lower.endsWith("token") -> CredentialKind.WHOLE
            // Moblin Cloud's account key; elsewhere (keyboard shortcuts) "key" is a key name.
            key == "key" && parents.lastOrNull() == "moblinCloud" -> CredentialKind.WHOLE
            // An SRTLA ingest's stream ID lets anyone send to it, like a stream key.
            key == "streamId" && "srtlaServer" in parents -> CredentialKind.WHOLE
            "webhook" in lower -> CredentialKind.URL
            key in publicUrlKeys -> null
            // The Moblink streamer's address on the local network, used as is.
            key == "url" && parents.takeLast(2) == listOf("moblink", "client") -> null
            key == "url" || key.endsWith("Url") -> CredentialKind.URL
            else -> null
        }
    }

    /**
     * [element] with every string passed through [transform], together with its [kind] (null when
     * it is not a credential). Strings in an array take the array's key.
     */
    fun map(element: JsonElement, transform: (kind: CredentialKind?, value: String) -> String): JsonElement =
        map(element, emptyList(), null, transform)

    private fun map(
        element: JsonElement,
        parents: List<String>,
        key: String?,
        transform: (CredentialKind?, String) -> String,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val inside = if (key == null) parents else parents + key
            JsonObject(element.mapValues { (name, value) -> map(value, inside, name, transform) })
        }
        is JsonArray -> JsonArray(element.map { map(it, parents, key, transform) })
        is JsonPrimitive -> if (element.isString) {
            val kind = key?.let { kind(parents, it) }
            val value = element.content
            val transformed = transform(kind, value)
            if (transformed == value) element else JsonPrimitive(transformed)
        } else {
            element
        }
    }
}
