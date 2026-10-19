// Port of Moblin/Media/Srtla/Server/SrtlaServer.swift, SrtlaServerClient.swift and
// SrtlaServerClientConnection.swift @97e26d2 (the SRTLA relay; upstream's libsrt receiver behind
// it is replaced by any SRT listener on [srtPort], e.g. libsrt via ffmpeg in tests).
// SRTLA is a bonding protocol on top of SRT, designed by rationalsa for the BELABOX project.
package io.github.mesmerize08.nexusirl.transport.srt.srtla.server

import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import io.github.mesmerize08.nexusirl.transport.srt.SRT_CONTROL_PACKET_TYPE_BIT
import io.github.mesmerize08.nexusirl.transport.srt.SrtPacketType
import io.github.mesmerize08.nexusirl.transport.srt.SrtlaPacketType
import io.github.mesmerize08.nexusirl.transport.srt.createSrtlaPacket
import io.github.mesmerize08.nexusirl.transport.srt.getSrtControlPacketType
import io.github.mesmerize08.nexusirl.transport.srt.getSrtSequenceNumber
import io.github.mesmerize08.nexusirl.transport.srt.getUInt32Be
import io.github.mesmerize08.nexusirl.transport.srt.isSrtDataPacket
import io.github.mesmerize08.nexusirl.transport.srt.isSrtSnAcked
import io.github.mesmerize08.nexusirl.transport.srt.processSrtNak
import io.github.mesmerize08.nexusirl.transport.srt.setUInt32Be
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import kotlin.random.Random

private const val SRT_CONTROL_TYPE_SIZE = 2
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val PERIODIC_TIMER_NANOS = 3 * NANOS_PER_SECOND
private const val CLIENT_REMOVE_TIMEOUT_NANOS = 10 * NANOS_PER_SECOND
private const val CONNECTION_REMOVE_TIMEOUT_NANOS = 10 * NANOS_PER_SECOND
private const val ACK_PACKET_LENGTH = SRT_CONTROL_TYPE_SIZE + 2 + 10 * 4
private val SRTLA_PACKET_TYPES = setOf(
    SrtlaPacketType.KEEPALIVE, SrtlaPacketType.ACK, SrtlaPacketType.REG1, SrtlaPacketType.REG2,
    SrtlaPacketType.REG3, SrtlaPacketType.REG_ERR, SrtlaPacketType.REG_NGP, SrtlaPacketType.REG_NAK,
)

/** Group id bytes as a map key. */
private class GroupId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is GroupId && bytes.contentEquals(other.bytes)

    override fun hashCode() = bytes.contentHashCode()
}

/**
 * SRTLA receiver: accepts bonded SRTLA paths on [srtlaPort] and relays each group, as one SRT
 * connection, to the SRT listener on 127.0.0.1:[srtPort]. Confined to [executor]
 * (upstream `srtlaServerQueue`).
 */
