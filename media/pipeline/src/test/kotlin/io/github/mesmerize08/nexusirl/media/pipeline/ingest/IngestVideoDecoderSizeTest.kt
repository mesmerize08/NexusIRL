package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestVideoDecoderSizeTest {
    @Test
    fun allowsUpTo4kInEitherOrientation() {
        assertTrue(IngestVideoDecoder.isSizeAllowed(3840, 2160))
        assertTrue(IngestVideoDecoder.isSizeAllowed(2160, 3840))
        assertTrue(IngestVideoDecoder.isSizeAllowed(1920, 1080))
    }

    @Test
    fun refusesLarger() {
        assertFalse(IngestVideoDecoder.isSizeAllowed(3841, 2160))
        assertFalse(IngestVideoDecoder.isSizeAllowed(2160, 3841))
        assertFalse(IngestVideoDecoder.isSizeAllowed(4096, 2160))
        assertFalse(IngestVideoDecoder.isSizeAllowed(3840, 2161))
    }
}
