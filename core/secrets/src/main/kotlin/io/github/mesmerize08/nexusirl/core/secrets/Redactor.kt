// Long regex / golden JSON literals are clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.core.secrets

import java.net.URI

/**
 * Masks secrets in any text before it reaches a log sink, the in-app log, remote-control `log`
 * events, error messages or log export (docs/PORT_PLAN.md §9.8).
 *
 * Three layers:
 * 1. URLs keep only `scheme://host[:port]`; any path or query is replaced by `***`.
 * 2. Known credential patterns (`Bearer`, `oauth:`, `streamid=`, `passphrase=`, ...) are masked.
 * 3. Every value currently in the [SecretStore] is masked by exact match.
 */
class Redactor(private val secrets: SecretStore? = null) {
    fun redact(text: String): String {
        var result = urlPattern.replace(text) { redactUrl(it.value) }
        for ((pattern, replacement) in credentialPatterns) {
            result = pattern.replace(result, replacement)
        }
        val values = secrets?.values().orEmpty()
            .filter { it.length >= MINIMUM_SECRET_LENGTH }
            .sortedByDescending { it.length }
        for (value in values) {
            result = result.replace(value, MASK)
        }
        return result
    }

    companion object {
        const val MASK = "***"
        private const val MINIMUM_SECRET_LENGTH = 4

        private val urlPattern = Regex(
            """\b(?:rtmps?|srtla|srt|rist|whips?|https?|wss?|mobcam)://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE,
        )

        private val credentialPatterns = listOf(
            Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+""") to "$1 $MASK",
            Regex("""(?i)\boauth:[A-Za-z0-9]+""") to "oauth:$MASK",
            Regex("""(?i)\b(streamid|passphrase|password|key|token|access_token|refresh_token|secret)=[^&\s"']+""") to "$1=$MASK",
            Regex("""(?i)("(?:password|passphrase|accessToken|refreshToken|token|streamKey|authentication)"\s*:\s*)"[^"]*"""") to "$1\"$MASK\"",
        )

        /**
         * Reduces [url] to `scheme://host[:port]`, appending a slash and [MASK] when a path, query
         * or user info was present.
         */
        fun redactUrl(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) {
                return MASK
            }
            val scheme = url.substring(0, schemeEnd)
            val parsed = try {
                URI(url)
            } catch (e: Exception) {
                null
            }
            val afterScheme = url.substring(schemeEnd + 3)
            val authority = afterScheme.takeWhile { it !in "/?#" }
            val hasUserInfo = authority.contains('@')
            val host = parsed?.host
                ?: authority.substringAfterLast('@').substringBefore(':')
            if (host.isEmpty()) {
                return "$scheme://$MASK"
            }
            val port = parsed?.port?.takeIf { it >= 0 }?.let { ":$it" } ?: ""
            val rest = afterScheme.substring(authority.length)
            val hasMore = (rest.isNotEmpty() && rest != "/") || hasUserInfo
            return if (hasMore) "$scheme://$host$port/$MASK" else "$scheme://$host$port"
        }
    }
}