class SrtlaServer(
    private val srtlaPort: Int,
    private val srtPort: Int,
    private val executor: SerialExecutor,
    private val clock: HostClock,
) {
    private var socket: DatagramSocket? = null
    private val clients = HashMap<GroupId, SrtlaServerClient>()
    private val connectionsByEndpoint = HashMap<SocketAddress, SrtlaServerClientConnection>()
    private var periodicTimer: Cancellable? = null

    /** The bound SRTLA port (useful when started with port 0). */
    @Volatile
    var localPort: Int = 0
        private set

    val numberOfClients: Int
        get() = clients.size

    fun start() {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(srtlaPort))
        }
        this.socket = socket
        localPort = socket.localPort
        Thread({ receiveLoop(socket) }, "srtla-server").apply {
            isDaemon = true
            start()
        }
        executor.execute {
            periodicTimer = executor.scheduleRepeating(PERIODIC_TIMER_NANOS) { handlePeriodicTimer() }
        }
    }

    fun stop() {
        socket?.close()
        executor.execute {
            periodicTimer?.cancel()
            for (client in clients.values) {
                client.stop()
            }
            clients.clear()
            connectionsByEndpoint.clear()
        }
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (e: SocketException) {
                return
            } catch (e: IOException) {
                continue
            }
            val packet = datagram.data.copyOf(datagram.length)
            val endpoint = datagram.socketAddress
            executor.execute { handlePacket(endpoint, packet) }
        }
    }

    internal fun send(endpoint: SocketAddress, packet: ByteArray) {
        try {
            socket?.send(DatagramPacket(packet, packet.size, endpoint))
        } catch (e: IOException) {
            // Idempotent send; loss is handled by SRT.
        }
    }

    private fun handlePeriodicTimer() {
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next().value
            if (client.handlePeriodicTimer()) {
                client.stop()
                iterator.remove()
            }
        }
        connectionsByEndpoint.values.removeAll { it.removed }
    }

    private fun handlePacket(endpoint: SocketAddress, packet: ByteArray) {
        connectionsByEndpoint[endpoint]?.let {
            it.handlePacketFromClient(packet)
            return
        }
        if (packet.size < SRT_CONTROL_TYPE_SIZE || isSrtDataPacket(packet)) {
            return
        }
        when (getSrtControlPacketType(packet)) {
            SrtlaPacketType.REG1 -> handleSrtlaReg1(endpoint, packet)
            SrtlaPacketType.REG2 -> handleSrtlaReg2(endpoint, packet)
        }
    }

    private fun handleSrtlaReg1(endpoint: SocketAddress, packet: ByteArray) {
        if (packet.size != 258) {
            return
        }
        val groupId = packet.copyOfRange(SRT_CONTROL_TYPE_SIZE, SRT_CONTROL_TYPE_SIZE + 128) + Random.nextBytes(128)
        val key = GroupId(groupId)
        if (clients.containsKey(key)) {
            return
        }
        clients[key] = SrtlaServerClient(this, srtPort, executor, clock)
        val response = createSrtlaPacket(SrtlaPacketType.REG2, 258)
        groupId.copyInto(response, SRT_CONTROL_TYPE_SIZE)
        send(endpoint, response)
    }

    private fun handleSrtlaReg2(endpoint: SocketAddress, packet: ByteArray) {
        if (packet.size != 258) {
            return
        }
        val client = clients[GroupId(packet.copyOfRange(SRT_CONTROL_TYPE_SIZE, packet.size))]
        if (client == null) {
            send(endpoint, createSrtlaPacket(SrtlaPacketType.REG_NGP, SRT_CONTROL_TYPE_SIZE))
            return
        }
        connectionsByEndpoint[endpoint] = client.addConnection(endpoint)
        send(endpoint, createSrtlaPacket(SrtlaPacketType.REG3, SRT_CONTROL_TYPE_SIZE))
    }
}

/** Collects NAKed sequence numbers and periodically re-sends them as one NAK (upstream `NakPacket`). */
private class NakPacket {
    private val sns = ArrayList<Long>()
    private var latestNakTimestamp: Long? = null
    private var latestNakDestinationSrtSocketId: Long? = null

    fun setLatest(timestamp: Long, socketId: Long) {
        latestNakTimestamp = timestamp
        latestNakDestinationSrtSocketId = socketId
    }

    fun add(sn: Long) {
        val index = sns.indexOfFirst { it >= sn }
        if (index >= 0) {
            if (sns[index] != sn) {
                sns.add(index, sn)
            }
        } else {
            sns.add(sn)
        }
    }

    fun removeUpTo(ackSn: Long) {
        sns.removeAll { isSrtSnAcked(it, ackSn) }
    }

    fun remove(sn: Long) {
        sns.remove(sn)
    }

    fun pack(): ByteArray? {
        val timestamp = latestNakTimestamp ?: return null
        val socketId = latestNakDestinationSrtSocketId ?: return null
        if (sns.isEmpty()) {
            return null
        }
        val writer = ByteWriter()
        writer.writeUInt16(SrtPacketType.NAK or SRT_CONTROL_PACKET_TYPE_BIT)
        writer.writeUInt16(0)
        writer.writeUInt32(0)
        writer.writeUInt32(timestamp)
        writer.writeUInt32(socketId)
        for (sn in sns.take(1300 / 4)) {
            writer.writeUInt32(sn)
        }
        return writer.toByteArray()
    }
}

