package io.github.mesmerize08.nexusirl.media.mux

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsPacket
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsPacketizedElementaryStream
import io.github.mesmerize08.nexusirl.media.mux.ts.PesOptionalHeader
import io.github.mesmerize08.nexusirl.media.mux.ts.TsTimestamp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ports of upstream @97e26d2:
 *   MoblinTests/Moblin/Media/HaishinKit/Mpeg/MpegTsSuite.swift
 *   MoblinTests/Moblin/Media/HaishinKit/Mpeg/TSTimestampSuite.swift
 */
class UpstreamMpegTsSuitesTest {
    private fun packets(dataSize: Int, pcr: Long?, data: ByteArray = ByteArray(dataSize) { 1 }) =
        MpegTsPacketizedElementaryStream(0xE0, MediaTime(0, 90_000), null, data).arrayOfPackets(256, true, pcr)

    @Test
    fun firstPacketStuffing() {
        val packets = packets(1, null, byteArrayOf(0x65))
        assertEquals(1, packets.size)
        assertEquals(MpegTsPacket.SIZE, packets[0].encode().size)
    }

    @Test
    fun firstPacketNoStuffing() {
        val packets = packets(168, null)
        assertEquals(1, packets.size)
        assertEquals(MpegTsPacket.SIZE, packets[0].encode().size)
    }

    @Test
    fun twoPackets() {
        val packets = packets(169, null)
        assertEquals(2, packets.size)
        assertEquals(MpegTsPacket.SIZE, packets[0].encode().size)
        assertEquals(MpegTsPacket.SIZE, packets[1].encode().size)
    }

    @Test
    fun firstPacketWithClockReferenceStuffing() {
        val packets = packets(161, 1234)
        assertEquals(1, packets.size)
        assertEquals(MpegTsPacket.SIZE, packets[0].encode().size)
    }

    @Test
    fun firstPacketWithClockReferenceNoStuffing() {
        val packets = packets(162, 1234)
        assertEquals(1, packets.size)
        assertEquals(MpegTsPacket.SIZE, packets[0].encode().size)
    }

    @Test
    fun everyPayloadSizeProducesWholePackets() {
        // Exercises the 0 / 183 / other remainder branches of the last-packet logic.
        for (size in 1..1000) {
            for (pcr in listOf(null, 1234L)) {
                for (packet in packets(size, pcr)) {
                    assertEquals(MpegTsPacket.SIZE, packet.encode().size, "data size $size, pcr $pcr")
                }
            }
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun encodeKnownValue() {
        assertContentEquals(bytes(0x29, 0x8D, 0x15, 0xCF, 0x13), TsTimestamp.encode(0x1_2345_6789L, 0x20))
    }

    @Test
    fun encodeMaximumValue() {
        assertContentEquals(bytes(0x3F, 0xFF, 0xFF, 0xFF, 0xFF), TsTimestamp.encode(0x1_FFFF_FFFFL, 0x30))
    }

    @Test
    fun encodeZero() {
        assertContentEquals(bytes(0x11, 0x00, 0x01, 0x00, 0x01), TsTimestamp.encode(0, 0x10))
    }

    @Test
    fun encodeWrapsAt33Bits() {
        val uptime30Hours = 30L * 3600 * 90000
        val encoded = TsTimestamp.encode(uptime30Hours, 0x20)
        assertEquals(0x20, encoded[0].toInt() and 0xF0)
        assertEquals(uptime30Hours and 0x1_FFFF_FFFFL, TsTimestamp.decode(encoded))
    }

    @Test
    fun encodeNegativeValue() {
        val encoded = TsTimestamp.encode(-1, 0x20)
        assertContentEquals(bytes(0x2F, 0xFF, 0xFF, 0xFF, 0xFF), encoded)
        assertEquals(0x1_FFFF_FFFFL, TsTimestamp.decode(encoded))
    }

    @Test
    fun roundTrip() {
        for (value in listOf(0L, 1L, 90000L, 0x1234_5678L, 0xFFFF_FFFFL, 0x1_0000_0000L, 0x1_FFFF_FFFEL)) {
            assertEquals(value, TsTimestamp.decode(TsTimestamp.encode(value, 0x20)))
        }
    }

    @Test
    fun decodeIgnoresMarkerBits() {
        assertEquals(0L, TsTimestamp.decode(bytes(0xF1, 0x00, 0x01, 0x00, 0x01)))
    }

    @Test
    fun decodeAtOffset() {
        val data = TsTimestamp.encode(1000, 0x30) + TsTimestamp.encode(900, 0x10)
        assertEquals(1000L, TsTimestamp.decode(data, 0))
        assertEquals(900L, TsTimestamp.decode(data, TsTimestamp.DATA_SIZE))
    }

    @Test
    fun decodeTooShort() {
        assertNull(TsTimestamp.decode(ByteArray(0)))
        assertNull(TsTimestamp.decode(bytes(0x21, 0x00, 0x01, 0x00)))
        assertNull(TsTimestamp.decode(bytes(0x21, 0x00, 0x01, 0x00, 0x01), 1))
    }

    @Test
    fun optionalHeaderTruncatedTimestamps() {
        var header = PesOptionalHeader.decode(bytes(0x80, 0xC0, 0x03, 0x31, 0x00, 0x01))
        assertNull(header.getPresentationTimeStamp())
        assertNull(header.getDecodeTimeStamp())
        header = PesOptionalHeader.decode(bytes(0x80, 0xC0, 0x05) + TsTimestamp.encode(1000, 0x30))
        assertEquals(MediaTime(1000, 90000), header.getPresentationTimeStamp())
        assertNull(header.getDecodeTimeStamp())
    }

    @Test
    fun optionalHeaderRoundTrip() {
        val header = PesOptionalHeader()
        header.setTimestamp(MediaTime(1000, 90000), MediaTime(900, 90000))
        val decoded = PesOptionalHeader.decode(header.encode())
        assertEquals(MediaTime(1000, 90000), decoded.getPresentationTimeStamp())
        assertEquals(MediaTime(900, 90000), decoded.getDecodeTimeStamp())
    }
}
