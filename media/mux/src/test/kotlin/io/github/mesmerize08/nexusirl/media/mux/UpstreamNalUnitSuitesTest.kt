package io.github.mesmerize08.nexusirl.media.mux

import io.github.mesmerize08.nexusirl.media.mux.nal.AvcNalUnit
import io.github.mesmerize08.nexusirl.media.mux.nal.AvcNalUnitType
import io.github.mesmerize08.nexusirl.media.mux.nal.HevcNalUnit
import io.github.mesmerize08.nexusirl.media.mux.nal.HevcNalUnitType
import io.github.mesmerize08.nexusirl.media.mux.nal.NalUnitReader
import io.github.mesmerize08.nexusirl.media.mux.nal.NalUnitWriter
import io.github.mesmerize08.nexusirl.media.mux.nal.SeiTimecode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Ports of upstream @97e26d2:
 *   MoblinTests/Moblin/Media/HaishinKit/Mpeg/NalUnitReaderSuite.swift
 *   MoblinTests/Moblin/Media/HaishinKit/Mpeg/NalUnitWriterSuite.swift
 *   MoblinTests/Moblin/Media/HaishinKit/Mpeg/NalUnitSeiSuite.swift
 */
class UpstreamNalUnitSuitesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // NalUnitReaderSuite

    @Test
    fun readBitMsbFirst() {
        val reader = NalUnitReader(bytes(0xA5))
        assertEquals(listOf(true, false, true, false, false, true, false, true), List(8) { reader.readBit() })
    }

    @Test
    fun readBitsHighAndLowNibble() {
        val reader = NalUnitReader(bytes(0xB4))
        assertEquals(0x0B, reader.readBits(4))
        assertEquals(0x04, reader.readBits(4))
    }

    @Test
    fun readBitsFullByte() {
        assertEquals(0xFF, NalUnitReader(bytes(0xFF)).readBits(8))
    }

    @Test
    fun readBitsU32AcrossBytes() {
        assertEquals(0x1234L, NalUnitReader(bytes(0x12, 0x34)).readBitsU32(16))
        assertEquals(0xABCDEFL, NalUnitReader(bytes(0xAB, 0xCD, 0xEF)).readBitsU32(24))
    }

    @Test
    fun skipBitsAdvancesPosition() {
        val reader = NalUnitReader(bytes(0x00, 0xBE))
        reader.skipBits(8)
        assertEquals(0xBE, reader.readBits(8))
    }

    @Test
    fun availableDecrementsAfterReads() {
        val reader = NalUnitReader(bytes(0xAA, 0xBB))
        assertEquals(16, reader.available())
        reader.readBit()
        assertEquals(15, reader.available())
        reader.skipBits(7)
        assertEquals(8, reader.available())
        reader.readBits(8)
        assertEquals(0, reader.available())
    }

    @Test
    fun emulationPreventionByteIsSkipped() {
        assertEquals(0x000080L, NalUnitReader(bytes(0x00, 0x00, 0x03, 0x80)).readBitsU32(24))
    }

    @Test
    fun noFalseEmulationPreventionByteRemoval() {
        assertEquals(0x0100_0380L, NalUnitReader(bytes(0x01, 0x00, 0x03, 0x80)).readBitsU32(32))
    }

    @Test
    fun readRawBytes() {
        val reader = NalUnitReader(bytes(0x12, 0x34, 0x56))
        reader.readBits(8)
        assertContentEquals(bytes(0x34, 0x56), reader.readRawBytes())
        assertContentEquals(bytes(0xDE, 0xAD, 0xBE, 0xEF), NalUnitReader(bytes(0xDE, 0xAD, 0xBE, 0xEF)).readRawBytes())
    }

    @Test
    fun readRawBytesThrowsWhenNotOnByteBoundary() {
        val reader = NalUnitReader(bytes(0xFF, 0x00))
        reader.readBit()
        assertFailsWith<MuxException> { reader.readRawBytes() }
    }

    @Test
    fun readBitThrowsWhenOutOfData() {
        assertFailsWith<MuxException> { NalUnitReader(ByteArray(0)).readBit() }
        val reader = NalUnitReader(bytes(0xFF))
        reader.skipBits(8)
        assertFailsWith<MuxException> { reader.readBit() }
    }

    @Test
    fun offsetSkipsInitialBytes() {
        val reader = NalUnitReader(bytes(0x12, 0x34, 0x56), offset = 1)
        assertEquals(0x34, reader.readBits(8))
        assertEquals(0x56, reader.readBits(8))
    }

    // NalUnitWriterSuite

    @Test
    fun writeBitMsbFirst() {
        val writer = NalUnitWriter()
        for (value in listOf(true, false, true, false, false, true, false, true)) {
            writer.writeBit(value)
        }
        assertContentEquals(bytes(0xA5), writer.data)
    }

    @Test
    fun writeBitsAcrossByteBoundary() {
        val writer = NalUnitWriter()
        writer.writeBits(0x0B, 4)
        writer.writeBits(0x04, 4)
        writer.writeBitsU32(0x1234, 16)
        assertContentEquals(bytes(0xB4, 0x12, 0x34), writer.data)
    }

    @Test
    fun emulationPreventionByteInserted() {
        for (value in listOf(0x00, 0x01, 0x02, 0x03)) {
            val writer = NalUnitWriter()
            writer.writeBytes(bytes(0x00, 0x00, value))
            assertContentEquals(bytes(0x00, 0x00, 0x03, value), writer.data)
        }
    }

    @Test
    fun noEmulationPreventionByteBeforeHighValue() {
        for (value in listOf(0x04, 0x30, 0x80, 0xFF)) {
            val writer = NalUnitWriter()
            writer.writeBytes(bytes(0x00, 0x00, value))
            assertContentEquals(bytes(0x00, 0x00, value), writer.data)
        }
    }

    @Test
    fun emulationPreventionByteRestartsZeroRun() {
        val writer = NalUnitWriter()
        writer.writeBytes(bytes(0x00, 0x00, 0x00, 0x00))
        assertContentEquals(bytes(0x00, 0x00, 0x03, 0x00, 0x00), writer.data)
    }

    @Test
    fun noEmulationPreventionByteWhenDisabled() {
        val writer = NalUnitWriter(emulationPrevention = false)
        writer.writeBytes(bytes(0x00, 0x00, 0x00, 0x01))
        assertContentEquals(bytes(0x00, 0x00, 0x00, 0x01), writer.data)
    }

    @Test
    fun emulationPreventionByteRoundTrip() {
        val writer = NalUnitWriter()
        writer.writeBytes(bytes(0xAB, 0x00, 0x00, 0x01, 0x00, 0x00, 0x30))
        assertContentEquals(bytes(0xAB, 0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x30), writer.data)
        val reader = NalUnitReader(writer.data)
        assertEquals(0xAB, reader.readBits(8))
        assertEquals(0x000001L, reader.readBitsU32(24))
        assertEquals(0x000030L, reader.readBitsU32(24))
    }

    // NalUnitSeiSuite

    private val noon = Instant.ofEpochSecond(1_755_864_000)

    private fun clock(hours: Int, minutes: Int, seconds: Int): Instant {
        val startOfDay = noon.epochSecond - noon.epochSecond % 86400
        return Instant.ofEpochSecond(startOfDay + 3600L * hours + 60L * minutes + seconds)
    }

    private fun timeCode(hours: Int, minutes: Int, seconds: Int, frame: Long) = SeiTimecode.fromClock(clock(hours, minutes, seconds), frame)

    @Test
    fun hevcTimeCodePayloadEncodeAndDecode() {
        val encoded = timeCode(12, 34, 56, 7).encode()
        assertContentEquals(bytes(0x70, 0x40, 0x3F, 0x11, 0x30, 0x10), encoded)
        assertEquals(SeiTimecode(12, 34, 56, 7), SeiTimecode.decode(NalUnitReader(encoded)))
    }

    @Test
    fun hevcTimeCodePayloadRoundTrip() {
        for ((h, m, s, f) in listOf(
            listOf(0, 0, 0, 0),
            listOf(23, 59, 59, 59),
            listOf(12, 34, 56, 7),
            listOf(1, 2, 3, 255),
            listOf(9, 8, 7, 511),
        )) {
            val encoded = timeCode(h, m, s, f.toLong()).encode()
            assertEquals(SeiTimecode(h, m, s, f.toLong()), SeiTimecode.decode(NalUnitReader(encoded)))
        }
    }

    @Test
    fun hevcTimeCodePayloadMakeClock() {
        val (decodedClock, frame) = timeCode(12, 34, 56, 7).makeClock()
        assertEquals((12 * 3600 + 34 * 60 + 56).toLong(), decodedClock.epochSecond % 86400)
        assertEquals(7L, frame)
    }

    @Test
    fun hevcNalUnitRoundTrip() {
        val encoded = HevcNalUnit.PrefixSei(timeCode(12, 34, 56, 7)).encode()
        assertContentEquals(bytes(0x4E, 0x01), encoded.copyOf(2))
        assertEquals(136, encoded[2].toInt() and 0xFF)
        assertEquals(6, encoded[3].toInt())
        val decoded = assertIs<HevcNalUnit.PrefixSei>(HevcNalUnit.decode(encoded))
        assertEquals(HevcNalUnitType.PREFIX_SEI_NUT, decoded.type)
        assertEquals(1, decoded.temporalIdPlusOne)
        assertEquals(SeiTimecode(12, 34, 56, 7), decoded.timeCode)
    }

    @Test
    fun hevcNalUnitEmulationPreventionByteInserted() {
        assertContentEquals(
            bytes(0x4E, 0x01, 0x88, 0x06, 0x70, 0x40, 0x00, 0x00, 0x03, 0x00, 0x10, 0x80),
            HevcNalUnit.PrefixSei(timeCode(0, 0, 0, 0)).encode(),
        )
    }

    @Test
    fun hevcNalUnitNoEmulationPreventionByteOnTheHour() {
        assertContentEquals(
            bytes(0x4E, 0x01, 0x88, 0x06, 0x70, 0x40, 0x00, 0x00, 0x30, 0x10, 0x80),
            HevcNalUnit.PrefixSei(timeCode(12, 0, 0, 0)).encode(),
        )
    }

    @Test
    fun hevcNalUnitRoundTripWithEmulationPreventionBytes() {
        for ((h, m, s, f) in listOf(listOf(0, 0, 0, 0), listOf(0, 1, 0, 0), listOf(0, 2, 8, 32), listOf(23, 59, 59, 59))) {
            val encoded = HevcNalUnit.PrefixSei(timeCode(h, m, s, f.toLong())).encode()
            val decoded = assertIs<HevcNalUnit.PrefixSei>(HevcNalUnit.decode(encoded))
            assertEquals(SeiTimecode(h, m, s, f.toLong()), decoded.timeCode)
        }
    }

    @Test
    fun avcNalUnitRoundTrip() {
        for ((h, m, s, f) in listOf(listOf(0, 0, 0, 0), listOf(23, 59, 59, 59), listOf(12, 34, 56, 7))) {
            val encoded = AvcNalUnit.Sei(timeCode(h, m, s, f.toLong())).encode()
            assertEquals(6, encoded[0].toInt())
            assertEquals(1, encoded[1].toInt())
            val decoded = assertIs<AvcNalUnit.Sei>(AvcNalUnit.decode(encoded))
            assertEquals(AvcNalUnitType.SEI, decoded.type)
            assertEquals(SeiTimecode(h, m, s, f.toLong()), assertNotNull(decoded.pictureTiming))
        }
    }

    @Test
    fun accessUnitDelimiters() {
        assertContentEquals(bytes(0, 0, 0, 1, 0x09, 0x10), AvcNalUnit.AUD_10_WITH_START_CODE)
        assertContentEquals(bytes(0, 0, 0, 1, 0x09, 0x30), AvcNalUnit.AUD_30_WITH_START_CODE)
    }
}
