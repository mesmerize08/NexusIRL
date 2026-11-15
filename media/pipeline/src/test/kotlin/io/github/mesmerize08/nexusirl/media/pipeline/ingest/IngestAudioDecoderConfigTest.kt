package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngestAudioDecoderConfigTest {
    private fun parse(vararg bytes: Int) = IngestAudioDecoder.parseAudioSpecificConfig(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun aacLc() {
        assertEquals(44_100 to 2, parse(0x12, 0x10))
        assertEquals(48_000 to 1, parse(0x11, 0x88))
        // 5.1 and 7.1.
        assertEquals(48_000 to 6, parse(0x11, 0xB0))
        assertEquals(48_000 to 8, parse(0x11, 0xB8))
    }

    @Test
    fun escapedObjectTypeAndExplicitRate() {
        // Object type 42 (escape 31, then 32 + 10): USAC, 48 kHz stereo.
        assertEquals(48_000 to 2, parse(0xF9, 0x46, 0x40))
        // Frequency index 15: an explicit 24-bit rate, 22050 Hz mono.
        assertEquals(22_050 to 1, parse(0x17, 0x80, 0x2B, 0x11, 0x08))
    }

    @Test
    fun refusesInvalid() {
        assertNull(parse())
        assertNull(parse(0x12))
        // Object type 0.
        assertNull(parse(0x02, 0x10))
        // Reserved frequency index 13.
        assertNull(parse(0x16, 0x90))
        // Reserved channel configuration 8.
        assertNull(parse(0x12, 0x40))
        // Explicit rate cut short.
        assertNull(parse(0x17, 0x80, 0x2B))
    }
}
