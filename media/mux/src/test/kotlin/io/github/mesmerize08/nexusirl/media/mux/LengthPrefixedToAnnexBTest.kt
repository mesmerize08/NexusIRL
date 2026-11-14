package io.github.mesmerize08.nexusirl.media.mux

import io.github.mesmerize08.nexusirl.media.mux.nal.addNalUnitStartCodes
import io.github.mesmerize08.nexusirl.media.mux.nal.lengthPrefixedToAnnexB
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LengthPrefixedToAnnexBTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun convertsTwoUnits() {
        val out = lengthPrefixedToAnnexB(b(0, 0, 0, 2, 0x65, 1, 0, 0, 0, 1, 0x41))
        assertContentEquals(b(0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x41), out)
    }

    @Test
    fun twoByteLengths() {
        assertContentEquals(b(0, 0, 0, 1, 0x65, 1), lengthPrefixedToAnnexB(b(0, 2, 0x65, 1), 2))
    }

    @Test
    fun hugeLengthIsRejected() {
        val result = assertTimeoutPreemptively<ByteArray?>(Duration.ofSeconds(1)) {
            lengthPrefixedToAnnexB(b(0xFF, 0xFF, 0xFF, 0xFC, 1, 2, 3))
        }
        assertNull(result)
    }

    @Test
    fun zeroLengthRejected() {
        assertNull(lengthPrefixedToAnnexB(b(0, 0, 0, 0, 1)))
    }

    @Test
    fun truncatedUnitRejected() {
        assertNull(lengthPrefixedToAnnexB(b(0, 0, 0, 5, 1, 2)))
    }

    @Test
    fun trailingBytesRejected() {
        assertNull(lengthPrefixedToAnnexB(b(0, 0, 0, 1, 0x41, 0, 0)))
    }

    @Test
    fun badLengthSizeRejected() {
        assertNull(lengthPrefixedToAnnexB(b(0, 0, 0, 1, 0x41), 3))
    }

    @Test
    fun addNalUnitStartCodesStopsAtBadLength() {
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            addNalUnitStartCodes(b(0xFF, 0xFF, 0xFF, 0xFC, 1, 2, 3))
        }
    }

    @Test
    fun avcCLengthSize() {
        fun avcC(byte4: Int) = b(1, 0x64, 0, 0x1F, byte4, 0xE0)
        assertEquals(4, MpegTsVideoConfig.Avc.nalLengthSize(avcC(0xFF)))
        assertEquals(2, MpegTsVideoConfig.Avc.nalLengthSize(avcC(0xFD)))
        assertNull(MpegTsVideoConfig.Avc.nalLengthSize(avcC(0xFE)))
        assertNull(MpegTsVideoConfig.Avc.nalLengthSize(b(1, 2, 3, 4)))
    }

    @Test
    fun hvcCLengthSize() {
        fun hvcC(byte21: Int) = ByteArray(23).also { it[21] = byte21.toByte() }
        assertEquals(4, MpegTsVideoConfig.Hevc.nalLengthSize(hvcC(0x0F)))
        assertEquals(2, MpegTsVideoConfig.Hevc.nalLengthSize(hvcC(0x0D)))
    }
}
