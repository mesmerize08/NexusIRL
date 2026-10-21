// Port of Moblin/Moblink/MoblinkStreamer.swift @97e26d2: the streamer side, which uses other phones
// on the same Wi-Fi as extra SRTLA paths. Adds docs/PORT_PLAN.md §9.6: local interfaces only and a
// wrong-password lockout.
package io.github.mesmerize08.nexusirl.moblink

import io.github.mesmerize08.nexusirl.core.websocket.CleartextPolicy
import io.github.mesmerize08.nexusirl.remotecontrol.REMOTE_CONTROL_API_VERSION
import io.github.mesmerize08.nexusirl.remotecontrol.remoteControlHashPassword
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** A relay's tunnel: send SRTLA to [host]:[port] (the relay), which forwards over its mobile data. */
data class MoblinkTunnel(val host: String, val port: Int, val relayId: UUID, val relayName: String)

/** A connected relay as shown in the status (upstream `getStatuses`). */
data class MoblinkRelayStatus(val name: String, val batteryPercentage: Int?, val thermalState: MoblinkThermalState?)

/** Upstream `MoblinkStreamerDelegate`. Called on the server's threads. */
interface MoblinkStreamerListener {
    fun tunnelAdded(tunnel: MoblinkTunnel)

    fun tunnelRemoved(tunnel: MoblinkTunnel)

    fun relaysChanged() {}
}

/**
 * The Moblink streamer: a WebSocket server (port 7777, advertised by the app with mDNS) that relays
 * connect to. Relays prove the password with upstream's hash; while streaming with SRTLA,
 * [startTunnels] asks each relay to open a tunnel to the server, and each tunnel becomes an extra
 * bonding path. WebSocket pings every 10 s (upstream ping timer).
 */
