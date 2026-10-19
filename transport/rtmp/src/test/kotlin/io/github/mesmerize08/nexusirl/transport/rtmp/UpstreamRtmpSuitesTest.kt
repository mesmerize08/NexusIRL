package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.core.clock.FakeHostClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** Port of MoblinTests/Moblin/Media/HaishinKit/Rtmp/Amf/AmfSuite.swift @97e26d2. */
class UpstreamAmfSuiteTest {
    private fun encode(value: AsValue): ByteArray = Amf0Encoder().apply { encode(value) }.data

    private fun decode(data: ByteArray): AsValue = Amf0Decoder(data).decode()

    @Test
    fun number() {
        val encoded = encode(AsValue.Number(1.0))
        assertContentEquals(bytes(0x00, 0x3F, 0xF0, 0, 0, 0, 0, 0, 0), encoded)
        assertEquals(AsValue.Number(1.0), decode(encoded))
    }

    @Test
    fun bool() {
        assertContentEquals(bytes(1, 1), encode(AsValue.Bool(true)))
        assertContentEquals(bytes(1, 0), encode(AsValue.Bool(false)))
        assertEquals(AsValue.Bool(true), decode(bytes(1, 1)))
        assertEquals(AsValue.Bool(false), decode(bytes(1, 0)))
    }

    @Test
    fun string() {
        val encoded = encode(AsValue.Str("1234"))
        assertContentEquals(bytes(0x02, 0x00, 0x04, 0x31, 0x32, 0x33, 0x34), encoded)
        assertEquals(AsValue.Str("1234"), decode(encoded))
    }

    @Test
    fun objectValue() {
        val value = AsValue.Object(mapOf("1" to AsValue.Str("2")))
        val encoded = encode(value)
        assertContentEquals(bytes(0x03, 0x00, 0x01, 0x31, 0x02, 0x00, 0x01, 0x32, 0x00, 0x00, 0x09), encoded)
        assertEquals(value, decode(encoded))
    }

    @Test
    fun typedObject() {
        val encoded = bytes(0x10, 0x00, 0x02, 0x32, 0x31, 0x00, 0x01, 0x31, 0x02, 0x00, 0x01, 0x32, 0x00, 0x00, 0x09)
        assertEquals(AsValue.TypedObject("21", mapOf("1" to AsValue.Str("2"))), decode(encoded))
    }

    @Test
    fun nullAndUndefined() {
        assertContentEquals(bytes(0x05), encode(AsValue.Null))
        assertEquals(AsValue.Null, decode(bytes(0x05)))
        assertEquals(AsValue.Undefined, decode(bytes(0x06)))
    }

    @Test
    fun ecmaArray() {
        val encoded = bytes(
            0x08, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x03, 0x66, 0x6F, 0x6F, 0x01, 0x01,
            0x00, 0x03, 0x62, 0x61, 0x72, 0x02, 0x00, 0x03, 0x66, 0x69, 0x65,
            0x00, 0x00, 0x09,
        )
        val decoded = assertIs<AsValue.EcmaArray>(decode(encoded))
        assertEquals(AsValue.Bool(true), decoded.items["foo"])
        assertEquals(AsValue.Str("fie"), decoded.items["bar"])
    }

    @Test
    fun strictArray() {
        val encoded = bytes(0x0A, 0x00, 0x00, 0x00, 0x02, 0x01, 0x01, 0x02, 0x00, 0x03, 0x66, 0x69, 0x65)
        val decoded = assertIs<AsValue.StrictArray>(decode(encoded))
        assertEquals(listOf(AsValue.Bool(true), AsValue.Str("fie")), decoded.items)
    }

    @Test
    fun date() {
        val value = AsValue.Date(Instant.ofEpochSecond(15))
        val encoded = encode(value)
        assertContentEquals(bytes(0x0B, 0x40, 0xCD, 0x4C, 0, 0, 0, 0, 0, 0, 0), encoded)
        assertEquals(value, decode(encoded))
    }

    @Test
    fun decodeIntNotRepresentable() {
        for (encoded in listOf(
            bytes(0x00, 0x7F, 0xF8, 0, 0, 0, 0, 0, 0),
            bytes(0x00, 0x7F, 0xF0, 0, 0, 0, 0, 0, 0),
            bytes(0x00, 0xFF, 0xF0, 0, 0, 0, 0, 0, 0),
            bytes(0x00, 0x7E, 0x00, 0, 0, 0, 0, 0, 0),
        )) {
            assertFailsWith<AmfException> { Amf0Decoder(encoded).decodeInt() }
        }
    }

    @Test
    fun decodeIntTruncates() {
        assertEquals(-1L, Amf0Decoder(encode(AsValue.Number(-1.75))).decodeInt())
    }

