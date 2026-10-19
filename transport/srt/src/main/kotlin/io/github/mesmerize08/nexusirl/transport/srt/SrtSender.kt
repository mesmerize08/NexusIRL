// Port of Moblin/Media/HaishinKit/Srt/SrtSender.swift @97e26d2
package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.Cancellable
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.ByteReader
import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import io.github.mesmerize08.nexusirl.media.mux.MuxException
import kotlin.random.Random

private const val DATA_PACKET_HEADER_SIZE = 16
private const val HANDSHAKE_VERSION_4 = 4L
private const val HANDSHAKE_VERSION_5 = 5L
private const val DESTINATION_SOCKET = 0L
private const val MAXIMUM_TRANSMISSION_UNIT_SIZE = 1500L
private const val MAXIMUM_FLOW_WINDOW_SIZE_IN_PACKETS = 8192L
private const val IP_UDP_HEADER_SIZE = 28L
private const val HANDSHAKE_TYPE_CONCLUSION = 0xFFFF_FFFFL
private const val HANDSHAKE_TYPE_INDUCTION = 0x0000_0001L
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_MICRO = 1_000L

/** One random SRT socket id per process, like upstream's global `srtSocketId`. */
private val SRT_SOCKET_ID: Long = Random.nextLong(1, U32_MASK + 1)

