package io.github.mesmerize08.nexusirl.core.websocket

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Where unencrypted HTTP and WebSocket traffic may go (docs/PORT_PLAN.md §9.5). Android's network
 * security config can only list host names, not address ranges, so the rule lives in the HTTP
 * clients instead: loopback always; the local network (private, link-local and Tailscale
 * addresses, IPv6 link-local and unique local addresses, `.local` and `.ts.net` names) only for
 * clients that opt in, such as remote control to an assistant on the same Wi-Fi. Everything else
 * must use TLS.
 */
object CleartextPolicy {
    /**
     * "localhost" or a loopback IP literal (127.0.0.0/8, ::1). Never a DNS lookup: a name such as
     * "127.evil.example" can resolve to anything, and the answer may change between this check
     * and the connection.
     */
    fun isLoopback(host: String): Boolean {
        val name = host.lowercase()
        if (name == "localhost") {
            return true
        }
        val literal = name.removePrefix("[").removeSuffix("]")
        if (literal.length != name.length && (!name.startsWith("[") || !name.endsWith("]"))) {
            return false
        }
        if (IPV4_LITERAL.matches(literal)) {
            val octets = literal.split('.').map { it.toInt() }
            return octets.all { it in 0..255 } && octets[0] == 127
        }
        if (':' !in literal || !IPV6_LITERAL.matches(literal)) {
            return false
        }
        return try {
            InetAddress.getByName(literal).isLoopbackAddress
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Private IPv4 ranges, link-local, Tailscale's 100.64.0.0/10, IPv6 link-local (fe80::/10) and
     * unique local (fc00::/7, which includes Tailscale's) addresses, and mDNS/MagicDNS names.
     */
    fun isLocalNetwork(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        if (name.endsWith(".local") || name.endsWith(".ts.net")) {
            return true
        }
        if (':' in name) {
            return isLocalIpv6(name)
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

    private fun isLocalIpv6(host: String): Boolean {
        val literal = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        // Only ever parse a literal, so this can never become a DNS lookup.
        if (!IPV6_LITERAL.matches(literal)) {
            return false
        }
        val address = try {
            InetAddress.getByName(literal)
        } catch (e: Exception) {
            return false
        }
        if (address !is Inet6Address) {
            // An IPv4-mapped address (::ffff:a.b.c.d), which Java turns into IPv4.
            return isLocalNetwork(address.hostAddress.orEmpty())
        }
        val first = address.address[0].toInt() and 0xFF
        return address.isLinkLocalAddress || (first and 0xFE) == 0xFC
    }

    /**
     * Whether [remote] is on the same network link as [local], this device's address the
     * connection arrived on: within the prefix of that address's interface. This is what lets
     * peers on a hotspot or LAN with public IPv6 addresses in (phone hotspots often give them out,
     * and iOS Moblin connects to those first) while refusing the rest of the internet, which is
     * never within the prefix.
     * Off-link hosts cannot fake an on-link address, as the TCP handshake would not reach them.
     */
    fun isOnLink(local: InetAddress, remote: InetAddress): Boolean {
        val prefixLength = try {
            NetworkInterface.getByInetAddress(local)?.interfaceAddresses?.firstOrNull { it.address == local }?.networkPrefixLength
        } catch (e: Exception) {
            null
        } ?: return false
        return isSamePrefix(local, remote, prefixLength.toInt())
    }

    /**
     * Whether [a] and [b] share their first [prefixLength] bits. Prefixes shorter than a normal
     * link's (/16 for IPv4, /48 for IPv6) are refused, so an odd interface can never open up a
     * large part of the internet.
     */
    fun isSamePrefix(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
        val x = a.address
        val y = b.address
        val minimum = if (x.size == 4) 16 else 48
        if (x.size != y.size || prefixLength < minimum || prefixLength > x.size * 8) {
            return false
        }
        for (bit in 0 until prefixLength) {
            val mask = 0x80 ushr (bit % 8)
            if ((x[bit / 8].toInt() and mask) != (y[bit / 8].toInt() and mask)) {
                return false
            }
        }
        return true
    }

    /**
     * For the local servers (remote control, Moblink): a connection that arrived on [local] from
     * [remote] is from the local network if [remote] is loopback, on the same link as [local]
     * (hotspot peers with public IPv6 addresses), or a local-network address reaching a loopback or
     * local-network [local]. An unknown peer never is.
     */
    fun isLocalConnection(
        local: InetAddress,
        remote: InetAddress?,
        onLink: (local: InetAddress, remote: InetAddress) -> Boolean = ::isOnLink,
    ): Boolean {
        if (remote == null) {
            return false
        }
        if (remote.isLoopbackAddress || onLink(local, remote)) {
            return true
        }
        // A local address alone proves nothing about the peer: port forwarding, a VPN or a
        // router's NAT can put any internet host behind it. And a private peer on a public
        // address of ours is a carrier's network, not ours.
        val localIsLocal = local.isLoopbackAddress || isLocalNetwork(local.hostAddress.orEmpty())
        return localIsLocal && isLocalNetwork(remote.hostAddress.orEmpty())
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

    private val IPV4_LITERAL = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
    private val IPV6_LITERAL = Regex("[0-9a-f:.]+")
}
