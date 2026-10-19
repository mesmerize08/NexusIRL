// Port of Moblin/Media/Srtla/Common/Srt.swift and Srtla.swift @97e26d2
package io.github.mesmerize08.nexusirl.transport.srt

/** SRT sequence numbers and IDs are unsigned 32-bit; they are held in a `Long` in 0..0xFFFFFFFF. */
internal const val U32_MASK = 0xFFFF_FFFFL

const val SRT_CONTROL_PACKET_TYPE_BIT = 0x8000

object SrtPacketType {
    const val HANDSHAKE = 0x0000
    const val KEEP_ALIVE = 0x0001
    const val ACK = 0x0002
    const val NAK = 0x0003
    const val CONGESTION_WARNING = 0x0004
    const val SHUTDOWN = 0x0005
    const val ACK_ACK = 0x0006
    const val DROP_REQ = 0x0007
    const val PEER_ERROR = 0x0008
}

/** SRTLA is a bonding protocol on top of SRT, designed by rationalsa for the BELABOX project. */
object SrtlaPacketType {
    const val KEEPALIVE = 0x1000
    const val ACK = 0x1100
    const val REG1 = 0x1200
    const val REG2 = 0x1201
    const val REG3 = 0x1202
    const val REG_ERR = 0x1210
    const val REG_NGP = 0x1211
    const val REG_NAK = 0x1212
}

fun ByteArray.getUInt16Be(offset: Int = 0): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

fun ByteArray.getUInt32Be(offset: Int = 0): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)

fun ByteArray.setUInt16Be(value: Int, offset: Int = 0) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

fun ByteArray.setUInt32Be(value: Long, offset: Int = 0) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

fun isSrtDataPacket(packet: ByteArray): Boolean = (packet[0].toInt() and 0x80) == 0

fun getSrtControlPacketType(packet: ByteArray): Int = packet.getUInt16Be() and 0x7FFF

fun getSrtSequenceNumber(packet: ByteArray): Long = packet.getUInt32Be()

/** True if [sn] is at or before [ackSn] (ACK numbers are "next expected"), with wrap tolerance. */
fun isSrtSnAcked(sn: Long, ackSn: Long): Boolean =
    if (sn < ackSn) ackSn - sn < 100_000_000 else sn - ackSn > 100_000_000

fun isSrtSnRange(sn: Long): Boolean = (sn and 0x8000_0000L) == 0x8000_0000L

/** Calls [onNak] for every lost sequence number in an SRT NAK control packet. */
fun processSrtNak(packet: ByteArray, onNak: (Long) -> Unit) {
    var offset = 16
    while (offset <= packet.size - 4) {
        val nakSn = packet.getUInt32Be(offset)
        offset += 4
        if (isSrtSnRange(nakSn)) {
            if (offset > packet.size - 4) {
                return
            }
            val upToNakSn = packet.getUInt32Be(offset)
            offset += 4
            val fromNakSn = nakSn and 0x7FFF_FFFFL
            if (((upToNakSn - fromNakSn) and U32_MASK) >= 100_000) {
                continue
            }
            for (sn in fromNakSn..upToNakSn) {
                onNak(sn)
            }
        } else {
            onNak(nakSn)
        }
    }
}

fun createSrtlaPacket(type: Int, length: Int): ByteArray {
    val packet = ByteArray(length)
    packet.setUInt16Be(type or SRT_CONTROL_PACKET_TYPE_BIT)
    return packet
}

/** Sender statistics, consumed by adaptive bitrate (upstream `SrtPerformanceData`). */
data class SrtPerformanceData(
    val pktRetransTotal: Int = 0,
    val pktRecvNakTotal: Int = 0,
    val pktSndDropTotal: Int = 0,
    val pktFlightSize: Int = 0,
    val msRtt: Double = 0.0,
    val pktSndBuf: Int = 0,
    val mbpsSendRate: Double = 0.0,
)