private val PEER_IP_LOCALHOST = byteArrayOf(1, 0, 0, 127, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

private fun createCommonControlPacketHeader(
    type: Int,
    typeSpecificInformation: Long,
    timestamp: Long,
    destinationSocketId: Long,
): ByteArray {
    val writer = ByteWriter(16)
    writer.writeUInt16(SRT_CONTROL_PACKET_TYPE_BIT or type)
    writer.writeUInt16(0)
    writer.writeUInt32(typeSpecificInformation)
    writer.writeUInt32(timestamp)
    writer.writeUInt32(destinationSocketId)
    return writer.toByteArray()
}

/** An SRT data packet: 16-byte header followed by up to `payloadSize` bytes of MPEG-TS. */
class SrtDataPacket internal constructor(payload: ByteArray, offset: Int, length: Int) {
    internal val data = ByteArray(DATA_PACKET_HEADER_SIZE + length)
    internal var sequenceNumber = 0L
    internal var createdAt = 0L
    internal var retransmittedAt: Long? = null
    var containsAudio = false

    init {
        payload.copyInto(data, DATA_PACKET_HEADER_SIZE, offset, offset + length)
    }

    internal fun setHeader(sequenceNumber: Long, now: Long, timestamp: Long, destinationSrtSocketId: Long) {
        this.sequenceNumber = sequenceNumber
        createdAt = now
        data.setUInt32Be(sequenceNumber, 0)
        data.setUInt32Be(0xE000_0001L, 4)
        data.setUInt32Be(timestamp, 8)
        data.setUInt32Be(destinationSrtSocketId, 12)
    }

    internal fun setRetransmissionBit() {
        data[4] = (data[4].toInt() or 0x4).toByte()
    }
}

/**
 * Moblin's own SRT caller (the default "Moblin" SRT implementation), tuned for IRL streaming:
 * paced output, audio-first NAK-driven retransmission and latency-based drops. It produces raw
 * SRT packets for a transport (plain UDP or SRTLA bonding) and consumes control packets from it.
 *
 * Threading: confined to [executor] (upstream's `srtlaClientQueue`). [start] and [stop] may be
 * called from any thread; every other method must be called on [executor].
 */
class SrtSender(
    private val streamId: String?,
    private val latency: Int,
    private val experimental: Boolean,
    private val executor: SerialExecutor,
    private val hostClock: HostClock,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()

        fun onDisconnected()

        fun onOutput(packet: ByteArray)
    }

    private enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    private var nextSequenceNumber = Random.nextLong(0, 10_000)
    private var peerDestinationSrtSocketId = 0L
    private val packetsToSend = ArrayDeque<SrtDataPacket>()
    private val packetsInFlight = ArrayDeque<SrtDataPacket>()
    private val packetsInFlightBySequenceNumber = HashMap<Long, SrtDataPacket>()
    private val audioSequenceNumbersToRetransmit = LinkedHashSet<Long>()
    private val videoSequenceNumbersToRetransmit = LinkedHashSet<Long>()
    private var state = State.CONNECTING

    @Volatile
    private var performanceData = SrtPerformanceData()
    private var numberOfBytesSent = 0L
    private var latestNumberOfBytesSentTime = hostClock.nanoTime()
    private var pktRetransTotal = 0
    private var pktRecvNakTotal = 0
    private var pktSndDropTotal = 0
    private var rttUs = 0L
    private var mbpsSendRate = 0.0
    private var latestOutputPacketsTime = hostClock.nanoTime()
    private val ackAckPacket = createCommonControlPacketHeader(SrtPacketType.ACK_ACK, 0, 0, 0) + ByteArray(4)
    private val keepAlivePacket = createCommonControlPacketHeader(SrtPacketType.KEEP_ALIVE, 0, 0, 0)
    private var latestReceivedPacketTime = hostClock.nanoTime()
    private val packetsInFlightDropThreshold: Long
    private val packetsToSendDropThreshold: Long
    private var clockStart = hostClock.nanoTime()
    private var connectTimer: Cancellable? = null

    init {
        val latencyNanos = latency * NANOS_PER_MILLI
        if (experimental) {
            packetsInFlightDropThreshold = latencyNanos * 2 / 3
            packetsToSendDropThreshold = latencyNanos * 4 / 5
        } else {
            packetsInFlightDropThreshold = latencyNanos * 3 / 2
            packetsToSendDropThreshold = latencyNanos * 5 / 4
        }
    }

    /** SRT timestamp: microseconds since start, truncated to 32 bits. */
    private fun timestamp(now: Long = hostClock.nanoTime()): Long = ((now - clockStart) / NANOS_PER_MICRO) and U32_MASK

    fun start() {
        executor.execute {
            clockStart = hostClock.nanoTime()
            latestReceivedPacketTime = hostClock.nanoTime()
            state = State.CONNECTING
            outputPacket(createInductionHandshakePacket())
            connectTimer = executor.schedule(5_000 * NANOS_PER_MILLI) { setDisconnected() }
        }
    }

    fun stop() {
        executor.execute { setDisconnected() }
    }

    fun newDataPacket(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): SrtDataPacket =
        SrtDataPacket(payload, offset, length)

    fun enqueue(packet: SrtDataPacket, now: Long) {
        if (state != State.CONNECTED) {
            return
        }
        if (!experimental) {
            packet.containsAudio = true
        }
        packet.setHeader(getNextSequenceNumber(), now, timestamp(now), peerDestinationSrtSocketId)
        packetsToSend.addLast(packet)
    }

    fun send(now: Long) {
        if (state != State.CONNECTED) {
            return
        }
        outputPackets(now)
        dropOldPackets(now)
        checkIfConnected(now)
    }

    fun input(packet: ByteArray) {
        latestReceivedPacketTime = hostClock.nanoTime()
        try {
            handleControlPacket(packet, latestReceivedPacketTime)
        } catch (e: MuxException) {
            // Upstream logs "srt-sender: Input error" and carries on.
        } catch (e: SrtException) {
            // Shutdown/ACKACK/unsupported packets are only logged upstream; the 5 s receive timeout disconnects.
        }
    }

    fun getPerformanceData(): SrtPerformanceData = performanceData

    val isConnected: Boolean
        get() = state == State.CONNECTED

    private fun dropOldPackets(now: Long) {
        while (true) {
            val packet = packetsInFlight.firstOrNull() ?: break
            if (now - packet.createdAt <= packetsInFlightDropThreshold) {
                break
            }
            packetsInFlight.removeFirst()
            packetsInFlightBySequenceNumber.remove(packet.sequenceNumber)
            pktSndDropTotal += 1
        }
        while (true) {
            val packet = packetsToSend.firstOrNull() ?: break
            if (now - packet.createdAt <= packetsToSendDropThreshold) {
                break
            }
            packetsToSend.removeFirst()
            pktSndDropTotal += 1
        }
    }

    private fun setDisconnected() {
        if (state == State.DISCONNECTED) {
            return
        }
        connectTimer?.cancel()
        state = State.DISCONNECTED
        listener.onDisconnected()
    }

    private fun checkIfConnected(now: Long) {
        if (now - latestReceivedPacketTime > 5_000 * NANOS_PER_MILLI) {
            setDisconnected()
        }
    }

    private fun outputPackets(now: Long) {
        if (now - latestOutputPacketsTime <= 2 * NANOS_PER_MILLI) {
            return
        }
        latestOutputPacketsTime = now
        var numberOfPacketsToSend = numberOfPacketsToRetransmit() + packetsToSend.size
        numberOfPacketsToSend = maxOf(numberOfPacketsToSend / 10, minOf(numberOfPacketsToSend, 10))
        var numberOfRetransmittedPackets = 0
        for (i in 0 until numberOfPacketsToSend) {
            if (!experimental || numberOfRetransmittedPackets < (numberOfPacketsToSend + 1) / 2) {
                if (retransmitPacketIfNeeded(audioSequenceNumbersToRetransmit, now)) {
                    numberOfRetransmittedPackets += 1
                    continue
                }
                if (retransmitPacketIfNeeded(videoSequenceNumbersToRetransmit, now)) {
                    numberOfRetransmittedPackets += 1
                    continue
                }
            }
            val packet = packetsToSend.removeFirstOrNull() ?: break
            sendPacket(packet)
        }
        updatePerformanceData()
    }

    private fun retransmitPacketIfNeeded(sequenceNumbers: LinkedHashSet<Long>, now: Long): Boolean {
        val iterator = sequenceNumbers.iterator()
        while (iterator.hasNext()) {
            val sequenceNumber = iterator.next()
            iterator.remove()
            val packet = packetsInFlightBySequenceNumber[sequenceNumber] ?: continue
            val retransmittedAt = packet.retransmittedAt
            if (retransmittedAt != null && now - retransmittedAt < rttUs * NANOS_PER_MICRO) {
                continue
            }
            packet.retransmittedAt = now
            packet.setRetransmissionBit()
            pktRetransTotal += 1
            outputPacket(packet.data)
            return true
        }
        return false
    }

    private fun sendPacket(packet: SrtDataPacket) {
        outputPacket(packet.data)
        packetsInFlight.addLast(packet)
        packetsInFlightBySequenceNumber[packet.sequenceNumber] = packet
    }

    private fun outputPacket(packet: ByteArray) {
        listener.onOutput(packet)
        numberOfBytesSent += packet.size + IP_UDP_HEADER_SIZE
    }

    private fun getNextSequenceNumber(): Long {
        val sequenceNumber = nextSequenceNumber
        nextSequenceNumber = (nextSequenceNumber + 1) and U32_MASK
        return sequenceNumber
    }

    private fun createInductionHandshakePacket(): ByteArray {
        val writer = ByteWriter(64)
        writer.writeBytes(createCommonControlPacketHeader(SrtPacketType.HANDSHAKE, 0, timestamp(), DESTINATION_SOCKET))
        writer.writeUInt32(HANDSHAKE_VERSION_4)
        writer.writeUInt16(0)
        writer.writeUInt16(2)
        writer.writeUInt32(nextSequenceNumber)
        writer.writeUInt32(MAXIMUM_TRANSMISSION_UNIT_SIZE)
        writer.writeUInt32(MAXIMUM_FLOW_WINDOW_SIZE_IN_PACKETS)
        writer.writeUInt32(HANDSHAKE_TYPE_INDUCTION)
        writer.writeUInt32(SRT_SOCKET_ID)
        writer.writeUInt32(0)
        writer.writeBytes(PEER_IP_LOCALHOST)
        return writer.toByteArray()
    }

    private fun createConclusionHandshakePacket(peerSocketId: Long, synCookie: Long): ByteArray {
        val writer = ByteWriter(128)
        writer.writeBytes(createCommonControlPacketHeader(SrtPacketType.HANDSHAKE, 0, timestamp(), DESTINATION_SOCKET))
        writer.writeUInt32(HANDSHAKE_VERSION_5)
        writer.writeUInt16(0)
        writer.writeUInt16(5)
        writer.writeUInt32(nextSequenceNumber)
        writer.writeUInt32(MAXIMUM_TRANSMISSION_UNIT_SIZE)
        writer.writeUInt32(MAXIMUM_FLOW_WINDOW_SIZE_IN_PACKETS)
        writer.writeUInt32(HANDSHAKE_TYPE_CONCLUSION)
        writer.writeUInt32(peerSocketId)
        writer.writeUInt32(synCookie)
        writer.writeBytes(PEER_IP_LOCALHOST)
        // HSREQ extension: SRT version 1.5.3, flags 0xBF, sender/receiver TSBPD delay.
        writer.writeUInt16(1)
        writer.writeUInt16(3)
        writer.writeUInt32(0x0001_0503)
        writer.writeUInt32(0xBF)
        writer.writeUInt16(latency)
        writer.writeUInt16(latency)
        if (streamId != null) {
            val encoded = encodeStreamId(streamId)
            writer.writeUInt16(5)
            writer.writeUInt16(encoded.size / 4)
            writer.writeBytes(encoded)
        }
        return writer.toByteArray()
    }

    private fun handleControlPacket(packet: ByteArray, now: Long) {
        val reader = ByteReader(packet)
        val controlType = reader.readUInt16() and 0x7FFF
        reader.readUInt16()
        val typeSpecificInformation = reader.readUInt32()
        reader.readUInt32() // timestamp
        reader.readUInt32() // destination socket id
        when (controlType) {
            SrtPacketType.HANDSHAKE -> handleHandshakePacket(reader)
            SrtPacketType.KEEP_ALIVE -> handleKeepAlivePacket()
            SrtPacketType.ACK -> handleAckPacket(typeSpecificInformation, reader, now)
            SrtPacketType.NAK -> handleNakPacket(reader)
            SrtPacketType.SHUTDOWN -> throw SrtException("Got shutdown packet")
            SrtPacketType.ACK_ACK -> throw SrtException("Got ack ack packet")
            else -> throw SrtException("Unsupported packet type $controlType")
        }
        outputPackets(now)
    }

    private fun handleHandshakePacket(reader: ByteReader) {
        reader.readUInt32()
        reader.readUInt16()
        reader.readUInt16()
        reader.readUInt32()
        reader.readUInt32()
        reader.readUInt32()
        val handshakeType = reader.readUInt32()
        val peerSocketId = reader.readUInt32()
        val synCookie = reader.readUInt32()
        reader.readBytes(16)
        when (handshakeType) {
            HANDSHAKE_TYPE_INDUCTION -> outputPacket(createConclusionHandshakePacket(peerSocketId, synCookie))
            HANDSHAKE_TYPE_CONCLUSION -> handleHandshakeConclusion(peerSocketId)
            else -> throw SrtException("Unsupported handshake type")
        }
    }

    private fun handleHandshakeConclusion(peerSocketId: Long) {
        peerDestinationSrtSocketId = peerSocketId
        ackAckPacket.setUInt32Be(peerSocketId, 12)
        keepAlivePacket.setUInt32Be(peerSocketId, 12)
        connectTimer?.cancel()
        state = State.CONNECTED
        listener.onConnected()
    }

    private fun handleKeepAlivePacket() {
        keepAlivePacket.setUInt32Be(timestamp(), 8)
        outputPacket(keepAlivePacket.copyOf())
    }

    private fun handleAckPacket(typeSpecificInformation: Long, reader: ByteReader, now: Long) {
        val lastAcknowledgedPacketSequenceNumber = reader.readUInt32()
        removeAckedPackets(lastAcknowledgedPacketSequenceNumber)
        if (typeSpecificInformation != 0L) {
            rttUs = reader.readUInt32()
            updateSendRate(now)
            updatePerformanceData()
            ackAckPacket.setUInt32Be(typeSpecificInformation, 4)
            ackAckPacket.setUInt32Be(timestamp(), 8)
            outputPacket(ackAckPacket.copyOf())
        }
    }

    private fun updatePerformanceData() {
        performanceData = SrtPerformanceData(
            pktRetransTotal = pktRetransTotal,
            pktRecvNakTotal = pktRecvNakTotal,
            pktSndDropTotal = pktSndDropTotal,
            pktFlightSize = packetsInFlight.size,
            msRtt = rttUs / 1000.0,
            mbpsSendRate = mbpsSendRate,
        )
    }

    private fun updateSendRate(now: Long) {
        val duration = now - latestNumberOfBytesSentTime
        if (duration <= 200 * NANOS_PER_MILLI) {
            return
        }
        latestNumberOfBytesSentTime = now
        val latestMbpsSendRate = (8.0 * numberOfBytesSent) / (duration / 1e9) / 1_000_000
        numberOfBytesSent = 0
        mbpsSendRate = 0.7 * mbpsSendRate + 0.3 * latestMbpsSendRate
    }

    private fun removeAckedPackets(lastAcknowledgedPacketSequenceNumber: Long) {
        val firstUnacked = packetsInFlight.indexOfFirst { !isSrtSnAcked(it.sequenceNumber, lastAcknowledgedPacketSequenceNumber) }
        if (firstUnacked >= 0) {
            repeat(firstUnacked) {
                packetsInFlightBySequenceNumber.remove(packetsInFlight.removeFirst().sequenceNumber)
            }
        } else {
            packetsInFlightBySequenceNumber.clear()
            packetsInFlight.clear()
        }
    }

    private fun handleNakPacket(reader: ByteReader) {
        while (reader.bytesAvailable >= 4) {
            val sequenceNumber = reader.readUInt32()
            if (isSrtSnRange(sequenceNumber)) {
                val firstSequenceNumber = sequenceNumber and 0x7FFF_FFFFL
                val upToNakSequenceNumber = reader.readUInt32() // Throws on truncation, like upstream.
                if (((upToNakSequenceNumber - firstSequenceNumber) and U32_MASK) >= MAXIMUM_FLOW_WINDOW_SIZE_IN_PACKETS) {
                    continue
                }
                for (sn in firstSequenceNumber..upToNakSequenceNumber) {
                    if (numberOfPacketsToRetransmit() >= 1000) {
                        return
                    }
                    appendSequenceNumberToRetransmit(sn)
                }
            } else {
                if (numberOfPacketsToRetransmit() >= 1000) {
                    return
                }
                appendSequenceNumberToRetransmit(sequenceNumber)
            }
        }
        pktRecvNakTotal += 1
    }

    private fun appendSequenceNumberToRetransmit(sequenceNumber: Long) {
        val packet = packetsInFlightBySequenceNumber[sequenceNumber] ?: return
        if (packet.containsAudio) {
            audioSequenceNumbersToRetransmit.add(sequenceNumber)
        } else {
            videoSequenceNumbersToRetransmit.add(sequenceNumber)
        }
    }

    private fun numberOfPacketsToRetransmit(): Int =
        audioSequenceNumbersToRetransmit.size + videoSequenceNumbersToRetransmit.size

    companion object {
        /**
         * Encodes an SRT stream id: UTF-8, zero padded to a multiple of 4, each 4-byte word
         * byte-reversed (SRT's little-endian word quirk).
         */
        fun encodeStreamId(streamId: String): ByteArray {
            var bytes = streamId.toByteArray(Charsets.UTF_8)
            val paddingLength = 4 - (bytes.size % 4)
            if (paddingLength < 4) {
                bytes += ByteArray(paddingLength)
            }
            for (offset in bytes.indices step 4) {
                bytes[offset] = bytes[offset + 3].also { bytes[offset + 3] = bytes[offset] }
                bytes[offset + 1] = bytes[offset + 2].also { bytes[offset + 2] = bytes[offset + 1] }
            }
            return bytes
        }
    }
}

class SrtException(message: String) : Exception(message)
