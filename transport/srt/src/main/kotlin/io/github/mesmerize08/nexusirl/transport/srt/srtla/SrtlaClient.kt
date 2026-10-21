// Port of Moblin/Media/Srtla/Client/SrtlaClient.swift @97e26d2 (the "Moblin" SRT implementation
// path; the libsrt LocalListener path is not needed until the post-v1 "Official" option).
package io.github.mesmerize08.nexusirl.transport.srt.srtla

import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.transport.srt.isSrtDataPacket
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLinkFactory
import java.util.UUID

/** User priority for one bonding path (upstream `SettingsStreamSrtConnectionPriority`). */
data class ConnectionPriority(
    val name: String,
    val priority: Int,
    val enabled: Boolean,
    val relayId: UUID? = null,
)

data class ConnectionPriorities(val enabled: Boolean = false, val priorities: List<ConnectionPriority> = emptyList())

/** Per-path statistics for the bonding status indicator. */
data class BondingConnection(val name: String, val usage: Long, val rtt: Int)

interface SrtlaClientListener {
    /** All paths needed to start are registered; the SRT handshake can begin. */
    fun onReady()

    fun onError(message: String)

    /** An SRT packet from the server (data or control), for the SRT sender. */
    fun onPacketFromServer(packet: ByteArray)

    fun onMoblinkDestinationAddress(address: String, port: Int) {}

    fun onMoblinkRestartTunnel(relayId: UUID) {}
}

/**
 * SRTLA bonding client. With [passThrough] it is a plain single-path SRT transport (upstream uses
 * the same class for `srt://`). Confined to [executor] (upstream `srtlaClientQueue`); the public
 * methods post to it, except [handleLocalPacket] which must be called on it.
 */
