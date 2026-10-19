// Port of Moblin/Media/HaishinKit/Mpeg/MpegTsPacketizedElementaryStream.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux.ts

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ByteReader
import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import io.github.mesmerize08.nexusirl.media.mux.MuxException

/** Converts to 90 kHz ticks the way upstream does: `Int64(seconds * 90000)` (truncating). */
internal fun MediaTime.toTsTicks(): Long = (seconds * TsTimestamp.RESOLUTION).toLong()

/** PES optional header. */
class PesOptionalHeader {
    var markerBits = 2
    var scramblingControl = 0
    var priority = false
    var dataAlignmentIndicator = false
    var copyright = false
    var originalOrCopy = false
    var ptsDtsIndicator = 0
    var pesHeaderLength = 0
    var optionalFields = ByteArray(0)

    fun setTimestamp(presentationTimeStamp: MediaTime?, decodeTimeStamp: MediaTime?) {
        if (presentationTimeStamp != null) {
            ptsDtsIndicator = ptsDtsIndicator or 0x02
        }
        if (decodeTimeStamp != null) {
            ptsDtsIndicator = ptsDtsIndicator or 0x01
        }
        if ((ptsDtsIndicator and 0x02) == 0x02 && presentationTimeStamp != null) {
            optionalFields += TsTimestamp.encode(presentationTimeStamp.toTsTicks(), ptsDtsIndicator shl 4)
        }
        if ((ptsDtsIndicator and 0x01) == 0x01 && decodeTimeStamp != null) {
            optionalFields += TsTimestamp.encode(decodeTimeStamp.toTsTicks(), 0x01 shl 4)
        }
        pesHeaderLength = optionalFields.size
    }

    fun encode(): ByteArray {
        var byte0 = markerBits shl 6
        byte0 = byte0 or (scramblingControl shl 4)
        byte0 = byte0 or (priority.bit() shl 3)
        byte0 = byte0 or (dataAlignmentIndicator.bit() shl 2)
        byte0 = byte0 or (copyright.bit() shl 1)
        byte0 = byte0 or originalOrCopy.bit()
        val byte1 = ptsDtsIndicator shl 6
        val writer = ByteWriter()
        writer.writeUInt8(byte0)
        writer.writeUInt8(byte1)
        writer.writeUInt8(pesHeaderLength)
        writer.writeBytes(optionalFields)
        return writer.toByteArray()
    }

    fun getPresentationTimeStamp(): MediaTime? {
        if ((ptsDtsIndicator and 0x02) != 0x02) {
            return null
        }
        return TsTimestamp.decode(optionalFields, 0)?.let { MediaTime(it, MediaTime.MPEG_TS) }
    }

    fun getDecodeTimeStamp(): MediaTime? {
        if ((ptsDtsIndicator and 0x01) != 0x01) {
            return null
        }
        return TsTimestamp.decode(optionalFields, TsTimestamp.DATA_SIZE)?.let { MediaTime(it, MediaTime.MPEG_TS) }
    }

    companion object {
        const val FIXED_SECTION_SIZE = 3

        private fun Boolean.bit(): Int = if (this) 1 else 0

        fun decode(data: ByteArray): PesOptionalHeader {
            val reader = ByteReader(data)
            val header = PesOptionalHeader()
            val byte0 = reader.readUInt8()
            val byte1 = reader.readUInt8()
            header.markerBits = (byte0 and 0b1100_0000) ushr 6
            header.scramblingControl = (byte0 and 0b0011_0000) ushr 4
            header.priority = (byte0 and 0b0000_1000) != 0
            header.dataAlignmentIndicator = (byte0 and 0b0000_0100) != 0
            header.copyright = (byte0 and 0b0000_0010) != 0
            header.originalOrCopy = (byte0 and 0b0000_0001) != 0
            header.ptsDtsIndicator = (byte1 and 0b1100_0000) ushr 6
            header.pesHeaderLength = reader.readUInt8()
            header.optionalFields = reader.readBytes(header.pesHeaderLength)
            return header
        }
    }
}

