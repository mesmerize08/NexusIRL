package io.github.mesmerize08.nexusirl.settings

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
        val prefixEnd = schemeEnd + 3
        // Protected user info is a placeholder right after the scheme (see [protect]).
        val authorityStart = placeholder.matchAt(url, prefixEnd)?.let { it.range.last + 1 } ?: prefixEnd
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#', '{'), authorityStart).let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, authorityEnd)
        // '{' starts a secret placeholder (never valid in a host). User info (user:password@) is secret too.
        val hostPort = authority.substringAfterLast('@')
        return url.substring(0, prefixEnd) + hostPort
    }

    /** What to show for [url] in the UI and logs: `scheme://host[:port]` only. */
    fun display(url: String): String = publicPart(url)

    fun containsPlaceholder(url: String): Boolean = placeholder.containsMatchIn(url)

    /**
     * Replaces the secret part of [url] with a placeholder produced by [store] (which saves the
     * secret and returns its placeholder text). URLs without a secret part, or already holding a
     * placeholder, are returned unchanged. User info (`user:password@`) gets a placeholder of its
     * own in front of the host, so resolving gives back the exact URL.
     */
    fun protect(url: String, store: (secret: String) -> String): String {
        if (containsPlaceholder(url)) {
            return url
        }
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            return url
        }
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
        val hostStart = url.lastIndexOf('@', authorityEnd - 1).let { if (it < authorityStart) authorityStart else it + 1 }
        val userInfo = url.substring(authorityStart, hostStart)
        val rest = url.substring(authorityEnd)
        if (userInfo.isEmpty() && rest.isEmpty()) {
            return url
        }
        return url.substring(0, authorityStart) +
            (if (userInfo.isEmpty()) "" else store(userInfo)) +
            url.substring(hostStart, authorityEnd) +
            (if (rest.isEmpty()) "" else store(rest))
    }

    /** True when [url] is a stream URL this app can use (see [StreamUrls.validate]). */
    fun isValid(url: String): Boolean = StreamUrls.validate(url) == null
}
