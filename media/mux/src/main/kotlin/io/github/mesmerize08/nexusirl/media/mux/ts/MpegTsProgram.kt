// Port of Moblin/Media/HaishinKit/Mpeg/MpegTsProgramSpecificInformation.swift and
// ElementaryStreamSpecificData.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux.ts

import io.github.mesmerize08.nexusirl.media.mux.ByteReader
import io.github.mesmerize08.nexusirl.media.mux.ByteWriter
import io.github.mesmerize08.nexusirl.media.mux.Crc32

object ElementaryStreamType {
    const val UNSPECIFIC = 0x00
    const val MPEG2_PACKETIZED_DATA = 0x06
    const val ADTS_AAC = 0x0F
    const val H264 = 0x1B
    const val H265 = 0x24
}

object ElementaryStreamDescriptorTag {
    const val REGISTRATION = 0x05
    const val EXTENSION = 0x7F
}

val OPUS_REGISTRATION: ByteArray = "Opus".toByteArray(Charsets.US_ASCII)

class ElementaryStreamSpecificData(var streamType: Int = ElementaryStreamType.UNSPECIFIC, var elementaryPacketId: Int = 0) {
    private var esDescriptors = ByteArray(0)

    fun appendDescriptor(tag: Int, data: ByteArray) {
        esDescriptors += byteArrayOf(tag.toByte(), data.size.toByte()) + data
    }

    fun getDescriptor(tag: Int): ByteArray? {
        val reader = ByteReader(esDescriptors)
        try {
            while (true) {
                val rawTag = reader.readUInt8()
                val length = reader.readUInt8()
                if (rawTag == tag) {
                    return reader.readBytes(length)
                }
                reader.skipBytes(length)
            }
        } catch (e: Exception) {
            return null
        }
    }

    fun encode(): ByteArray {
        val writer = ByteWriter()
        writer.writeUInt8(streamType)
        writer.writeUInt16(elementaryPacketId or 0xE000)
        writer.writeUInt16(esDescriptors.size or 0xF000)
        writer.writeBytes(esDescriptors)
        return writer.toByteArray()
    }

    companion object {
        fun decode(reader: ByteReader): ElementaryStreamSpecificData {
            val data = ElementaryStreamSpecificData()
            val type = reader.readUInt8()
            data.streamType = if (type in KNOWN_TYPES) type else ElementaryStreamType.UNSPECIFIC
            data.elementaryPacketId = reader.readUInt16() and 0x0FFF
            val esInfoLength = reader.readUInt16() and 0x01FF
            data.esDescriptors = reader.readBytes(esInfoLength)
            return data
        }

        // Upstream's ElementaryStreamType enum cases; unknown values decode as unspecific.
        private val KNOWN_TYPES = setOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x0F, 0x10, 0x1B, 0x24)
    }
}

/** Program-specific information section (PAT/PMT), with the upstream encoding layout. */
abstract class MpegTsProgramSpecificInformation(var tableId: Int) {
    private val pointerField = 0
    private val tableIdExtension = 1
    private val versionNumber = 0
    private val currentNextIndicator = true
    private val sectionNumber = 0
    private val lastSectionNumber = 0

    protected abstract fun encodeSectionData(): ByteArray

    fun packet(packetId: Int): MpegTsPacket {
        val packet = MpegTsPacket(packetId)
        packet.payloadUnitStartIndicator = true
        val encoded = encode()
        val payload = ByteArray(184) { 0xFF.toByte() }
        encoded.copyInto(payload)
        packet.setPayload(payload)
        return packet
    }

    private fun encode(): ByteArray {
        val sectionData = encodeSectionData()
        val sectionLength = sectionData.size + 9
        val sectionSyntaxIndicator = sectionData.isNotEmpty()
        val writer = ByteWriter()
        writer.writeUInt8(tableId)
        var value = 0
        value = value or (if (sectionSyntaxIndicator) 0x8000 else 0)
        value = value or (RESERVED_BITS shl 12)
        value = value or sectionLength
        writer.writeUInt16(value)
        writer.writeUInt16(tableIdExtension)
        writer.writeUInt8((RESERVED_BITS shl 6) or (versionNumber shl 1) or (if (currentNextIndicator) 1 else 0))
        writer.writeUInt8(sectionNumber)
        writer.writeUInt8(lastSectionNumber)
        writer.writeBytes(sectionData)
        val section = writer.toByteArray()
        val crc = Crc32.mpeg2.calculate(section)
        val result = ByteWriter()
        result.writeUInt8(pointerField)
        result.writeBytes(section)
        result.writeUInt32(crc)
        return result.toByteArray()
    }

    companion object {
        private const val RESERVED_BITS = 0x03
        const val TABLE_ID_PROGRAM_ASSOCIATION = 0
        const val TABLE_ID_PROGRAM_MAPPING = 2
    }
}

class MpegTsProgramAssociation : MpegTsProgramSpecificInformation(TABLE_ID_PROGRAM_ASSOCIATION) {
    /** Program number to PMT packet id. */
    val programs = LinkedHashMap<Int, Int>()

    override fun encodeSectionData(): ByteArray {
        val writer = ByteWriter()
        for ((programNumber, programId) in programs) {
            writer.writeUInt16(programNumber)
            writer.writeUInt16(programId or 0xE000)
        }
        return writer.toByteArray()
    }
}

class MpegTsProgramMapping : MpegTsProgramSpecificInformation(TABLE_ID_PROGRAM_MAPPING) {
    var programClockReferencePacketId = 0
    val elementaryStreamSpecificDatas = ArrayList<ElementaryStreamSpecificData>()

    override fun encodeSectionData(): ByteArray {
        elementaryStreamSpecificDatas.sortBy { it.elementaryPacketId }
        val writer = ByteWriter()
        writer.writeUInt16(programClockReferencePacketId or 0xE000)
        writer.writeUInt16(0xF000)
        for (data in elementaryStreamSpecificDatas) {
            writer.writeBytes(data.encode())
        }
        return writer.toByteArray()
    }
}