    @Test
    fun decodeTooDeeplyNested() {
        val encoded = ByteArray(10000 * 5 + 1)
        for (i in 0 until 10000) {
            bytes(0x0A, 0x00, 0x00, 0x00, 0x01).copyInto(encoded, i * 5)
        }
        encoded[encoded.size - 1] = 0x05
        val error = assertFailsWith<AmfException> { Amf0Decoder(encoded).decode() }
        assertEquals("Too deep", error.message)
    }

    @Test
    fun decodeSrsConnectResult() {
        // Abridged from upstream decodeRtmpFoo: a real SRS 5 `_result` for `connect`.
        val encoder = Amf0Encoder()
        encoder.encode(AsValue.Str("_result"))
        encoder.encode(AsValue.Number(1.0))
        encoder.encode(
            AsValue.Object(
                linkedMapOf(
                    "fmsVer" to AsValue.Str("FMS/3,5,3,888"),
                    "capabilities" to AsValue.Number(127.0),
                    "mode" to AsValue.Number(1.0),
                ),
            ),
        )
        encoder.encode(
            AsValue.Object(linkedMapOf("level" to AsValue.Str("status"), "code" to AsValue.Str("NetConnection.Connect.Success"))),
        )
        val command = RtmpCommand.decode(encoder.data)!!
        assertEquals(RtmpCommand.RESULT, command.name)
        assertEquals(1L, command.transactionId)
        assertEquals(AsValue.Number(127.0), command.commandObject!!["capabilities"])
        assertEquals(AsValue.Str("NetConnection.Connect.Success"), (command.arguments[0] as AsValue.Object).value["code"])
    }
}

/** Port of MoblinTests/Moblin/Media/HaishinKit/Rtmp/RtmpSuite.swift @97e26d2. */
class UpstreamRtmpSuiteTest {
    @Test
    fun twitchUrl() {
        val url = "rtmp://foo.com/app/live_asefwefwefwef"
        assertEquals("rtmp://foo.com/app", RtmpUrl.uri(url))
        assertEquals("live_asefwefwefwef", RtmpUrl.streamKey(url))
    }

    @Test
    fun kickUrl() {
        val url = "rtmp://foo.com/foobar"
        assertEquals("rtmp://foo.com", RtmpUrl.uri(url))
        assertEquals("foobar", RtmpUrl.streamKey(url))
    }

    @Test
    fun bilibiliUrl() {
        val url = "rtmp://foo.com/live/?foo=bar&a=b"
        assertEquals("rtmp://foo.com/live", RtmpUrl.uri(url))
        assertEquals("?foo=bar&a=b", RtmpUrl.streamKey(url))
    }

    @Test
    fun twitcastingUrl() {
        val url = "rtmp://foo.com/live/g:3234234?key=1234"
        assertEquals("rtmp://foo.com/live", RtmpUrl.uri(url))
        assertEquals("g:3234234?key=1234", RtmpUrl.streamKey(url))
    }

    private fun connectResultChunkData(): ByteArray {
        val command =
            RtmpCommand(RtmpCommand.RESULT, 1, null, listOf(AsValue.Object(mapOf("code" to AsValue.Str("NetConnection.Connect.Success")))))
        return encodeChunks(0, RtmpChunkStreamId.COMMAND, RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, command.encode()), 128).single()
    }

    @Test
    fun connectResultInOneRead() {
        val reader = RtmpChunkReader()
        val messages = ArrayList<RtmpMessage>()
        assertTrue(reader.read(connectResultChunkData()) { messages.add(it) }.isEmpty())
        assertEquals(1, RtmpCommand.decode(messages.single().payload)!!.arguments.size)
    }

    @Test
    fun connectResultSplitAfterChunkHeader() {
        val reader = RtmpChunkReader()
        val messages = ArrayList<RtmpMessage>()
        val data = connectResultChunkData()
        val buffer = reader.read(data.copyOfRange(0, 12)) { messages.add(it) }
        assertContentEquals(data.copyOfRange(0, 12), buffer)
        assertTrue(reader.read(buffer + data.copyOfRange(12, data.size)) { messages.add(it) }.isEmpty())
        assertEquals(1, RtmpCommand.decode(messages.single().payload)!!.arguments.size)
    }
}

/** Port of MoblinTests/Moblin/Media/HaishinKit/Rtmp/RtmpStreamInfoSuite.swift @97e26d2. */
class UpstreamRtmpStreamInfoSuiteTest {
    private fun info() = RtmpStreamInfo(FakeHostClock())

    @Test
    fun initialState() {
        val info = info()
        assertEquals(0, info.bitrateStats.totalBytes)
        assertEquals(0, info.bitrateStats.latestSpeed)
        assertEquals(RtmpStreamStats(), info.stats)
    }

    @Test
    fun clearResetsState() {
        val info = info()
        info.bitrateStats.add(5000)
        info.onTimeout()
        info.onWritten(1400)
        info.clear()
        assertEquals(5000, info.bitrateStats.totalBytes)
        assertEquals(1500, info.bitrateStats.latestSpeed)
        assertEquals(RtmpStreamStats(), info.stats)
    }

