// Port of Moblin/Media/Srtla/Client/RemoteConnection.swift @97e26d2
// SRTLA is a bonding protocol on top of SRT, designed by rationalsa for the BELABOX project
// (https://github.com/BELABOX/srtla). This is an independent port of Moblin's MIT implementation.
package io.github.mesmerize08.nexusirl.transport.srt.srtla

import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsPacket
import io.github.mesmerize08.nexusirl.transport.srt.SrtPacketType
import io.github.mesmerize08.nexusirl.transport.srt.SrtlaPacketType
import io.github.mesmerize08.nexusirl.transport.srt.createSrtlaPacket
import io.github.mesmerize08.nexusirl.transport.srt.getSrtControlPacketType
import io.github.mesmerize08.nexusirl.transport.srt.getSrtSequenceNumber
import io.github.mesmerize08.nexusirl.transport.srt.getUInt32Be
import io.github.mesmerize08.nexusirl.transport.srt.isSrtDataPacket
import io.github.mesmerize08.nexusirl.transport.srt.isSrtSnAcked
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLink
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLinkEvent
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLinkFactory
import io.github.mesmerize08.nexusirl.transport.srt.processSrtNak
import java.util.UUID
import kotlin.random.Random

private const val SRT_CONTROL_TYPE_SIZE = 2
private const val WINDOW_DEFAULT = 20
private const val WINDOW_MINIMUM = 1
private const val WINDOW_MAXIMUM = 60
private const val WINDOW_STABLE_MINIMUM = 10
private const val WINDOW_STABLE_MAXIMUM = 20
private const val WINDOW_MULTIPLY = 1000
private const val WINDOW_DECREMENT = 100
private const val WINDOW_INCREMENT = 30
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L

/** A null TS packet (PID 0x1FFF) used for optional datagram padding. */
private val NULL_PACKET: ByteArray = ByteArray(MpegTsPacket.SIZE).also {
    it[0] = 0x47
    it[1] = 0x1F
    it[2] = 0xFF.toByte()
    it[3] = 0x10
}

interface RemoteConnectionListener {
    fun onSocketConnected(connection: RemoteConnection)

    fun onRegNgp(connection: RemoteConnection)

    fun onReg2(groupId: ByteArray)

    fun onRegistered()

    fun onPacket(packet: ByteArray)

    fun onSrtAck(sn: Long)

    fun onSrtNak(sn: Long)

    fun onSrtlaAck(sn: Long)

    fun onMoblinkReconnect(connection: RemoteConnection)
}

/**
 * One SRTLA path (cellular, Wi-Fi, Ethernet or a Moblink relay) with its own congestion window.
 * `type == null` is the single pass-through connection used for plain SRT.
 * Confined to [executor].
 */
