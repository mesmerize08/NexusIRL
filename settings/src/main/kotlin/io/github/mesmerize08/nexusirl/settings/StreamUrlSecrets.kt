package io.github.mesmerize08.nexusirl.settings

import java.net.URI

/**
 * Splits stream URLs into a displayable part and a secret part (docs/PORT_PLAN.md §9.3, §9.4).
 *
 * Everything after `scheme://host[:port]` (path, query, user info) is treated as secret: that is
 * where RTMP stream keys, SRT `streamid`/`passphrase` and similar credentials live. The settings
 * file then stores `rtmp://live.example.com{secret:<uuid>}` and the real value lives in the
 * SecretStore. Only `scheme://host[:port]` is ever shown on screen.
 */
object StreamUrlSecrets {
    private val placeholder = Regex("""\{secret:[0-9a-fA-F-]{36}\}""")

    /** Splits [url] into its public prefix and secret remainder (empty when there is none). */
    fun split(url: String): Pair<String, String> {
        val public = publicPart(url)
        return public to url.removePrefix(public)
    }

    /** `scheme://host[:port]` of [url], or the whole string when it is not a URL. */
    fun publicPart(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            return url
        }
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#', '{'), authorityStart).let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, authorityEnd)
        // '{' starts a secret placeholder (never valid in a host). User info (user:password@) is secret too.
        val hostPort = authority.substringAfterLast('@')
        return url.substring(0, authorityStart) + hostPort
    }

    /** What to show for [url] in the UI and logs: `scheme://host[:port]` only. */
    fun display(url: String): String = publicPart(url)

    fun containsPlaceholder(url: String): Boolean = placeholder.containsMatchIn(url)

    /**
     * Replaces the secret part of [url] with a placeholder produced by [store] (which saves the
     * secret and returns its placeholder text). URLs without a secret part, or already holding a
     * placeholder, are returned unchanged.
     */
    fun protect(url: String, store: (secret: String) -> String): String {
        if (containsPlaceholder(url)) {
            return url
        }
        val (public, secret) = split(url)
        return if (secret.isEmpty()) url else public + store(secret)
    }

    /** True when [url] looks like a stream URL this app can use. */
    fun isValid(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme?.lowercase() in setOf("srt", "srtla", "rtmp", "rtmps") && !uri.host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }
}
