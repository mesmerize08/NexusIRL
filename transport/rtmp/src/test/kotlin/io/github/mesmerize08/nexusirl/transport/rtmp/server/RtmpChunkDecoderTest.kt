package io.github.mesmerize08.nexusirl.transport.rtmp.server

import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessage
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessageType
import io.github.mesmerize08.nexusirl.transport.rtmp.encodeChunks
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RtmpChunkDecoderTest {
    private val big = 8 * 1024 * 1024

    private fun decoder(limits: RtmpServerLimits = RtmpServerLimits()) = RtmpChunkDecoder(limits) { big }

    private fun RtmpChunkDecoder.collect(data: ByteArray, step: Int = data.size): List<RtmpDecodedMessage> {
        val out = ArrayList<RtmpDecodedMessage>()
        var at = 0
        while (at < data.size) {
            val n = minOf(step, data.size - at)
            feed(data, at, n) { out.add(it) }
            at += n
        }
        return out
    }

    private fun payload(size: Int) = ByteArray(size) { (it * 7).toByte() }

    private fun encode(type: Int, csid: Int, message: RtmpMessage, size: Int = 128) = encodeChunks(type, csid, message, size).reduce {
            a,
            b,
        ->
        a +
            b
    }

    private fun failure(block: () -> Unit) = assertFailsWith<RtmpProtocolException> { block() }.reason

    @Test
    fun decodesMessageSplitIntoChunks() {
        val body = payload(300)
        val data = encode(0, 3, RtmpMessage(RtmpMessageType.AMF0_COMMAND, 1, 500, body))
        val messages = decoder().collect(data, 1)
        assertEquals(1, messages.size)
        val m = messages[0]
        assertEquals(RtmpMessageType.AMF0_COMMAND, m.typeId)
        assertEquals(1L, m.messageStreamId)
        assertEquals(500L, m.timestamp)
        assertEquals(3, m.chunkStreamId)
        assertContentEquals(body, m.payload)
    }

    @Test
    fun typeOneAndTwoHeadersAreDeltas() {
        val d = decoder()
        val first = encode(0, 4, RtmpMessage(RtmpMessageType.VIDEO, 7, 1000, payload(10)))
        val second = encode(1, 4, RtmpMessage(RtmpMessageType.VIDEO, 0, 40, payload(10)))
        // Type 2: basic header, 3-byte delta, then the body (same length and type as before).
        val third = byteArrayOf((0x80 or 4).toByte(), 0, 0, 40) + payload(10)
        val m = d.collect(first + second + third)
        assertEquals(listOf(1000L, 40L, 40L), m.map { it.timestamp })
        assertEquals(listOf(true, false, false), m.map { it.isAbsolute })
        assertEquals(listOf(7L, 7L, 7L), m.map { it.messageStreamId })
        assertEquals(listOf(10, 10, 10), m.map { it.payload.size })
        assertEquals(listOf(9, 9, 9), m.map { it.typeId })
    }

    @Test
    fun extendedTimestamp() {
        val body = payload(300)
        val data = encode(0, 3, RtmpMessage(RtmpMessageType.VIDEO, 1, 0x1_000_000, body))
        val m = decoder().collect(data, 3)
        assertEquals(1, m.size)
        assertEquals(0x1_000_000L, m[0].timestamp)
        assertContentEquals(body, m[0].payload)
    }

    @Test
    fun twoAndThreeByteBasicHeadersAreRejected() {
        assertEquals("Two bytes basic header is not implemented", failure { decoder().feed(byteArrayOf(0), 0, 1) {} })
        assertEquals("Three bytes basic header is not implemented", failure { decoder().feed(byteArrayOf(1), 0, 1) {} })
    }

    @Test
    fun tooManyChunkStreams() {
        val d = decoder()
        for (csid in 2..17) {
            val data = encode(0, csid, RtmpMessage(RtmpMessageType.VIDEO, 1, 0, payload(5)))
            d.feed(data, 0, data.size) {}
        }
        val data = encode(0, 18, RtmpMessage(RtmpMessageType.VIDEO, 1, 0, payload(5)))
        assertEquals("Too many chunk streams", failure { d.feed(data, 0, data.size) {} })
    }

    @Test
    fun oversizedMessageRejectedBeforeAllocation() {
        val header = byteArrayOf(3, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 9, 1, 0, 0, 0)
        val d = decoder()
        assertEquals("Message of 16777215 bytes is too large", failure { d.feed(header, 0, 12) {} })
    }

    @Test
    fun partialDataLimit() {
        val d = decoder(RtmpServerLimits(maxPartialBytes = 1000))
        val reason = failure {
            for (csid in 2..10) {
                val chunk = encodeChunks(0, csid, RtmpMessage(RtmpMessageType.VIDEO, 1, 0, payload(600)), 128)[0]
                d.feed(chunk, 0, chunk.size) {}
            }
        }
        assertEquals("Too much incomplete data", reason)
    }

    @Test
    fun partialBudgetCountsAllocatedCapacity() {
        // 1152 bytes received, under 3000, but growing the array to 2000 would hold 1024 + 2000.
        val d = decoder(RtmpServerLimits(maxPartialBytes = 3000))
        val chunks = encodeChunks(0, 3, RtmpMessage(RtmpMessageType.VIDEO, 1, 0, payload(2000)), 128)
        val reason = failure { chunks.take(9).forEach { d.feed(it, 0, it.size) {} } }
        assertEquals("Too much incomplete data", reason)
    }

    @Test
    fun partialBudgetIsReleasedWhenMessageCompletes() {
        val d = decoder(RtmpServerLimits(maxPartialBytes = 3000))
        repeat(3) {
            val m = d.collect(encode(0, 3, RtmpMessage(RtmpMessageType.VIDEO, 1, 0, payload(1500))))
            assertEquals(1, m.size)
        }
    }

    @Test
    fun decoderStaysFailedAfterAnError() {
        val d = decoder()
        failure { d.feed(byteArrayOf(0), 0, 1) {} }
        val good = encode(0, 3, RtmpMessage(RtmpMessageType.VIDEO, 1, 0, payload(5)))
        var emitted = 0
        failure { d.feed(byteArrayOf(0x03, 0), 0, 2) { emitted++ } }
        failure { d.feed(good, 0, good.size) { emitted++ } }
        assertEquals(0, emitted)
    }

    @Test
    fun zeroLengthMessageRejected() {
        val header = byteArrayOf(3, 0, 0, 0, 0, 0, 0, 20, 0, 0, 0, 0)
        assertEquals("Unexpected data", failure { decoder().feed(header, 0, 12) {} })
    }

    @Test
    fun chunkSizeApplies() {
        val d = decoder()
        d.chunkSize = 4096
        val body = payload(5000)
        val m = d.collect(encode(0, 3, RtmpMessage(RtmpMessageType.VIDEO, 1, 0, body), 4096), 777)
        assertEquals(1, m.size)
        assertContentEquals(body, m[0].payload)
    }
}
