package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.websocket.CleartextPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What is wrong with a WebSocket URL the user entered. */
enum class WebSocketUrlProblem {
    /** Not a ws:// or wss:// URL with a host. */
    INVALID,

    /** Plain ws:// to a host outside the local network (CleartextPolicy). */
    NOT_LOCAL_CLEARTEXT,
}

/**
 * What is wrong with WebSocket URL [url], or null if it can be used. The host is parsed the way
 * OkHttp's WebSocket client parses it (IPv6 literals in brackets included), so this agrees with
 * what CleartextPolicy.Guard refuses when connecting (docs/PORT_PLAN.md §9.5).
 */
fun webSocketUrlProblem(url: String): WebSocketUrlProblem? {
    if (!SettingsMoblinkRelay.isValidWebSocketUrl(url)) {
        return WebSocketUrlProblem.INVALID
    }
    val cleartext = url.startsWith("ws:", ignoreCase = true)
    // OkHttp turns ws: and wss: into http: and https: the same way.
    val httpUrl = (if (cleartext) "http:" else "https:") + url.substringAfter(':')
    val host = httpUrl.toHttpUrlOrNull()?.host ?: return WebSocketUrlProblem.INVALID
    return if (cleartext && !CleartextPolicy.isAllowed(host, allowLocalNetwork = true)) {
        WebSocketUrlProblem.NOT_LOCAL_CLEARTEXT
    } else {
        null
    }
}
