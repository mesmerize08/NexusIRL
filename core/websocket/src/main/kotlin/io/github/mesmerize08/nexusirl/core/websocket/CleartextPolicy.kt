package io.github.mesmerize08.nexusirl.core.websocket

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress

/**
 * Where unencrypted HTTP and WebSocket traffic may go (docs/PORT_PLAN.md §9.5). Android's network
 * security config can only list host names, not address ranges, so the rule lives in the HTTP
 * clients instead: loopback always; the local network (private, link-local and Tailscale
 * addresses, `.local` and `.ts.net` names) only for clients that opt in, such as remote control
 * to an assistant on the same Wi-Fi. Everything else must use TLS.
 */
object CleartextPolicy {
    fun isLoopback(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")) {
            return true
        }
        // Test servers and some systems use the machine's own name for loopback.
        return try {
            InetAddress.getByName(host).isLoopbackAddress
        } catch (e: Exception) {
            false
        }
    }

    /** Private IPv4 ranges, link-local, Tailscale's 100.64.0.0/10, and mDNS/MagicDNS names. */
    fun isLocalNetwork(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        if (name.endsWith(".local") || name.endsWith(".ts.net")) {
            return true
        }
        val parts = name.split('.')
        if (parts.size != 4) {
            return false
        }
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) {
            return false
        }
        val (a, b) = octets
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    fun isAllowed(host: String, allowLocalNetwork: Boolean): Boolean = isLoopback(host) || (allowLocalNetwork && isLocalNetwork(host))

    /** Refuses cleartext requests (http, ws) that the policy does not allow. */
    class Guard(private val allowLocalNetwork: Boolean = false) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            if (!url.isHttps && !isAllowed(url.host, allowLocalNetwork)) {
                throw IOException("Unencrypted connection to ${url.host} refused; use TLS (https:// or wss://)")
            }
            return chain.proceed(chain.request())
        }
    }
}