class RemoteConnection(
    val type: InterfaceType?,
    private val mpegtsPacketsPerPacket: Int,
    private val packetPadding: Boolean,
    val bondingInterface: BondingInterface?,
    private val interfaceNames: Map<String, String>,
    private var priority: Float,
    private val linkFactory: UdpLinkFactory,
    private val executor: SerialExecutor,
    private val clock: HostClock,
    val relayId: UUID? = null,
    private val relayName: String? = null,
) {
    private enum class State { IDLE, SOCKET_CONNECTING, SHOULD_SEND_REGISTER_REQUEST, WAIT_FOR_REGISTER_RESPONSE, REGISTERED }

    var listener: RemoteConnectionListener? = null
    private var link: UdpLink? = null
    private var connectTimer: Cancellable? = null
    private var keepaliveTimer: Cancellable? = null
    private var latestReceivedTime = clock.nanoTime()
    private val packetsInFlight = HashSet<Long>()
    private var windowSize = 0
    private var hasFullGroupId = false
    private var groupId = ByteArray(0)
    private var state = State.IDLE
    private var keepAliveSendBaseTime = clock.nanoTime()
    var rtt = 0
        private set
    private val dataPacketsToSend = ArrayList<ByteArray>()
    private var totalDataSentByteCount = 0L
    var destinationHost: String? = null
        private set
    var destinationPort: Int? = null
        private set

    val typeString: String
        get() = when (type) {
            InterfaceType.WIFI -> "WiFi"
            InterfaceType.WIRED_ETHERNET -> bondingInterface?.let { interfaceNames[it.name] ?: it.name } ?: "Ethernet"
            InterfaceType.CELLULAR -> "Cellular"
            else -> relayName ?: "Any"
        }

    val isRegistered: Boolean
        get() = state == State.REGISTERED

    fun setPriority(priority: Float) {
        this.priority = priority
    }

    fun start(host: String, port: Int) {
        destinationHost = host
        destinationPort = port
        startInternal()
    }

    private fun startInternal() {
        val host = destinationHost ?: return
        val port = destinationPort ?: return
        if (state != State.IDLE) {
            return
        }
        link = linkFactory.open(host, port, bondingInterface, executor) { event ->
            when (event) {
                UdpLinkEvent.Ready -> handleReady()
                is UdpLinkEvent.Failed -> reconnect("Connection failed: ${event.reason}")
                is UdpLinkEvent.Packet -> handlePacketFromServer(event.data)
            }
        }
        state = State.SOCKET_CONNECTING
    }

    fun stop(@Suppress("UNUSED_PARAMETER") reason: String) {
        link?.close()
        link = null
        cancelAllTimers()
        state = State.IDLE
    }

    /** Scheduling score: higher is better; -1 means not usable. */
    fun score(): Int {
        if (state != State.REGISTERED) {
            return -1
        }
        if (type == null) {
            return 1
        }
        if (priority == 0f) {
            return -1
        }
        val score = windowSize / (packetsInFlight.size + 1)
        return when {
            windowSize > WINDOW_STABLE_MAXIMUM * WINDOW_MULTIPLY -> (score * priority).toInt()
            windowSize > WINDOW_STABLE_MINIMUM * WINDOW_MULTIPLY -> {
                val factor = (windowSize - WINDOW_STABLE_MINIMUM * WINDOW_MULTIPLY).toFloat() /
                    ((WINDOW_STABLE_MAXIMUM - WINDOW_STABLE_MINIMUM) * WINDOW_MULTIPLY)
                val scaledPriority = 1 + (priority - 1) * factor
                (score * scaledPriority).toInt()
            }
            else -> score
        }
    }

    fun isEnabled(): Boolean = priority > 0

    fun sendSrtPacket(packet: ByteArray) = sendPacket(packet)

    fun flushDataPackets() {
        if (dataPacketsToSend.isNotEmpty()) {
            sendDataPackets()
        }
    }

    fun probe() {
        groupId = Random.nextBytes(256)
        sendSrtlaReg2()
    }

    fun register(groupId: ByteArray) {
        this.groupId = groupId
        hasFullGroupId = true
        if (state == State.SHOULD_SEND_REGISTER_REQUEST) {
            sendSrtlaReg2()
            state = State.WAIT_FOR_REGISTER_RESPONSE
        }
    }

    fun sendSrtlaReg1() {
        groupId = Random.nextBytes(256)
        val packet = createSrtlaPacket(SrtlaPacketType.REG1, SRT_CONTROL_TYPE_SIZE + groupId.size)
        groupId.copyInto(packet, SRT_CONTROL_TYPE_SIZE)
        sendPacket(packet)
    }

    fun handleSrtAckSn(ackSn: Long) {
        packetsInFlight.removeIf { isSrtSnAcked(it, ackSn) }
    }

    fun handleSrtNakSn(sn: Long) {
        if (!packetsInFlight.remove(sn)) {
            return
        }
        windowSize = maxOf(windowSize - WINDOW_DECREMENT, WINDOW_MINIMUM * WINDOW_MULTIPLY)
    }

    fun handleSrtlaAckSn(sn: Long) {
        if (packetsInFlight.remove(sn)) {
            if (packetsInFlight.size * WINDOW_MULTIPLY > windowSize) {
                windowSize += WINDOW_INCREMENT - 1
            }
        }
        windowSize = minOf(windowSize + 1, WINDOW_MAXIMUM * WINDOW_MULTIPLY)
    }

    /** Bytes sent since the previous call, or null when not registered (bonding statistics). */
    fun getDataSentDelta(): Long? {
        val delta = totalDataSentByteCount
        totalDataSentByteCount = 0
        return if (state == State.REGISTERED) delta else null
    }

    internal val windowSizeForTesting: Int
        get() = windowSize

    internal val packetsInFlightForTesting: Int
        get() = packetsInFlight.size

    private fun cancelAllTimers() {
        keepaliveTimer?.cancel()
        keepaliveTimer = null
        connectTimer?.cancel()
        connectTimer = null
    }

    private fun handleReady() {
        cancelAllTimers()
        connectTimer = executor.schedule(5 * NANOS_PER_SECOND) { reconnect("Connection timeout") }
        latestReceivedTime = clock.nanoTime()
        packetsInFlight.clear()
        totalDataSentByteCount = 0
        windowSize = WINDOW_DEFAULT * WINDOW_MULTIPLY
        if (type == null) {
            state = State.REGISTERED
            connectTimer?.cancel()
        } else if (state == State.SHOULD_SEND_REGISTER_REQUEST || hasFullGroupId) {
            sendSrtlaReg2()
            state = State.WAIT_FOR_REGISTER_RESPONSE
        } else {
            state = State.SHOULD_SEND_REGISTER_REQUEST
        }
        listener?.onSocketConnected(this)
    }

    private fun reconnect(reason: String) {
        if (relayId != null) {
            listener?.onMoblinkReconnect(this)
        } else {
            stop(reason)
            startInternal()
        }
    }

    private fun sendPacket(packet: ByteArray) {
        if (isSrtDataPacket(packet)) {
            packetsInFlight.add(getSrtSequenceNumber(packet))
            var numberOfMpegTsPackets = (packet.size - 16) / MpegTsPacket.SIZE
            if (packetPadding && numberOfMpegTsPackets < mpegtsPacketsPerPacket) {
                var padded = packet
                while (numberOfMpegTsPackets < mpegtsPacketsPerPacket) {
                    padded += NULL_PACKET
                    numberOfMpegTsPackets += 1
                }
                sendDataPacketInternal(padded)
                totalDataSentByteCount += padded.size
            } else {
                sendDataPacketInternal(packet)
                totalDataSentByteCount += packet.size
            }
        } else {
            link?.send(packet)
        }
    }

    private fun sendDataPacketInternal(packet: ByteArray) {
        dataPacketsToSend.add(packet)
        if (dataPacketsToSend.size > 15) {
            sendDataPackets()
        }
    }

    private fun sendDataPackets() {
        val link = this.link
        for (packet in dataPacketsToSend) {
            link?.send(packet)
        }
        dataPacketsToSend.clear()
    }

    private fun sendSrtlaReg2() {
        val packet = createSrtlaPacket(SrtlaPacketType.REG2, SRT_CONTROL_TYPE_SIZE + groupId.size)
        groupId.copyInto(packet, SRT_CONTROL_TYPE_SIZE)
        sendPacket(packet)
    }

    private fun keepAliveTime(): Long = (clock.nanoTime() - keepAliveSendBaseTime) / NANOS_PER_MILLI

    private fun sendSrtlaKeepalive() {
        val packet = createSrtlaPacket(SrtlaPacketType.KEEPALIVE, SRT_CONTROL_TYPE_SIZE + 8)
        val time = keepAliveTime()
        for (i in 0 until 8) {
            packet[SRT_CONTROL_TYPE_SIZE + i] = (time ushr (56 - 8 * i)).toByte()
        }
        sendPacket(packet)
    }

    private fun handleSrtlaKeepalive(packet: ByteArray) {
        if (packet.size < 10) {
            return
        }
        var sendTime = 0L
        for (i in 0 until 8) {
            sendTime = (sendTime shl 8) or (packet[SRT_CONTROL_TYPE_SIZE + i].toLong() and 0xFF)
        }
        rtt = (keepAliveTime() - sendTime).coerceIn(0, 10_000).toInt()
    }

    private fun handleSrtlaAck(packet: ByteArray) {
        if (packet.size % 4 != 0) {
            return
        }
        for (offset in 4 until packet.size step 4) {
            listener?.onSrtlaAck(packet.getUInt32Be(offset))
        }
    }

    private fun handleSrtlaReg2(packet: ByteArray) {
        if (packet.size != 258 || groupId.size != 256) {
            return
        }
        for (i in 0 until 128) {
            if (packet[SRT_CONTROL_TYPE_SIZE + i] != groupId[i]) {
                return // Wrong group id in reg 2.
            }
        }
        listener?.onReg2(packet.copyOfRange(SRT_CONTROL_TYPE_SIZE, packet.size))
    }

    private fun handleSrtlaReg3() {
        if (state != State.WAIT_FOR_REGISTER_RESPONSE) {
            return
        }
        state = State.REGISTERED
        listener?.onRegistered()
        connectTimer?.cancel()
        keepaliveTimer = executor.scheduleRepeating(NANOS_PER_SECOND) {
            val now = clock.nanoTime()
            sendSrtlaKeepalive()
            if (latestReceivedTime < now - 5 * NANOS_PER_SECOND) {
                reconnect("No packet received in 5 seconds")
            }
        }
    }

    private fun handleControlPacket(packet: ByteArray) {
        when (val type = getSrtControlPacketType(packet)) {
            SrtlaPacketType.KEEPALIVE -> handleSrtlaKeepalive(packet)
            SrtlaPacketType.ACK -> handleSrtlaAck(packet)
            SrtlaPacketType.REG1 -> Unit
            SrtlaPacketType.REG2 -> handleSrtlaReg2(packet)
            SrtlaPacketType.REG3 -> handleSrtlaReg3()
            SrtlaPacketType.REG_ERR, SrtlaPacketType.REG_NAK -> Unit
            SrtlaPacketType.REG_NGP -> listener?.onRegNgp(this)
            else -> {
                if (packet.size >= 16) {
                    when (type) {
                        SrtPacketType.ACK -> if (packet.size >= 20) listener?.onSrtAck(packet.getUInt32Be(16))
                        SrtPacketType.NAK -> processSrtNak(packet) { listener?.onSrtNak(it) }
                    }
                }
                listener?.onPacket(packet)
            }
        }
    }

    private fun handlePacketFromServer(packet: ByteArray) {
        if (packet.size < SRT_CONTROL_TYPE_SIZE) {
            return
        }
        latestReceivedTime = clock.nanoTime()
        if (isSrtDataPacket(packet)) {
            listener?.onPacket(packet)
        } else {
            handleControlPacket(packet)
        }
    }
}
