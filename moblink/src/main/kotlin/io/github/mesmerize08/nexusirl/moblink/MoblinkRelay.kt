// Port of Moblin/Moblink/MoblinkRelay.swift @97e26d2: the relay side, which lends this phone's
// mobile data (or Ethernet) to a streamer on the same Wi-Fi.
package io.github.mesmerize08.nexusirl.moblink

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.remotecontrol.remoteControlHashPassword
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import okhttp3.OkHttpClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Upstream `MoblinkRelayState`, the overall state shown to the user. */
enum class MoblinkRelayState(val text: String) {
    WAITING_FOR_STREAMERS("Waiting for streamers"),
    NO_INTERFACE("No interface"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    WRONG_PASSWORD("Wrong password"),
    UNKNOWN_ERROR("Unknown error"),
}

/** Upstream's per-interface `RelayState`. */
enum class MoblinkInterfaceState { NONE, CONNECTING, CONNECTED, WAITING_FOR_CELLULAR, WRONG_PASSWORD, UNKNOWN_ERROR }

/** Battery and thermal state for the streamer's relay list (upstream `moblinkRelayGetStatus`). Any thread. */
fun interface MoblinkStatusProvider {
    fun status(): MoblinkResponse.Status
}

/**
 * Upstream `UUID.add(data:)`: the relay id for a non-cellular interface is the main id with the
 * interface name's bytes added (reversed, wrapping), so each path gets its own stable id.
 */
fun moblinkRelayId(relayId: UUID, interfaceName: String): UUID {
    val uuid = ByteArray(16)
    var most = relayId.mostSignificantBits
    var least = relayId.leastSignificantBits
    for (i in 7 downTo 0) {
        uuid[i] = (most and 0xFF).toByte()
        most = most shr 8
        uuid[i + 8] = (least and 0xFF).toByte()
        least = least shr 8
    }
    val bytes = ByteArray(16) { uuid[15 - it] }
    for ((index, value) in interfaceName.toByteArray(Charsets.UTF_8).reversed().withIndex()) {
        bytes[index % 16] = (bytes[index % 16] + value).toByte()
    }
    val result = ByteArray(16) { bytes[15 - it] }
    var newMost = 0L
    var newLeast = 0L
    for (i in 0 until 8) {
        newMost = (newMost shl 8) or (result[i].toLong() and 0xFF)
        newLeast = (newLeast shl 8) or (result[i + 8].toLong() and 0xFF)
    }
    return UUID(newMost, newLeast)
}

/**
 * One relay connection: talks to the streamer over Wi-Fi and, on `startTunnel`, forwards UDP
 * between a local port (which the streamer sends to) and the destination over [destination],
 * e.g. the mobile data network. Packets are passed through unchanged.
 */
class MoblinkInterfaceRelay(
    private val id: UUID,
    private val name: String,
    streamerUrl: String,
    private val password: String,
    val destination: BondingInterface,
    private val status: MoblinkStatusProvider,
    client: OkHttpClient,
    private val onStateChanged: () -> Unit,
) {
    @Volatile
    var state = MoblinkInterfaceState.NONE
        private set

    /** Whether this relay reports battery and thermal state (upstream: the cellular one). */
    @Volatile
    var isMain = false

    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "moblink-relay").apply { isDaemon = true } }
    private var reconnect: ScheduledFuture<*>? = null
    private val tunnelLock = Any()
    private var tunnel: Tunnel? = null

    private val socket = ReconnectingWebSocket(
        streamerUrl,
        object : ReconnectingWebSocket.Listener {
            override fun onConnected(socket: ReconnectingWebSocket) = Unit

            override fun onMessage(socket: ReconnectingWebSocket, text: String) = handleMessage(text)

            override fun onDisconnected(socket: ReconnectingWebSocket) {
                setState(MoblinkInterfaceState.CONNECTING)
                stopTunnel()
            }
        },
        client,
    )

    fun start() {
        setState(MoblinkInterfaceState.CONNECTING)
        socket.start()
    }

    fun stop() {
        reconnect?.cancel(false)
        timer.shutdownNow()
        socket.stop()
        stopTunnel()
        setState(MoblinkInterfaceState.NONE)
    }

    private fun setState(newState: MoblinkInterfaceState) {
        if (state != newState) {
            state = newState
            onStateChanged()
        }
    }

    private fun send(message: MoblinkMessageToStreamer) = socket.send(message.toJson())

    /** Upstream `reconnect`: drop the tunnel and connect again after 5 seconds. */
    private fun reconnectLater() {
        stopTunnel()
        reconnect?.cancel(false)
        reconnect = try {
            timer.schedule({ socket.restart() }, 5, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            null
        }
    }

    /** Handles one message from the streamer; public for tests. */
    fun handleMessage(text: String) {
        val message = try {
            MoblinkMessageToRelay.fromJson(text)
        } catch (e: Exception) {
            return
        }
        when (message) {
            is MoblinkMessageToRelay.Hello -> {
                val hash = remoteControlHashPassword(message.authentication.challenge, message.authentication.salt, password)
                send(MoblinkMessageToStreamer.Identify(id, name, hash))
            }
            is MoblinkMessageToRelay.Identified -> when (message.result) {
                MoblinkResult.OK -> setState(MoblinkInterfaceState.CONNECTED)
                MoblinkResult.WRONG_PASSWORD -> {
                    reconnectLater()
                    setState(MoblinkInterfaceState.WRONG_PASSWORD)
                }
                else -> {
                    reconnectLater()
                    setState(MoblinkInterfaceState.UNKNOWN_ERROR)
                }
            }
            is MoblinkMessageToRelay.Request -> when (val request = message.data) {
                is MoblinkRequest.StartTunnel -> startTunnel(message.id, request.address, request.port)
                MoblinkRequest.Status -> send(
                    MoblinkMessageToStreamer.Response(
                        message.id,
                        MoblinkResult.OK,
                        if (isMain) status.status() else MoblinkResponse.Status(),
                    ),
                )
                MoblinkRequest.Unknown -> send(MoblinkMessageToStreamer.Response(message.id, MoblinkResult.UNKNOWN_REQUEST))
            }
        }
    }

    private fun startTunnel(requestId: Int, address: String, port: Int) {
        stopTunnel()
        val newTunnel = try {
            Tunnel(destination, address, port)
        } catch (e: Exception) {
            reconnectLater()
            return
        }
        synchronized(tunnelLock) { tunnel = newTunnel }
        newTunnel.start { reconnectLater() }
        setState(MoblinkInterfaceState.CONNECTED)
        send(MoblinkMessageToStreamer.Response(requestId, MoblinkResult.OK, MoblinkResponse.StartTunnel(newTunnel.localPort)))
    }

    private fun stopTunnel() {
        synchronized(tunnelLock) {
            tunnel?.stop()
            tunnel = null
        }
    }

    /**
     * The UDP forwarding: a socket on any local address for the streamer (upstream NWListener;
     * the first sender becomes the streamer), and a socket on [destination] connected to the
     * server.
     */
    private class Tunnel(destination: BondingInterface, address: String, port: Int) {
        private val streamerSocket = DatagramSocket(0)
        private val destinationSocket = DatagramSocket()

        @Volatile
        private var streamer: SocketAddress? = null

        @Volatile
        private var running = true
        val localPort: Int = streamerSocket.localPort

        init {
            try {
                destination.bind(destinationSocket)
                val target = destination.resolve(address).first()
                destinationSocket.connect(InetSocketAddress(target, port))
            } catch (e: Exception) {
                streamerSocket.close()
                destinationSocket.close()
                throw e
            }
        }

        fun start(onError: () -> Unit) {
            worker("moblink-to-destination", onError) {
                val buffer = ByteArray(MAXIMUM_PACKET_SIZE)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    streamerSocket.receive(packet)
                    streamer = packet.socketAddress
                    destinationSocket.send(DatagramPacket(packet.data, packet.offset, packet.length))
                }
            }
            worker("moblink-to-streamer", onError) {
                val buffer = ByteArray(MAXIMUM_PACKET_SIZE)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    destinationSocket.receive(packet)
                    streamer?.let { streamerSocket.send(DatagramPacket(packet.data, packet.offset, packet.length, it)) }
                }
            }
        }

        /** Runs [body] on its own thread; a failure while still running restarts the relay (upstream `reconnect`). */
        private fun worker(name: String, onError: () -> Unit, body: () -> Unit) {
            Thread({
                try {
                    body()
                } catch (e: Exception) {
                    if (running) {
                        onError()
                    }
                }
            }, name).apply { isDaemon = true }.start()
        }

        fun stop() {
            running = false
            streamerSocket.close()
            destinationSocket.close()
        }

        private companion object {
            const val MAXIMUM_PACKET_SIZE = 2048
        }
    }
}