/** One bonded group, relayed as one SRT connection to the local SRT listener. */
internal class SrtlaServerClient(
    private val server: SrtlaServer,
    srtPort: Int,
    private val executor: SerialExecutor,
    private val clock: HostClock,
) {
    private val localSocket = DatagramSocket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), srtPort)) }
    private val connections = ArrayList<SrtlaServerClientConnection>()
    private var latestConnection: SrtlaServerClientConnection? = null
    private val createdAt = clock.nanoTime()
    private val nakPacket = NakPacket()
    private val periodicNakTimer: Cancellable = executor.scheduleRepeating(100_000_000) { handlePeriodicNakTimer() }
    private val dataPacketsToSend = ArrayList<ByteArray>()
    private var latestFlushDataPacketsTime = clock.nanoTime()

    init {
        Thread({ receiveFromLocalSrtServer() }, "srtla-server-client").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        periodicNakTimer.cancel()
        localSocket.close()
    }

    private fun receiveFromLocalSrtServer() {
        val buffer = ByteArray(2048)
        while (!localSocket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                localSocket.receive(datagram)
            } catch (e: SocketException) {
                return
            } catch (e: IOException) {
                continue
            }
            val packet = datagram.data.copyOf(datagram.length)
            executor.execute { handlePacketFromLocalSrtServer(packet) }
        }
    }

    fun addConnection(endpoint: SocketAddress): SrtlaServerClientConnection {
        connections.firstOrNull { it.endpoint == endpoint }?.let { return it }
        val connection = SrtlaServerClientConnection(endpoint, server, clock, this)
        connections.add(connection)
        return connection
    }

    private fun handlePeriodicNakTimer() {
        nakPacket.pack()?.let { latestConnection?.sendPacket(it) }
    }

    private fun handlePacketFromLocalSrtServer(packet: ByteArray) {
        if (packet.size < SRT_CONTROL_TYPE_SIZE) {
            return
        }
        if (isSrtDataPacket(packet)) {
            latestConnection?.sendPacket(packet)
            return
        }
        when (getSrtControlPacketType(packet)) {
            SrtPacketType.ACK -> {
                // ACKs go out on every path so each client path can prune its in-flight set.
                connections.forEach { it.sendPacket(packet) }
                if (packet.size >= 20) {
                    nakPacket.removeUpTo(packet.getUInt32Be(16))
                }
            }
            SrtPacketType.NAK -> {
                connections.forEach { it.sendPacket(packet) }
                if (packet.size >= 16) {
                    processSrtNak(packet) { nakPacket.add(it) }
                    nakPacket.setLatest(packet.getUInt32Be(8), packet.getUInt32Be(12))
                }
            }
            else -> latestConnection?.sendPacket(packet)
        }
    }

    fun handlePacketFromSrtClient(connection: SrtlaServerClientConnection, packet: ByteArray) {
        if (isSrtDataPacket(packet)) {
            nakPacket.remove(getSrtSequenceNumber(packet))
            dataPacketsToSend.add(packet)
            val now = clock.nanoTime()
            if (now - latestFlushDataPacketsTime > 25_000_000) {
                for (data in dataPacketsToSend) {
                    sendToLocal(data)
                }
                dataPacketsToSend.clear()
                latestFlushDataPacketsTime = now
            }
        } else {
            sendToLocal(packet)
        }
        latestConnection = connection
    }

    private fun sendToLocal(packet: ByteArray) {
        try {
            localSocket.send(DatagramPacket(packet, packet.size))
        } catch (e: IOException) {
            // Local SRT server not reachable; SRT recovers or times out.
        }
    }

    /** Returns true when this group should be removed. */
    fun handlePeriodicTimer(): Boolean {
        val now = clock.nanoTime()
        val iterator = connections.iterator()
        while (iterator.hasNext()) {
            val connection = iterator.next()
            if (!connection.isActive(now)) {
                connection.removed = true
                iterator.remove()
            }
        }
        return connections.isEmpty() && now - createdAt > CLIENT_REMOVE_TIMEOUT_NANOS
    }
}

/** One registered SRTLA path of a group. Acks data packets in batches of 10 sequence numbers. */
internal class SrtlaServerClientConnection(
    val endpoint: SocketAddress,
    private val server: SrtlaServer,
    private val clock: HostClock,
    private val client: SrtlaServerClient,
) {
    private var latestReceivedTime = clock.nanoTime()
    private val ackPacket = createSrtlaPacket(SrtlaPacketType.ACK, ACK_PACKET_LENGTH)
    private var nextSnOffset = SRT_CONTROL_TYPE_SIZE + 2
    var removed = false

    fun isActive(now: Long): Boolean = now - latestReceivedTime < CONNECTION_REMOVE_TIMEOUT_NANOS

    fun handlePacketFromClient(packet: ByteArray) {
        if (packet.size < SRT_CONTROL_TYPE_SIZE) {
            return
        }
        latestReceivedTime = clock.nanoTime()
        if (isSrtDataPacket(packet)) {
            if (packet.size < 4) {
                return
            }
            ackPacket.setUInt32Be(getSrtSequenceNumber(packet), nextSnOffset)
            nextSnOffset += 4
            if (nextSnOffset == ACK_PACKET_LENGTH) {
                nextSnOffset = SRT_CONTROL_TYPE_SIZE + 2
                sendPacket(ackPacket.copyOf())
            }
            client.handlePacketFromSrtClient(this, packet)
            return
        }
        when (getSrtControlPacketType(packet)) {
            SrtlaPacketType.KEEPALIVE -> sendPacket(packet)
            SrtlaPacketType.REG2 -> sendPacket(createSrtlaPacket(SrtlaPacketType.REG3, SRT_CONTROL_TYPE_SIZE))
            in SRTLA_PACKET_TYPES -> Unit // Unexpected SRTLA packet; upstream only logs it.
            else -> client.handlePacketFromSrtClient(this, packet)
        }
    }

    fun sendPacket(packet: ByteArray) {
        server.send(endpoint, packet)
    }
}
