package io.github.mesmerize08.nexusirl.transport.rtmp.server

import io.github.mesmerize08.nexusirl.core.clock.FakeHostClock
import io.github.mesmerize08.nexusirl.transport.rtmp.AsObject
import io.github.mesmerize08.nexusirl.transport.rtmp.AsValue
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpChunkReader
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpCommand
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessage
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessageType
import io.github.mesmerize08.nexusirl.transport.rtmp.encodeChunks
import io.github.mesmerize08.nexusirl.transport.rtmp.uint32Payload
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtmpServerSessionTest {
    private val stream1 = RtmpServerStream(UUID.randomUUID(), "One", "key-1", 2_000)
    private val clock = FakeHostClock()

    private class Recorder : RtmpServerSession.Events {
        val published = ArrayList<RtmpServerStream>()
        var wrongKeys = 0
        val media = ArrayList<RtmpIngestMedia>()
        val closed = ArrayList<String>()

        override fun onPublish(stream: RtmpServerStream) {
            published.add(stream)
        }

        override fun onWrongKey() {
            wrongKeys += 1
        }

        override fun onMedia(media: RtmpIngestMedia) {
            this.media.add(media)
        }

        override fun onClosed(reason: String) {
            closed.add(reason)
        }
    }

    /** A test client: builds client bytes and parses what the session sent back. */
    private inner class Client(streams: List<RtmpServerStream> = listOf(stream1), limits: RtmpServerLimits = RtmpServerLimits()) {
        val events = Recorder()
        val sends = ArrayList<ByteArray>()
        val session = RtmpServerSession({ streams }, limits, clock, { sends.add(it) }, events, Random(1))
        private val reader = RtmpChunkReader()
        private var pending = ByteArray(0)
        private var parsedSends = 0
        var c1 = ByteArray(1536) { (it * 31 + 7).toByte() }

        var fed = 0L

        fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            fed += length
            session.feed(data, offset, length)
        }

        fun c0c1(version: Int = 3) = byteArrayOf(version.toByte()) + c1

        fun handshake() {
            feed(c0c1() + ByteArray(1536) { 0x55 })
            parsedSends = sends.size
        }

        /** The messages sent since the last call (after the handshake). */
        fun replies(): List<RtmpMessage> {
            val out = ArrayList<RtmpMessage>()
            while (parsedSends < sends.size) {
                pending += sends[parsedSends++]
            }
            pending = reader.read(pending) { message ->
                if (message.type == RtmpMessageType.CHUNK_SIZE) {
                    reader.maximumChunkSize = u32(message.payload, 0).toInt()
                }
                out.add(message)
            }
            return out
        }

        fun connect(tcUrl: String? = "rtmp://host/live") {
            val obj = LinkedHashMap<String, AsValue>()
            obj["app"] = AsValue.Str("live")
            if (tcUrl != null) {
                obj["tcUrl"] = AsValue.Str(tcUrl)
            }
            feed(command("connect", 1, obj))
        }

        fun publish(key: String) {
            handshake()
            connect()
            feed(command("createStream", 2, null))
            feed(command("publish", 3, null, listOf(AsValue.Str(key), AsValue.Str("live")), streamId = 1))
        }
    }

    private fun u32(data: ByteArray, at: Int): Long = ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
        ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun message(
        type: Int,
        payload: ByteArray,
        csid: Int = 3,
        streamId: Long = 0,
        timestamp: Long = 0,
        chunkType: Int = 0,
    ): ByteArray = encodeChunks(chunkType, csid, RtmpMessage(type, streamId, timestamp, payload), 128).reduce { a, b -> a + b }

    private fun command(
        name: String,
        transactionId: Long,
        commandObject: AsObject?,
        arguments: List<AsValue> = emptyList(),
        csid: Int = 3,
        streamId: Long = 0,
    ) = message(RtmpMessageType.AMF0_COMMAND, RtmpCommand(name, transactionId, commandObject, arguments).encode(), csid, streamId)

    private fun avcSequenceHeader() = byteArrayOf(0x17, 0, 0, 0, 0, 1, 0x64, 0, 0x1F)

    private fun avcFrame(compositionTime: Int, size: Int = 20) =
        byteArrayOf(0x17, 1, (compositionTime shr 16).toByte(), (compositionTime shr 8).toByte(), compositionTime.toByte()) +
            ByteArray(size) { (it + 1).toByte() }

    private fun aacFrame() = byteArrayOf(0xAF.toByte(), 1, 0x21, 0x10, 0x05)

    private fun video(body: ByteArray, timestamp: Long, chunkType: Int = 0) =
        message(RtmpMessageType.VIDEO, body, csid = 9, streamId = 1, timestamp = timestamp, chunkType = chunkType)

    private fun resultCommand(message: RtmpMessage) = RtmpCommand.decode(message.payload)!!

    private fun argumentObject(command: RtmpCommand) = (command.arguments[0] as AsValue.Object).value

    @Test
    fun handshakeRepliesAsUpstream() {
        val client = Client()
        client.feed(client.c0c1())
        val out = client.sends.reduce { a, b -> a + b }
        assertEquals(1 + 1536 + 1536, out.size)
        assertEquals(3, out[0].toInt())
        val s1 = out.copyOfRange(1, 1537)
        assertContentEquals(ByteArray(8), s1.copyOfRange(0, 8))
        val s2 = out.copyOfRange(1537, 3073)
        assertContentEquals(client.c1.copyOfRange(0, 4), s2.copyOfRange(0, 4))
        assertContentEquals(ByteArray(4), s2.copyOfRange(4, 8))
        assertContentEquals(client.c1.copyOfRange(8, 1536), s2.copyOfRange(8, 1536))
        assertEquals(RtmpServerSession.State.HANDSHAKE, client.session.state)
        assertTrue(client.events.closed.isEmpty())
    }

    @Test
    fun handshakeAcrossManyFeeds() {
        val client = Client()
        val data = client.c0c1() + ByteArray(1536) + run {
            val obj = linkedMapOf<String, AsValue>("tcUrl" to AsValue.Str("rtmp://host/live"))
            command("connect", 1, obj)
        }
        for (b in data) {
            client.feed(byteArrayOf(b))
        }
        assertEquals(RtmpServerSession.State.CONNECTED, client.session.state)
    }

    @Test
    fun emptyFeedDoesNothing() {
        val client = Client()
        client.feed(ByteArray(0))
        client.feed(client.c0c1(), 0, 0)
        assertTrue(client.events.closed.isEmpty())
        assertTrue(client.sends.isEmpty())
        client.handshake()
        assertTrue(client.events.closed.isEmpty())
    }

    @Test
    fun wrongVersionCloses() {
        val client = Client()
        client.feed(client.c0c1(version = 6))
        assertEquals(listOf("Only version 3 is supported, not 6"), client.events.closed)
        assertEquals(RtmpServerSession.State.CLOSED, client.session.state)
        assertTrue(client.sends.isEmpty())
    }

    @Test
    fun connectRepliesInOrder() {
        val client = Client()
        client.handshake()
        val obj = linkedMapOf<String, AsValue>("tcUrl" to AsValue.Str("rtmp://host/live"))
        client.feed(command("connect", 7, obj, csid = 5))
        val replies = client.replies()
        assertEquals(listOf(5, 6, 1, RtmpMessageType.AMF0_COMMAND), replies.map { it.type })
        assertEquals(2_500_000L, u32(replies[0].payload, 0))
        assertEquals(5, replies[1].payload.size)
        assertEquals(2_500_000L, u32(replies[1].payload, 0))
        assertEquals(2, replies[1].payload[4].toInt())
        assertEquals(1024L, u32(replies[2].payload, 0))
        val result = resultCommand(replies[3])
        assertEquals("_result", result.name)
        assertEquals(7L, result.transactionId)
        assertTrue(result.commandObject!!.isEmpty())
        val argument = argumentObject(result)
        assertEquals(AsValue.Str("status"), argument["level"])
        assertEquals(AsValue.Str("NetConnection.Connect.Success"), argument["code"])
        assertEquals(AsValue.Str("Connection succeeded."), argument["description"])
        // The _result goes on the request's chunk stream.
        assertEquals(5, client.sends.last()[0].toInt() and 0x3F)
        assertEquals(RtmpServerSession.State.CONNECTED, client.session.state)
    }

    @Test
    fun publishBeforeConnectAndSecondConnectAreIgnored() {
        val client = Client()
        client.handshake()
        client.feed(command("publish", 3, null, listOf(AsValue.Str("key-1"))))
        assertEquals(RtmpServerSession.State.HANDSHAKE, client.session.state)
        assertTrue(client.events.published.isEmpty())
        client.connect()
        client.feed(command("publish", 4, null, listOf(AsValue.Str("key-1")), streamId = 1))
        assertEquals(listOf(stream1), client.events.published)
        client.replies()
        client.connect()
        assertTrue(client.replies().isEmpty())
        assertEquals(RtmpServerSession.State.PUBLISHING, client.session.state)
    }

    @Test
    fun connectWrongAppCloses() {
        val wrongApp = Client()
        wrongApp.handshake()
        wrongApp.connect("rtmp://host/app")
        assertEquals(listOf("Not a camera path"), wrongApp.events.closed)
        val missing = Client()
        missing.handshake()
        missing.connect(null)
        assertEquals(listOf("Stream URL missing"), missing.events.closed)
        val invalid = Client()
        invalid.handshake()
        invalid.connect("rtmp://host/live path with spaces")
        assertEquals(listOf("Invalid stream URL"), invalid.events.closed)
    }

    @Test
    fun publishWithKeyStarts() {
        val client = Client()
        client.handshake()
        client.connect()
        client.replies()
        client.feed(command("releaseStream", 2, null, listOf(AsValue.Str("key-1"))))
        client.feed(command("FCPublish", 3, null, listOf(AsValue.Str("key-1"))))
        client.feed(command("createStream", 4, null))
        val created = resultCommand(client.replies().single())
        assertEquals("_result", created.name)
        assertEquals(4L, created.transactionId)
        assertEquals(listOf<AsValue>(AsValue.Number(1.0)), created.arguments)
        client.feed(command("publish", 5, null, listOf(AsValue.Str("key-1"), AsValue.Str("live")), streamId = 1))
        val status = client.replies().single()
        assertEquals(1L, status.streamId)
        val onStatus = resultCommand(status)
        assertEquals("onStatus", onStatus.name)
        assertEquals(5L, onStatus.transactionId)
        val argument = argumentObject(onStatus)
        assertEquals(AsValue.Str("status"), argument["level"])
        assertEquals(AsValue.Str("NetStream.Publish.Start"), argument["code"])
        assertEquals(AsValue.Str("Start publishing."), argument["description"])
        assertEquals(RtmpServerSession.State.PUBLISHING, client.session.state)
        assertEquals(listOf(stream1), client.events.published)
        assertEquals(stream1, client.session.stream)
        assertTrue(client.events.closed.isEmpty())
    }

    @Test
    fun publishPicksTheMatchingStream() {
        val stream2 = RtmpServerStream(UUID.randomUUID(), "Two", "key-2", 500)
        val client = Client(listOf(stream1, stream2))
        client.publish("key-2")
        assertEquals(listOf(stream2), client.events.published)
    }

    @Test
    fun wrongKeyClosesWithoutTheKey() {
        val client = Client()
        client.publish("nope")
        assertEquals(1, client.events.wrongKeys)
        assertEquals(listOf("Stream key not configured"), client.events.closed)
        assertFalse(client.events.closed.single().contains("nope"))
        assertTrue(client.events.published.isEmpty())
        assertNull(client.session.stream)
    }

    @Test
    fun emptyKeysNeverMatch() {
        val client = Client(listOf(RtmpServerStream(UUID.randomUUID(), "Empty", "", 0)))
        client.publish("")
        assertEquals(1, client.events.wrongKeys)
        assertEquals(listOf("Stream key not configured"), client.events.closed)
    }

    @Test
    fun publishArgumentErrors() {
        val missing = Client()
        missing.handshake()
        missing.connect()
        missing.feed(command("publish", 3, null))
        assertEquals(listOf("Missing publish argument"), missing.events.closed)
        val notString = Client()
        notString.handshake()
        notString.connect()
        notString.feed(command("publish", 3, null, listOf(AsValue.Number(5.0))))
        assertEquals(listOf("Stream key not a string"), notString.events.closed)
    }

    @Test
    fun amfDecodeErrorCloses() {
        val client = Client()
        client.handshake()
        client.feed(message(RtmpMessageType.AMF0_COMMAND, byteArrayOf(2, 0, 7, 'c'.code.toByte())))
        assertEquals(1, client.events.closed.size)
        assertTrue(client.events.closed.single().startsWith("AMF-0 decode error "), client.events.closed.single())
    }

    @Test
    fun amfDataAndUnknownCommandsAreIgnored() {
        val client = Client()
        client.handshake()
        client.connect()
        client.replies()
        client.feed(message(RtmpMessageType.AMF0_DATA, byteArrayOf(1, 2, 3)))
        client.feed(command("someCommand", 9, null))
        client.feed(command("deleteStream", 9, null))
        client.feed(command("FCUnpublish", 9, null))
        assertTrue(client.replies().isEmpty())
        assertTrue(client.events.closed.isEmpty())
    }

    @Test
    fun mediaBeforePublishDropped() {
        val client = Client()
        client.handshake()
        client.connect()
        client.feed(video(avcSequenceHeader(), 0))
        assertTrue(client.events.media.isEmpty())
        assertTrue(client.events.closed.isEmpty())
    }

    @Test
    fun configsAreForwarded() {
        val client = Client()
        client.publish("key-1")
        client.feed(video(avcSequenceHeader(), 0))
        client.feed(message(RtmpMessageType.AUDIO, byteArrayOf(0xAF.toByte(), 0, 0x12, 0x10), csid = 8, streamId = 1))
        assertEquals(
            listOf(
                RtmpIngestMedia.VideoConfig(IngestVideoCodec.H264, byteArrayOf(1, 0x64, 0, 0x1F)),
                RtmpIngestMedia.AudioConfig(byteArrayOf(0x12, 0x10)),
            ),
            client.events.media,
        )
    }

    @Test
    fun videoTimestamps() {
        clock.advanceMillis(5_000)
        val client = Client()
        client.publish("key-1")
        client.feed(video(avcFrame(40), 100))
        clock.advanceMillis(500)
        client.feed(video(avcFrame(0), 33, chunkType = 1))
        val frames = client.events.media.map { it as RtmpIngestMedia.VideoFrame }
        assertEquals(listOf(7_000L, 7_033L), frames.map { it.dtsMs })
        assertEquals(listOf(7_040L, 7_033L), frames.map { it.ptsMs })
        assertTrue(frames[0].isKeyFrame)
        assertContentEquals(avcFrame(40).copyOfRange(5, 25), frames[0].data)
    }

    @Test
    fun zeroIsPerClient() {
        clock.advanceMillis(1_000)
        val client = Client()
        client.publish("key-1")
        client.feed(message(RtmpMessageType.AUDIO, aacFrame(), csid = 8, streamId = 1, timestamp = 1_000))
        client.feed(video(avcFrame(0), 1_000))
        val audio = client.events.media[0] as RtmpIngestMedia.AudioFrame
        val video = client.events.media[1] as RtmpIngestMedia.VideoFrame
        assertEquals(3_000L, audio.ptsMs)
        assertEquals(audio.ptsMs, video.dtsMs)
    }

    @Test
    fun timestampsUnwrap() {
        val client = Client()
        client.publish("key-1")
        client.feed(video(avcFrame(0), 0xFFFF_FF00L))
        client.feed(video(avcFrame(0), 0x200, chunkType = 1))
        client.feed(video(avcFrame(0), 0x100, chunkType = 1))
        val dts = client.events.media.map { (it as RtmpIngestMedia.VideoFrame).dtsMs }
        assertEquals(listOf(0x200L, 0x100L), listOf(dts[1] - dts[0], dts[2] - dts[1]))
    }

    @Test
    fun timestampsStepBackWithoutWrapping() {
        val client = Client()
        client.publish("key-1")
        client.feed(video(avcFrame(0), 1_000))
        client.feed(video(avcFrame(0), 900))
        val dts = client.events.media.map { (it as RtmpIngestMedia.VideoFrame).dtsMs }
        assertEquals(-100L, dts[1] - dts[0])
    }

    @Test
    fun ackAfterWindow() {
        val client = Client()
        client.handshake()
        client.connect()
        client.feed(message(RtmpMessageType.WINDOW_ACK, uint32Payload(1_000), csid = 2))
        client.replies()
        // 14 ignored data messages of 112 bytes each: 1,568 bytes, more than the window once.
        val data = List(14) { message(RtmpMessageType.AMF0_DATA, ByteArray(100), csid = 4) }.reduce { a, b -> a + b }
        assertEquals(1_568, data.size)
        client.feed(data)
        val acks = client.replies().filter { it.type == RtmpMessageType.ACK }
        assertEquals(1, acks.size)
        assertEquals(client.fed, u32(acks[0].payload, 0))
    }

    @Test
    fun chunkSizeErrors() {
        val wrongSize = Client()
        wrongSize.handshake()
        wrongSize.feed(message(RtmpMessageType.CHUNK_SIZE, byteArrayOf(0, 0, 1), csid = 2))
        assertEquals(listOf("Not 4 bytes chunk size"), wrongSize.events.closed)
        val zero = Client()
        zero.handshake()
        zero.feed(message(RtmpMessageType.CHUNK_SIZE, uint32Payload(0), csid = 2))
        assertEquals(listOf("Invalid chunk size 0"), zero.events.closed)
        val huge = Client()
        huge.handshake()
        huge.feed(message(RtmpMessageType.CHUNK_SIZE, uint32Payload(0x0100_0000), csid = 2))
        assertEquals(listOf("Invalid chunk size 16777216"), huge.events.closed)
        val windowAck = Client()
        windowAck.handshake()
        windowAck.feed(message(RtmpMessageType.WINDOW_ACK, byteArrayOf(1), csid = 2))
        assertEquals(listOf("Not 4 bytes window acknowledgement size"), windowAck.events.closed)
    }

    @Test
    fun clientChunkSizeApplies() {
        val client = Client()
        client.handshake()
        client.feed(message(RtmpMessageType.CHUNK_SIZE, uint32Payload(4096), csid = 2))
        client.connect()
        client.replies()
        val body = RtmpCommand("createStream", 2, null, listOf(AsValue.Str("x".repeat(500)))).encode()
        client.feed(encodeChunks(0, 3, RtmpMessage(RtmpMessageType.AMF0_COMMAND, 0, 0, body), 4096).single())
        assertEquals(1, client.replies().size)
    }

    @Test
    fun sequenceEndCloses() {
        val client = Client()
        client.publish("key-1")
        client.feed(video(byteArrayOf(0x92.toByte(), 'h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte()), 0))
        assertEquals(listOf("Stream ended"), client.events.closed)
    }

    @Test
    fun parserFailureCloses() {
        val client = Client()
        client.publish("key-1")
        client.feed(message(RtmpMessageType.AUDIO, byteArrayOf(0x90.toByte(), 0, 1, 2), csid = 8, streamId = 1))
        assertEquals(listOf("Unsupported audio codec exHeader. Only AAC is supported."), client.events.closed)
    }

    @Test
    fun limitsSwitchAtPublish() {
        val big = video(avcFrame(0, size = 70_000 - 5), 0)
        val before = Client()
        before.handshake()
        before.connect()
        before.feed(big)
        assertEquals(listOf("Message of 70000 bytes is too large"), before.events.closed)
        val after = Client()
        after.publish("key-1")
        after.feed(big)
        assertTrue(after.events.closed.isEmpty())
        assertEquals(70_000 - 5, (after.events.media.single() as RtmpIngestMedia.VideoFrame).data.size)
    }

    @Test
    fun commandsAfterPublishUseTheCommandLimit() {
        val client = Client(limits = RtmpServerLimits(maxCommandMessage = 100))
        client.publish("key-1")
        client.feed(message(RtmpMessageType.AMF0_DATA, ByteArray(101)))
        assertEquals(listOf("Message of 101 bytes is too large"), client.events.closed)
    }

    @Test
    fun closeIsReportedOnce() {
        val client = Client()
        client.handshake()
        val bad = message(RtmpMessageType.CHUNK_SIZE, byteArrayOf(0, 0, 1), csid = 2)
        client.feed(bad + bad)
        client.feed(bad)
        client.session.close("Again")
        assertEquals(listOf("Not 4 bytes chunk size"), client.events.closed)
        assertEquals(RtmpServerSession.State.CLOSED, client.session.state)
    }

    @Test
    fun closeFromAnEventStopsTheRestOfTheFeed() {
        val client = Client()
        val publish = command("publish", 3, null, listOf(AsValue.Str("key-1")), streamId = 1)
        lateinit var session: RtmpServerSession
        val events = object : RtmpServerSession.Events by client.events {
            override fun onPublish(stream: RtmpServerStream) {
                client.events.onPublish(stream)
                session.close("Rejected")
            }
        }
        var sends = 0
        session = RtmpServerSession({ listOf(stream1) }, RtmpServerLimits(), clock, { sends += 1 }, events)
        session.feed(client.c0c1() + ByteArray(1536))
        session.feed(command("connect", 1, linkedMapOf("tcUrl" to AsValue.Str("rtmp://host/live"))))
        // Upstream would go on with the messages after the publish in the same read.
        val before = sends
        session.feed(publish + command("createStream", 4, null) + video(avcFrame(0), 0))
        assertEquals(listOf("Rejected"), client.events.closed)
        assertTrue(client.events.media.isEmpty())
        // Only the onStatus, sent before onPublish.
        assertEquals(before + 1, sends)
    }

    /** A session whose `send` closes it on the [failAt]th send after the handshake, like a failed socket write. */
    private fun closingOnSend(failAt: Int): Pair<RtmpServerSession, Recorder> {
        val events = Recorder()
        lateinit var session: RtmpServerSession
        var sends = 0
        session = RtmpServerSession({ listOf(stream1) }, RtmpServerLimits(), clock, {
            sends += 1
            // Send 1 is the handshake.
            if (sends == failAt + 1) {
                session.close("Write failed")
            }
        }, events)
        session.feed(byteArrayOf(3) + ByteArray(1536) + ByteArray(1536))
        return session to events
    }

    private fun connectBytes() = command("connect", 1, linkedMapOf("tcUrl" to AsValue.Str("rtmp://host/live")))

    private fun publishBytes() = command("publish", 3, null, listOf(AsValue.Str("key-1")), streamId = 1)

    @Test
    fun closeFromSendDuringConnectStaysClosed() {
        for (failAt in 1..4) {
            val (session, events) = closingOnSend(failAt)
            session.feed(connectBytes() + publishBytes())
            session.feed(publishBytes())
            session.close("Again")
            assertEquals(listOf("Write failed"), events.closed, "send $failAt")
            assertEquals(RtmpServerSession.State.CLOSED, session.state)
            assertTrue(events.published.isEmpty())
        }
    }

    @Test
    fun closeFromSendDuringOnStatusStaysClosed() {
        // Sends after the handshake: 4 connect replies, then onStatus.
        val (session, events) = closingOnSend(5)
        session.feed(connectBytes())
        session.feed(publishBytes() + video(avcFrame(0), 0))
        session.feed(video(avcFrame(0), 10))
        session.close("Again")
        assertEquals(listOf("Write failed"), events.closed)
        assertEquals(RtmpServerSession.State.CLOSED, session.state)
        assertTrue(events.published.isEmpty())
        assertTrue(events.media.isEmpty())
        assertNull(session.stream)
    }

    @Test
    fun typeThreeHeaderStartsAMessageWithTheLastDelta() {
        val client = Client()
        client.publish("key-1")
        val frame = avcFrame(0)
        client.feed(video(frame, 100))
        client.feed(video(frame, 33, chunkType = 1))
        // A new message with only a type-3 basic header: same length, type and delta.
        client.feed(byteArrayOf((0xC0 or 9).toByte()) + frame)
        val dts = client.events.media.map { (it as RtmpIngestMedia.VideoFrame).dtsMs }
        assertEquals(listOf(0L, 33L, 66L), dts.map { it - dts[0] })
    }

    @Test
    fun closeStopsEverything() {
        val client = Client()
        client.publish("key-1")
        client.session.close("Same stream key")
        val sent = client.sends.size
        client.feed(video(avcFrame(0), 0))
        assertEquals(listOf("Same stream key"), client.events.closed)
        assertTrue(client.events.media.isEmpty())
        assertEquals(sent, client.sends.size)
    }
}
