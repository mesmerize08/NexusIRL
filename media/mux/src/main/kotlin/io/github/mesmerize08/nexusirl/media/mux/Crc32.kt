// Port of Moblin/Media/HaishinKit/Mpeg/Crc32.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux

/** CRC-32/MPEG-2 (polynomial 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final XOR). */
class Crc32 private constructor(polynomial: Int) {
    private val table = IntArray(256) { index ->
        var crc = index shl 24
        repeat(8) {
            crc = (crc shl 1) xor (if (crc and 0x8000_0000.toInt() != 0) polynomial else 0)
        }
        crc
    }

    fun calculate(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var crc = -1
        for (i in offset until offset + length) {
            crc = (crc shl 8) xor table[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc.toLong() and 0xFFFF_FFFFL
    }

    companion object {
        val mpeg2 = Crc32(0x04C1_1DB7)
    }
}