/** A packetized elementary stream packet, split into TS packets by [arrayOfPackets]. */
class MpegTsPacketizedElementaryStream(
    private val streamId: Int,
    presentationTimeStamp: MediaTime?,
    decodeTimeStamp: MediaTime?,
    val data: ByteArray,
) {
    val optionalHeader = PesOptionalHeader()
    private val packetLength: Int

    init {
        optionalHeader.dataAlignmentIndicator = true
        optionalHeader.setTimestamp(presentationTimeStamp, decodeTimeStamp)
        val length = data.size + optionalHeader.encode().size
        packetLength = if (length < 0xFFFF) length else 0
    }

    private fun encode(): ByteArray {
        val writer = ByteWriter(data.size + 32)
        writer.writeBytes(START_CODE)
        writer.writeUInt8(streamId)
        writer.writeUInt16(packetLength)
        writer.writeBytes(optionalHeader.encode())
        writer.writeBytes(data)
        return writer.toByteArray()
    }

    fun arrayOfPackets(packetId: Int, randomAccessIndicator: Boolean, programClockReference: Long?): List<MpegTsPacket> {
        val payload = encode()
        val packets = ArrayList<MpegTsPacket>(payload.size / 184 + 2)
        var payloadOffset: Int
        // First packet: carries the adaptation field (random access, optional PCR).
        run {
            val packet = MpegTsPacket(packetId)
            packet.payloadUnitStartIndicator = true
            val adaptationField = MpegTsAdaptationField()
            adaptationField.randomAccessIndicator = randomAccessIndicator
            if (programClockReference != null) {
                adaptationField.programClockReference = TsProgramClockReference.encode(programClockReference, 0)
            }
            packet.adaptationField = adaptationField
            val maximumPayloadSize = packet.maximumPayloadSize()
            payloadOffset = minOf(maximumPayloadSize, payload.size)
            packet.setPayload(payload, 0, payloadOffset)
            if (payloadOffset < maximumPayloadSize) {
                packet.setAdaptationFieldStuffing(maximumPayloadSize - payloadOffset)
            }
            packets.add(packet)
        }
        // Middle packets: full 184-byte payloads.
        while (payloadOffset <= payload.size - 184) {
            val packet = MpegTsPacket(packetId)
            packet.setPayload(payload, payloadOffset, 184)
            packets.add(packet)
            payloadOffset += 184
        }
        // Last packet(s): pad with adaptation field stuffing.
        when ((payload.size - payloadOffset) % 184) {
            0 -> Unit
            183 -> {
                var packet = MpegTsPacket(packetId)
                packet.adaptationField = MpegTsAdaptationField()
                packet.setPayload(payload, payloadOffset, 182)
                payloadOffset += 182
                packets.add(packet)
                packet = MpegTsPacket(packetId)
                packet.adaptationField = MpegTsAdaptationField()
                packet.setPayload(payload, payloadOffset, payload.size - payloadOffset)
                packet.setAdaptationFieldStuffing(182 - packet.payloadLength)
                packets.add(packet)
            }
            else -> {
                val packet = MpegTsPacket(packetId)
                packet.adaptationField = MpegTsAdaptationField()
                packet.setPayload(payload, payloadOffset, payload.size - payloadOffset)
                packet.setAdaptationFieldStuffing(182 - packet.payloadLength)
                packets.add(packet)
            }
        }
        return packets
    }

    companion object {
        private val START_CODE = byteArrayOf(0x00, 0x00, 0x01)
        private const val UNTIL_PACKET_LENGTH_SIZE = 6

        /** Decodes a complete PES packet (used by tests and, later, the TS reader). */
        fun decode(data: ByteArray): Pair<PesOptionalHeader, ByteArray> {
            val reader = ByteReader(data)
            if (!reader.readBytes(3).contentEquals(START_CODE)) {
                throw MuxException("Bad PES start code")
            }
            reader.readUInt8()
            reader.readUInt16()
            val header = PesOptionalHeader.decode(reader.readBytes(reader.bytesAvailable))
            reader.position = UNTIL_PACKET_LENGTH_SIZE + 3 + header.pesHeaderLength
            return header to reader.readBytes(reader.bytesAvailable)
        }
    }
}
