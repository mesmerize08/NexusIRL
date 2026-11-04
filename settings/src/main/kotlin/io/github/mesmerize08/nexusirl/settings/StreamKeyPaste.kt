package io.github.mesmerize08.nexusirl.settings

import java.net.URLEncoder

/**
 * The stream editor's "Paste stream key" (docs/PORT_PLAN.md §9.4): pasted text is either a whole
 * stream URL, which replaces the field, or a key that goes where the current URL's protocol keeps
 * it. RTMP(S): the last path component (upstream `isValidRtmpUrl`), after the app name. SRT(LA):
 * the `streamid` query parameter; a pasted query (`streamid=…&passphrase=…`) is merged instead.
 */
object StreamKeyPaste {
    enum class Failure { EMPTY, NOT_A_KEY, INVALID_URL, NEEDS_SERVER }

    sealed interface Result {
        /** [url] is the new field value; [wholeUrl] when the clipboard held a whole URL. */
        data class Pasted(val url: String, val wholeUrl: Boolean) : Result

        data class Failed(val failure: Failure) : Result
    }

    private const val MAX_LENGTH = 2048
    private val SRT_QUERY = Regex("""^\??(streamid|passphrase|pbkeylen|latency)=""", RegexOption.IGNORE_CASE)

    /** What pasting [clipboard] into a field holding [current] gives. */
    fun apply(current: String, clipboard: String): Result {
        val text = clipboard.trim()
        if (text.isEmpty()) {
            return Result.Failed(Failure.EMPTY)
        }
        // Keys and URLs have no spaces; "Stream key: …" or a whole message is not one.
        if (text.length > MAX_LENGTH || text.any { it.isWhitespace() }) {
            return Result.Failed(Failure.NOT_A_KEY)
        }
        if ("://" in text) {
            val url = StreamUrls.clean(text)
            return if (StreamUrlSecrets.isValid(url)) Result.Pasted(url, wholeUrl = true) else Result.Failed(Failure.INVALID_URL)
        }
        val url = when (current.substringBefore("://", "").lowercase()) {
            "rtmp", "rtmps" -> withRtmpKey(current, text.removePrefix("/"))
            "srt", "srtla" -> withSrtQuery(current, text)
            else -> return Result.Failed(Failure.NEEDS_SERVER)
        }
        return if (StreamUrlSecrets.isValid(url)) Result.Pasted(url, wholeUrl = false) else Result.Failed(Failure.INVALID_URL)
    }

    /** `rtmp://host/app` + key; an old key (the path after the app name) and its query are replaced. */
    private fun withRtmpKey(current: String, key: String): String {
        val authorityStart = current.indexOf("://") + 3
        val authorityEnd = current.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) current.length else it }
        val segments = current.substring(authorityEnd).substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
        val app = if (segments.size >= 2) segments.dropLast(1) else segments
        return current.substring(0, authorityEnd) + app.joinToString("") { "/$it" } + "/" + key
    }

    private fun withSrtQuery(current: String, text: String): String {
        val base = current.substringBefore('#').substringBefore('?')
        val existing = queryPairs(current.substringBefore('#').substringAfter('?', ""))
        val added = if (SRT_QUERY.containsMatchIn(text)) {
            queryPairs(text.removePrefix("?"))
        } else {
            listOf("streamid" to URLEncoder.encode(text, "UTF-8"))
        }
        val replaced = existing.map { (name, value) ->
            added.firstOrNull { it.first.equals(name, ignoreCase = true) } ?: (name to value)
        }
        val existingNames = existing.map { it.first.lowercase() }.toSet()
        val merged = replaced + added.filter { it.first.lowercase() !in existingNames }
        return base + "?" + merged.joinToString("&") { (name, value) -> "$name=$value" }
    }

    /** Raw (still encoded) `name=value` pairs of a query. */
    private fun queryPairs(query: String): List<Pair<String, String>> = query.split('&').filter { it.isNotEmpty() }.map {
        it.substringBefore('=') to it.substringAfter('=', "")
    }
}