class MoblinkStreamer(
    private val port: Int,
    private val password: String,
    private val listener: MoblinkStreamerListener,
    private val acceptLocalAddress: (InetAddress) -> Boolean = { address ->
        val host = address.hostAddress.orEmpty()
        CleartextPolicy.isLoopback(host) || CleartextPolicy.isLocalNetwork(host)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val random = SecureRandom()
    private val relays = LinkedHashMap<WebSocket, Relay>()
    private val failures = HashMap<String, Pair<Int, Long>>()
    private var server: Server? = null
    private var destination: Pair<String, Int>? = null

    private inner class Relay(val socket: WebSocket) {
        val challenge = randomString()
        val salt = randomString()
        var identified = false
        var relayId: UUID? = null
        var name = ""
        var batteryPercentage: Int? = null
        var thermalState: MoblinkThermalState? = null
        var tunnel: MoblinkTunnel? = null
        var nextId = 0
        val requests = HashMap<Int, (MoblinkResult, MoblinkResponse?) -> Unit>()

        fun send(message: MoblinkMessageToRelay) {
            try {
                socket.send(message.toJson())
            } catch (e: Exception) {
                // Closing.
            }
        }

        fun request(data: MoblinkRequest, onResult: (MoblinkResult, MoblinkResponse?) -> Unit) {
            val id = ++nextId
            requests[id] = onResult
            send(MoblinkMessageToRelay.Request(id, data))
        }
    }

    fun start() {
        val newServer = Server(InetSocketAddress(port))
        newServer.isReuseAddr = true
        newServer.connectionLostTimeout = 10
        newServer.start()
        server = newServer
    }

    fun stop() {
        val removed = synchronized(lock) {
            val tunnels = relays.values.mapNotNull { it.tunnel }
            relays.clear()
            tunnels
        }
        removed.forEach(listener::tunnelRemoved)
        try {
            server?.stop(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        server = null
    }

    /** Upstream `startTunnels`: called when an SRTLA stream knows its server address. */
    fun startTunnels(address: String, port: Int) {
        val ready = synchronized(lock) {
            destination = address to port
            relays.values.filter { it.identified }
        }
        ready.forEach { startTunnel(it) }
    }

    /** Upstream `stopTunnels`: the stream ended. */
    fun stopTunnels() {
        val removed = synchronized(lock) {
            destination = null
            relays.values.mapNotNull { relay -> relay.tunnel.also { relay.tunnel = null } }
        }
        removed.forEach(listener::tunnelRemoved)
    }

    /** Upstream `updateStatus` + `getStatuses`: asks every relay for battery and thermal state. */
    fun updateStatus() {
        val identified = synchronized(lock) { relays.values.filter { it.identified } }
        for (relay in identified) {
            synchronized(lock) {
                relay.request(MoblinkRequest.Status) { result, data ->
                    val status = data as? MoblinkResponse.Status
                    if (result == MoblinkResult.OK && status != null) {
                        relay.batteryPercentage = status.batteryPercentage
                        relay.thermalState = status.thermalState
                        listener.relaysChanged()
                    }
                }
            }
        }
    }

    fun statuses(): List<MoblinkRelayStatus> = synchronized(lock) {
        relays.values.filter { it.identified }.sortedBy { it.name }.map {
            MoblinkRelayStatus(
                it.name,
                it.batteryPercentage,
                it.thermalState,
            )
        }
    }

    private fun randomString(): String = ByteArray(32).also(random::nextBytes).let { Base64.getEncoder().encodeToString(it) }

    private fun startTunnel(relay: Relay) {
        val (address, destinationPort) = synchronized(lock) { destination } ?: return
        val old = synchronized(lock) { relay.tunnel.also { relay.tunnel = null } }
        old?.let(listener::tunnelRemoved)
        synchronized(lock) {
            relay.request(MoblinkRequest.StartTunnel(address, destinationPort)) { result, data ->
                val tunnelPort = (data as? MoblinkResponse.StartTunnel)?.port
                val host = relay.socket.remoteSocketAddress?.address?.hostAddress
                if (result != MoblinkResult.OK || tunnelPort == null || host == null) {
                    return@request
                }
                // The tunnel is at the relay's address, on the port it answered with.
                val tunnel = MoblinkTunnel(host.substringBefore('%'), tunnelPort, relay.relayId ?: return@request, relay.name)
                val stillWanted = synchronized(lock) { relays[relay.socket] === relay && destination != null }
                if (stillWanted) {
                    synchronized(lock) { relay.tunnel = tunnel }
                    listener.tunnelAdded(tunnel)
                }
            }
        }
    }

    private fun isLockedOut(host: String): Boolean = synchronized(lock) {
        val (count, lockedUntil) = failures[host] ?: return false
        count >= MAXIMUM_FAILURES && clock() < lockedUntil
    }

    private fun recordFailure(host: String) = synchronized(lock) {
        val (count, lockedUntil) = failures[host] ?: (0 to 0L)
        val newCount = if (count >= MAXIMUM_FAILURES && clock() >= lockedUntil) 1 else count + 1
        failures[host] = newCount to if (newCount >= MAXIMUM_FAILURES) clock() + LOCKOUT_MILLIS else 0L
    }

    private fun handleIdentify(relay: Relay, message: MoblinkMessageToStreamer.Identify) {
        val host = relay.socket.remoteSocketAddress?.address?.hostAddress.orEmpty()
        val expected = remoteControlHashPassword(relay.challenge, relay.salt, password)
        if (!MessageDigest.isEqual(expected.toByteArray(), message.authentication.toByteArray())) {
            recordFailure(host)
            relay.send(MoblinkMessageToRelay.Identified(MoblinkResult.WRONG_PASSWORD))
            relay.socket.close()
            return
        }
        // Upstream: a relay that reconnects replaces its old connection.
        val replaced = synchronized(lock) {
            failures.remove(host)
            val previous = relays.values.firstOrNull { it !== relay && it.relayId == message.id }
            previous?.let { relays.remove(it.socket) }
            relay.relayId = message.id
            relay.name = message.name.substringBefore('\n').trim().take(30)
            relay.identified = true
            previous
        }
        replaced?.tunnel?.let(listener::tunnelRemoved)
        replaced?.socket?.close()
        relay.send(MoblinkMessageToRelay.Identified(MoblinkResult.OK))
        startTunnel(relay)
        updateStatus()
        listener.relaysChanged()
    }

    private inner class Server(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onStart() = Unit

        override fun onOpen(socket: WebSocket, handshake: ClientHandshake) {
            val local = socket.localSocketAddress?.address
            val host = socket.remoteSocketAddress?.address?.hostAddress.orEmpty()
            if (local == null || !acceptLocalAddress(local) || isLockedOut(host)) {
                socket.close()
                return
            }
            val relay = Relay(socket)
            synchronized(lock) { relays[socket] = relay }
            relay.send(MoblinkMessageToRelay.Hello(REMOTE_CONTROL_API_VERSION, MoblinkAuthentication(relay.challenge, relay.salt)))
        }

        override fun onClose(socket: WebSocket, code: Int, reason: String?, remote: Boolean) {
            val relay = synchronized(lock) { relays.remove(socket) } ?: return
            relay.tunnel?.let(listener::tunnelRemoved)
            listener.relaysChanged()
        }

        override fun onMessage(socket: WebSocket, message: String) {
            val relay = synchronized(lock) { relays[socket] } ?: return
            val parsed = try {
                MoblinkMessageToStreamer.fromJson(message)
            } catch (e: Exception) {
                // Upstream closes on anything it cannot decode.
                socket.close()
                return
            }
            when (parsed) {
                is MoblinkMessageToStreamer.Identify -> handleIdentify(relay, parsed)
                is MoblinkMessageToStreamer.Response -> {
                    val callback = synchronized(lock) { if (relay.identified) relay.requests.remove(parsed.id) else null }
                    callback?.invoke(parsed.result, parsed.data)
                }
            }
        }

        override fun onError(socket: WebSocket?, error: Exception) = Unit
    }

    companion object {
        const val MAXIMUM_FAILURES = 5
        const val LOCKOUT_MILLIS = 60_000L
    }
}