class SrtlaClient(
    private val listener: SrtlaClientListener,
    private val passThrough: Boolean,
    private val mpegtsPacketsPerPacket: Int,
    private val packetPadding: Boolean,
    private val linkFactory: UdpLinkFactory,
    private val executor: SerialExecutor,
    private val clock: HostClock,
    networkInterfaceNames: Map<String, String> = emptyMap(),
    connectionPriorities: ConnectionPriorities = ConnectionPriorities(),
) {
    private enum class State { IDLE, WAIT_FOR_REMOTE_SOCKET_CONNECTED, WAIT_FOR_PROBE, WAIT_FOR_GROUP_ID, WAIT_FOR_REGISTERED, RUNNING }

    private var remoteConnections = ArrayList<RemoteConnection>()
    private var connectTimer: Cancellable? = null
    private var state = State.IDLE
    private var host = ""
    private var port = 0
    private var groupId: ByteArray? = null
    private var totalByteCount = 0L
    private val interfaceNames = HashMap(networkInterfaceNames)
    private var priorities = normalizePriorities(connectionPriorities)
    private var latestFlushDataPacketsTime = clock.nanoTime()
    private var availableInterfaces: List<BondingInterface> = emptyList()

    init {
        if (passThrough) {
            remoteConnections.add(newRemoteConnection(null, null, 1f))
        }
    }

    private fun newRemoteConnection(
        type: InterfaceType?,
        bondingInterface: BondingInterface?,
        priority: Float,
        relayId: UUID? = null,
        relayName: String? = null,
    ) = RemoteConnection(
        type, mpegtsPacketsPerPacket, packetPadding, bondingInterface, interfaceNames, priority,
        linkFactory, executor, clock, relayId, relayName,
    )

    /** Starts connecting to [host]:[port]; fails with [SrtlaClientListener.onError] after [timeoutSeconds]. */
    fun start(host: String, port: Int, timeoutSeconds: Double) {
        executor.execute {
            totalByteCount = 0
            this.host = host
            this.port = port
            for (connection in remoteConnections) {
                startRemote(connection)
            }
            if (!passThrough) {
                // Upstream starts NWPathMonitor here; its first callback adds one path per interface.
                applyInterfaces()
            }
            connectTimer = executor.schedule((timeoutSeconds * 1e9).toLong()) {
                onDisconnected("connect timer expired")
            }
            state = State.WAIT_FOR_REMOTE_SOCKET_CONNECTED
            listener.onMoblinkDestinationAddress(host, port)
        }
    }

    fun stop() {
        executor.execute { stopInternal() }
    }

    private fun stopInternal() {
        for (connection in remoteConnections) {
            stopRemote(connection)
        }
        remoteConnections = ArrayList()
        connectTimer?.cancel()
        state = State.IDLE
    }

    /**
     * Replaces the set of available network paths (replaces upstream's `NWPathMonitor` callback).
     * Fed by the Android connectivity layer; ignored in pass-through mode.
     */
    fun setInterfaces(interfaces: List<BondingInterface>) {
        executor.execute {
            availableInterfaces = interfaces
            if (!passThrough && state != State.IDLE) {
                applyInterfaces()
            }
        }
    }

    private fun applyInterfaces() {
        val available = availableInterfaces.filter { it.type != InterfaceType.OTHER }
        val kept = ArrayList<RemoteConnection>()
        for (connection in remoteConnections) {
            val bondingInterface = connection.bondingInterface
            if (bondingInterface == null || availableInterfaces.any { it.id == bondingInterface.id }) {
                kept.add(connection)
            } else {
                stopRemote(connection)
            }
        }
        for (bondingInterface in available) {
            // Moblink relay paths may be bound to Wi-Fi too; they are not the Wi-Fi path itself.
            if (kept.any { it.relayId == null && it.bondingInterface?.id == bondingInterface.id }) {
                continue
            }
            val connection = newRemoteConnection(
                bondingInterface.type,
                bondingInterface,
                getConnectionPriority(interfaceName(bondingInterface.type, bondingInterface)),
            )
            kept.add(connection)
            startRemote(connection)
            groupId?.let { connection.register(it) }
        }
        // Cellular first, then Wi-Fi, then the rest (upstream ordering, as a stable rank).
        remoteConnections = ArrayList(
            kept.sortedBy {
                when (it.type) {
                    InterfaceType.CELLULAR -> 0
                    InterfaceType.WIFI -> 1
                    else -> 2
                }
            },
        )
    }

    /**
     * Adds a Moblink relay tunnel endpoint as an extra bonding path. [via] is the local network the
     * relay is reached on (Android binds it to Wi-Fi so it works while mobile data is the default).
     */
    fun addMoblink(host: String, port: Int, relayId: UUID, name: String, via: BondingInterface? = null) {
        executor.execute {
            if (state == State.IDLE) {
                return@execute
            }
            val connection = newRemoteConnection(InterfaceType.OTHER, via, getRelayConnectionPriority(relayId), relayId, name)
            connection.listener = remoteListener
            connection.start(host, port)
            groupId?.let { connection.register(it) }
            remoteConnections.add(connection)
        }
    }

    fun removeMoblink(host: String, port: Int) {
        executor.execute {
            if (state == State.IDLE) {
                return@execute
            }
            val connection = remoteConnections.firstOrNull { it.destinationHost == host && it.destinationPort == port }
                ?: return@execute
            stopRemote(connection)
            remoteConnections.remove(connection)
        }
    }

    fun setNetworkInterfaceNames(names: Map<String, String>) {
        executor.execute {
            interfaceNames.clear()
            interfaceNames.putAll(names)
        }
    }

    fun setConnectionPriorities(connectionPriorities: ConnectionPriorities) {
        executor.execute {
            priorities = normalizePriorities(connectionPriorities)
            for (connection in remoteConnections) {
                val relayId = connection.relayId
                connection.setPriority(
                    if (relayId != null) {
                        getRelayConnectionPriority(relayId)
                    } else {
                        getConnectionPriority(interfaceName(connection.type, connection.bondingInterface))
                    },
                )
            }
        }
    }

    /** Statistics per enabled path; must be called on the executor. */
    fun connectionStatistics(): List<BondingConnection> = remoteConnections.filter { it.isEnabled() }.mapNotNull { connection ->
        connection.getDataSentDelta()?.let { BondingConnection(connection.typeString, it, connection.rtt) }
    }

    fun getTotalByteCount(): Long = totalByteCount

    /** Sends one SRT packet from the local sender over the best path. Must be called on the executor. */
    fun handleLocalPacket(packet: ByteArray) {
        val connection = selectRemoteConnection() ?: return
        connection.sendSrtPacket(packet)
        if (isSrtDataPacket(packet)) {
            val now = clock.nanoTime()
            if (now - latestFlushDataPacketsTime > 15_000_000) {
                latestFlushDataPacketsTime = now
                for (remoteConnection in remoteConnections) {
                    remoteConnection.flushDataPackets()
                }
            }
        }
        totalByteCount += packet.size
    }

    private fun normalizePriorities(connectionPriorities: ConnectionPriorities): List<ConnectionPriority> {
        if (!connectionPriorities.enabled) {
            return emptyList()
        }
        val lowest = connectionPriorities.priorities.filter { it.enabled }.minOfOrNull { it.priority } ?: return emptyList()
        return connectionPriorities.priorities.map { it.copy(priority = it.priority - lowest + 1) }
    }

    private fun getConnectionPriority(name: String): Float {
        val priority = priorities.firstOrNull { it.name == name } ?: return 1f
        return if (priority.enabled) priority.priority.toFloat() else 0f
    }

    private fun getRelayConnectionPriority(relayId: UUID): Float {
        val priority = priorities.firstOrNull { it.relayId == relayId } ?: return 1f
        return if (priority.enabled) priority.priority.toFloat() else 0f
    }

    private fun startRemote(connection: RemoteConnection) {
        connection.listener = remoteListener
        connection.start(host, port)
    }

    private fun stopRemote(connection: RemoteConnection) {
        connection.stop("Stopping stream")
        connection.listener = null
    }

    private fun startListener() {
        state = State.RUNNING
        connectTimer?.cancel()
        listener.onReady()
    }

    private fun onDisconnected(message: String) {
        if (state == State.IDLE) {
            return
        }
        stopInternal()
        listener.onError(message)
    }

    private fun selectRemoteConnection(): RemoteConnection? {
        var selected: RemoteConnection? = null
        var selectedScore = -1
        for (connection in remoteConnections) {
            val score = connection.score()
            if (score > selectedScore) {
                selected = connection
                selectedScore = score
            }
        }
        return selected
    }

    private val remoteListener = object : RemoteConnectionListener {
        override fun onSocketConnected(connection: RemoteConnection) {
            if (state != State.WAIT_FOR_REMOTE_SOCKET_CONNECTED && state != State.WAIT_FOR_PROBE) {
                return
            }
            if (passThrough) {
                startListener()
            } else {
                connection.probe()
                state = State.WAIT_FOR_PROBE
            }
        }

        override fun onRegNgp(connection: RemoteConnection) {
            if (state != State.WAIT_FOR_PROBE) {
                return
            }
            connection.sendSrtlaReg1()
            state = State.WAIT_FOR_GROUP_ID
        }

        override fun onReg2(groupId: ByteArray) {
            if (state != State.WAIT_FOR_GROUP_ID) {
                return
            }
            this@SrtlaClient.groupId = groupId
            for (connection in remoteConnections) {
                connection.register(groupId)
            }
            state = State.WAIT_FOR_REGISTERED
        }

        override fun onRegistered() {
            if (state != State.WAIT_FOR_REGISTERED) {
                return
            }
            startListener()
        }

        override fun onPacket(packet: ByteArray) {
            listener.onPacketFromServer(packet)
            totalByteCount += packet.size
        }

        override fun onSrtAck(sn: Long) {
            for (connection in remoteConnections) {
                connection.handleSrtAckSn(sn)
            }
        }

        override fun onSrtNak(sn: Long) {
            for (connection in remoteConnections) {
                connection.handleSrtNakSn(sn)
            }
        }

        override fun onSrtlaAck(sn: Long) {
            for (connection in remoteConnections) {
                connection.handleSrtlaAckSn(sn)
            }
        }

        override fun onMoblinkReconnect(connection: RemoteConnection) {
            connection.relayId?.let { listener.onMoblinkRestartTunnel(it) }
        }
    }

    private fun interfaceName(type: InterfaceType?, bondingInterface: BondingInterface?): String = when (type) {
        InterfaceType.CELLULAR -> "Cellular"
        InterfaceType.WIFI -> "WiFi"
        else -> bondingInterface?.name ?: ""
    }
}