    @Test
    fun onTimeoutExponentialSmoothing() {
        val info = info()
        info.bitrateStats.add(1000)
        info.onTimeout()
        assertEquals(300, info.bitrateStats.latestSpeed)
        info.bitrateStats.add(1000)
        info.onTimeout()
        assertEquals(510, info.bitrateStats.latestSpeed)
        info.onTimeout()
        assertEquals(357, info.bitrateStats.latestSpeed)
    }

    @Test
    fun onTimeoutNoNewBytes() {
        val info = info()
        info.onTimeout()
        info.onTimeout()
        assertEquals(0, info.bitrateStats.latestSpeed)
    }

    @Test
    fun onWrittenAndAckPacketsInFlight() {
        val info = info()
        info.onWritten(1400)
        assertEquals(1, info.stats.packetsInFlight)
        info.onWritten(2800)
        info.onWritten(4200)
        assertEquals(3, info.stats.packetsInFlight)
        info.onAck(2800)
        assertEquals(1, info.stats.packetsInFlight)
        info.onAck(4201)
        assertEquals(0, info.stats.packetsInFlight)
    }

    @Test
    fun onAckUpdatesRtt() {
        val clock = FakeHostClock()
        val info = RtmpStreamInfo(clock)
        info.onWritten(1400)
        clock.advanceMillis(200)
        info.onAck(1401)
        assertEquals(200.0, info.stats.rttMs)
    }

    @Test
    fun onAckSequenceRolloverAtInt32Max() {
        val info = info()
        info.onWritten(Int.MAX_VALUE.toLong() - 500)
        info.onWritten(Int.MAX_VALUE.toLong() + 5000)
        info.onAck(Int.MAX_VALUE.toLong() - 600)
        info.onAck(1000)
        assertEquals(2, info.stats.packetsInFlight)
    }

    @Test
    fun onAckSequenceRolloverAtUInt32Max() {
        val info = info()
        val aboveInt32Max = Int.MAX_VALUE.toLong() + 1000
        info.onWritten(aboveInt32Max - 500)
        info.onWritten(0xFFFF_FFFFL + 5000)
        info.onAck(aboveInt32Max - 600)
        info.onAck(1000)
        assertEquals(2, info.stats.packetsInFlight)
    }

    @Test
    fun packetsInFlightNeverNegative() {
        val info = info()
        info.onAck(5000)
        info.onWritten(1400)
        assertEquals(0, info.stats.packetsInFlight)
    }

    @Test
    fun multipleOnWrittenAccumulatesTimings() {
        val info = info()
        for (i in 1..10) {
            info.onWritten(i * 1400L)
        }
        assertEquals(10, info.stats.packetsInFlight)
        info.onAck(7000)
        assertEquals(5, info.stats.packetsInFlight)
    }
}

class RtmpChunkTest {
    @Test
    fun largeMessagesAreSplitWithType3Continuations() {
        val payload = ByteArray(300) { it.toByte() }
        val chunks = encodeChunks(0, 9, RtmpMessage(RtmpMessageType.VIDEO, 1, 1234, payload), 128)
        assertEquals(3, chunks.size)
        assertEquals(12 + 128, chunks[0].size)
        assertEquals(0xC9.toByte(), chunks[1][0]) // fmt 3, csid 9
        val reader = RtmpChunkReader()
        val messages = ArrayList<RtmpMessage>()
        val rest = reader.read(chunks.fold(ByteArray(0)) { acc, c -> acc + c }) { messages.add(it) }
        assertTrue(rest.isEmpty())
        assertContentEquals(payload, messages.single().payload)
        assertEquals(1234L, messages.single().timestamp)
        assertEquals(1L, messages.single().streamId)
    }

    @Test
    fun extendedTimestampRoundTrip() {
        val chunks = encodeChunks(0, 8, RtmpMessage(RtmpMessageType.AUDIO, 1, 0x0123_4567, ByteArray(200)), 128)
        val messages = ArrayList<RtmpMessage>()
        RtmpChunkReader().read(chunks.fold(ByteArray(0)) { acc, c -> acc + c }) { messages.add(it) }
        assertEquals(0x0123_4567L, messages.single().timestamp)
    }

    @Test
    fun avcCFromParameterSets() {
        val sps = bytes(0x67, 0x64, 0x00, 0x1F, 0xAC)
        val pps = bytes(0x68, 0xEE, 0x3C, 0x80)
        val avcC = Flv.makeAvcC(sps, pps)
        assertContentEquals(bytes(1, 0x64, 0x00, 0x1F, 0xFF, 0xE1, 0, 5) + sps + bytes(1, 0, 4) + pps, avcC)
    }

    @Test
    fun emulationPreventionIsRemovedForHvcC() {
        assertContentEquals(
            bytes(0x00, 0x00, 0x01, 0x00, 0x00, 0x00),
            Flv.removeEmulationPrevention(bytes(0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x03, 0x00)),
        )
    }
}