/**
 * Upstream `MoblinkRelay`: one [MoblinkInterfaceRelay] per usable destination (mobile data and
 * Ethernet) for one streamer, with ids and names derived like upstream so the streamer can tell
 * them apart. Call [setInterfaces] whenever the networks change.
 */
class MoblinkRelay(
    private val relayId: UUID,
    private val name: String,
    val streamerUrl: String,
    private val password: String,
    private val status: MoblinkStatusProvider,
    private val client: OkHttpClient,
    private val onStateChanged: (MoblinkRelayState) -> Unit,
) {
    private val relays = LinkedHashMap<String, MoblinkInterfaceRelay>()
    private var started = false

    @Synchronized
    fun setInterfaces(interfaces: List<BondingInterface>) {
        started = true
        val wanted = interfaces.filter { it.type == InterfaceType.CELLULAR || it.type == InterfaceType.WIRED_ETHERNET }
        for (id in relays.keys.toList()) {
            if (wanted.none { it.id == id }) {
                relays.remove(id)?.stop()
            }
        }
        for (destination in wanted) {
            if (destination.id in relays) {
                continue
            }
            val cellular = destination.type == InterfaceType.CELLULAR
            val relay = MoblinkInterfaceRelay(
                id = if (cellular) relayId else moblinkRelayId(relayId, destination.name),
                name = if (cellular) name else "$name-${destination.name}",
                streamerUrl = streamerUrl,
                password = password,
                destination = destination,
                status = status,
                client = client,
                onStateChanged = ::reportState,
            )
            relays[destination.id] = relay
            relay.start()
        }
        // Upstream: the cellular relay (else the first) reports battery and thermal state.
        val main = relays.values.firstOrNull { it.destination.type == InterfaceType.CELLULAR } ?: relays.values.firstOrNull()
        relays.values.forEach { it.isMain = it === main }
        reportState()
    }

    @Synchronized
    fun stop() {
        started = false
        relays.values.forEach { it.stop() }
        relays.clear()
    }

    /** Upstream `relayStateChanged`. */
    private fun reportState() {
        val states = synchronized(this) { relays.values.map { it.state } }
        var state = MoblinkRelayState.NO_INTERFACE
        for (relayState in states) {
            state = when (relayState) {
                MoblinkInterfaceState.CONNECTING -> MoblinkRelayState.CONNECTING
                MoblinkInterfaceState.CONNECTED -> if (state == MoblinkRelayState.NO_INTERFACE) MoblinkRelayState.CONNECTED else state
                MoblinkInterfaceState.WRONG_PASSWORD -> MoblinkRelayState.WRONG_PASSWORD
                MoblinkInterfaceState.UNKNOWN_ERROR -> MoblinkRelayState.UNKNOWN_ERROR
                else -> state
            }
        }
        onStateChanged(state)
    }
}
