package io.github.mesmerize08.nexusirl.transport.rtmp.server

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.transport.rtmp.AsObject
import io.github.mesmerize08.nexusirl.transport.rtmp.AsValue
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpCommand
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessage
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMessageType
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpStream
import io.github.mesmerize08.nexusirl.transport.rtmp.encodeChunks
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RtmpServerTest {
    private val stream = RtmpServerStream(UUID.randomUUID(), "My stream", KEY, 2_000)

    private class Recorder : RtmpServerListener {
        val events = LinkedBlockingQueue<String>()
        val media: MutableList<RtmpIngestMedia> = Collections.synchronizedList(ArrayList())
        val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())

        override fun onPublishStart(streamId: UUID) {
            threads += Thread.currentThread().name
            events += "start $streamId"
        }

        override fun onPublishStop(streamId: UUID, reason: String) {
            threads += Thread.currentThread().name
            events += "stop $streamId $reason"
        }

        override fun onMedia(streamId: UUID, media: RtmpIngestMedia) {
            threads += Thread.currentThread().name
            this.media += media
        }

        fun next(timeoutMs: Long = 5_000): String = events.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: fail("no listener event")

        fun videoFrames() = synchronized(media) { media.filterIsInstance<RtmpIngestMedia.VideoFrame>() }
    }

    private val recorder = Recorder()
    private val logLines: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun <T> withServer(
        limits: RtmpServerLimits = RtmpServerLimits(),
        acceptPeer: (InetAddress, InetAddress) -> Boolean = { _, _ -> true },
        body: (RtmpServer) -> T,
    ): T {
        val server = RtmpServer(RtmpServerSettings(0, listOf(stream)), recorder, acceptPeer, limits, HostClock.system) { logLines += it }
        server.start()
        try {
            return body(server)
        } finally {
            server.stop()
        }
    }

    /** A hand-made client: exact bytes in, and how the server ends the connection. */
    private class RawClient(port: Int) : AutoCloseable {
        val socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
        }
        private val output = socket.getOutputStream()

        fun write(data: ByteArray) = output.write(data)

        fun publish(key: String) = write(
            byteArrayOf(3) + ByteArray(1536) { it.toByte() } + ByteArray(1536) +
                command("connect", 1, linkedMapOf("app" to AsValue.Str("live"), "tcUrl" to AsValue.Str("rtmp://127.0.0.1/live"))) +
                command("createStream", 2, null) +
                command("publish", 3, null, listOf(AsValue.Str(key), AsValue.Str("live")), streamId = 1),
        )

        /**
         * Reads until the server ends the connection (end of stream or a reset) and returns how
         * many bytes came first, or null if it is still open after [timeoutMs].
         */
        fun bytesUntilClosed(timeoutMs: Long): Int? {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) {
                    return null
                }
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val count = try {
                    socket.getInputStream().read(buffer)
                } catch (e: SocketTimeoutException) {
                    return null
                } catch (e: IOException) {
                    return total
                }
                if (count < 0) {
                    return total
                }
                total += count
            }
        }

        fun firstByte(timeoutMs: Int): Int {
            socket.soTimeout = timeoutMs
            return socket.getInputStream().read()
        }

        override fun close() = socket.close()

        private fun command(
            name: String,
            transactionId: Long,
            commandObject: AsObject?,
            arguments: List<AsValue> = emptyList(),
            streamId: Long = 0,
        ): ByteArray {
            val payload = RtmpCommand(name, transactionId, commandObject, arguments).encode()
            return encodeChunks(0, 3, RtmpMessage(RtmpMessageType.AMF0_COMMAND, streamId, 0, payload), 128).reduce { a, b -> a + b }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                return false
            }
            Thread.sleep(20)
        }
        return true
    }

    private fun serverThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("rtmp-server") }

    @Test
    fun rtmpStreamPublishesAndMediaArrives() = withServer { server ->
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val aac = byteArrayOf(0x21, 0x10, 0x05, 0x40, 0x01)
        val executor = SerialExecutor.create("test-rtmp-publisher")
        val connected = CountDownLatch(1)
        val client = RtmpStream(
            "rtmp://127.0.0.1:${server.port}/live/$KEY",
            executor,
            HostClock.system,
            object : RtmpStream.Listener {
                override fun onStatus(code: String) = Unit

                override fun onConnected() = connected.countDown()
            },
        )
        try {
            client.setVideoConfig(MpegTsVideoConfig.Avc(sps, pps))
            client.setAudioConfig(MpegTsAudioConfig.aacLc(48_000, 2), 48_000)
            client.connect()
            assertTrue(connected.await(5, TimeUnit.SECONDS), "publishing")
            assertEquals("start ${stream.id}", recorder.next())
            assertEquals(setOf(stream.id), server.connectedStreamIds())
            assertEquals(1, server.numberOfClients())
            repeat(30) { i ->
                val pts = MediaTime.fromSeconds(10.0 + i / 30.0)
                val nal = byteArrayOf(if (i == 0) 0x65 else 0x41, i.toByte(), 1, 2)
                client.writeVideo(byteArrayOf(0, 0, 0, 1) + nal, pts, null, i == 0)
                client.writeAudio(aac, 0, aac.size, pts)
            }
            assertTrue(waitUntil(5_000) { recorder.videoFrames().size == 30 }, "30 frames, got ${recorder.videoFrames().size}")
            val frames = recorder.videoFrames()
            frames.forEachIndexed { i, frame ->
                assertContentEquals(byteArrayOf(0, 0, 0, 4, if (i == 0) 0x65 else 0x41, i.toByte(), 1, 2), frame.data)
                assertEquals(i == 0, frame.isKeyFrame)
            }
            assertTrue(frames.zipWithNext().all { (a, b) -> b.dtsMs > a.dtsMs }, "dts increases")
            val media = synchronized(recorder.media) { recorder.media.toList() }
            val videoConfig = media.filterIsInstance<RtmpIngestMedia.VideoConfig>().single()
            assertEquals(IngestVideoCodec.H264, videoConfig.codec)
            assertContentEquals(
                MpegTsAudioConfig.aacLc(48_000, 2).encode(),
                media.filterIsInstance<RtmpIngestMedia.AudioConfig>().single().audioSpecificConfig,
            )
            val audio = media.filterIsInstance<RtmpIngestMedia.AudioFrame>()
            assertTrue(audio.isNotEmpty(), "audio frames")
            audio.forEach { assertContentEquals(aac, it.data) }
            client.disconnect()
            assertTrue(recorder.next().startsWith("stop ${stream.id} "))
            assertTrue(waitUntil(2_000) { server.numberOfClients() == 0 && server.connectedStreamIds().isEmpty() })
            assertEquals(setOf("rtmp-server"), recorder.threads.toSet(), "one listener thread")
        } finally {
            client.disconnect()
            executor.shutdown()
        }
    }

    @Test
    fun wrongKeyIsRefused() = withServer { server ->
        val executor = SerialExecutor.create("test-rtmp-publisher")
        val connected = CountDownLatch(1)
        val client = RtmpStream(
            "rtmp://127.0.0.1:${server.port}/live/nope",
            executor,
            HostClock.system,
            object : RtmpStream.Listener {
                override fun onStatus(code: String) = Unit

                override fun onConnected() = connected.countDown()
            },
        )
        try {
            client.connect()
            assertTrue(!connected.await(1_500, TimeUnit.MILLISECONDS), "never publishing")
            assertNull(recorder.events.poll())
            assertTrue(server.connectedStreamIds().isEmpty())
            assertTrue(waitUntil(2_000) { server.numberOfClients() == 0 })
        } finally {
            client.disconnect()
            executor.shutdown()
        }
    }

    @Test
    fun lockoutAfterFiveWrongKeys() = withServer(RtmpServerLimits(lockoutMs = 1_000)) { server ->
        var lastFailure = 0L
        repeat(5) {
            RawClient(server.port).use { client ->
                client.publish("wrong")
                val bytes = client.bytesUntilClosed(3_000)
                assertTrue(bytes != null && bytes > 0, "a handshake, then closed: $bytes")
            }
            lastFailure = System.nanoTime()
        }
        RawClient(server.port).use { client ->
            assertEquals(0, client.bytesUntilClosed(500), "closed before S0")
        }
        assertTrue((System.nanoTime() - lastFailure) / 1_000_000 < 1_000, "checked inside the lockout")
        Thread.sleep(maxOf(0, 1_200 - (System.nanoTime() - lastFailure) / 1_000_000))
        RawClient(server.port).use { client ->
            client.write(byteArrayOf(3) + ByteArray(1536))
            assertEquals(3, client.firstByte(3_000), "S0 again")
        }
        assertTrue(logLines.isNotEmpty())
    }

    @Test
    fun sameKeyNewestWins() = withServer { server ->
        RawClient(server.port).use { first ->
            first.publish(KEY)
            assertEquals("start ${stream.id}", recorder.next())
            RawClient(server.port).use { second ->
                second.publish(KEY)
                assertEquals("stop ${stream.id} Same stream key", recorder.next())
                assertEquals("start ${stream.id}", recorder.next())
                assertTrue(first.bytesUntilClosed(3_000) != null, "the first is disconnected")
                assertTrue(waitUntil(2_000) { server.numberOfClients() == 1 })
                assertEquals(setOf(stream.id), server.connectedStreamIds())
                assertEquals(null, second.bytesUntilClosed(300), "the second stays")
                assertNull(recorder.events.poll(200, TimeUnit.MILLISECONDS), "no stop for the replaced client")
            }
        }
    }

    @Test
    fun silentClientTimesOut() = withServer(RtmpServerLimits(handshakeTimeoutMs = 500, readTimeoutMs = 60_000)) { server ->
        RawClient(server.port).use { client ->
            val started = System.nanoTime()
            assertEquals(0, client.bytesUntilClosed(4_000), "closed by housekeeping")
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 500, "not before the deadline")
            assertTrue(waitUntil(1_000) { server.numberOfClients() == 0 })
        }
        assertTrue(logLines.any { "Handshake timeout" in it }, "$logLines")
    }

    @Test
    fun slowlorisTimesOut() = withServer(RtmpServerLimits(handshakeTimeoutMs = 1_000)) { server ->
        RawClient(server.port).use { client ->
            val deadline = System.nanoTime() + 5_000_000_000L
            var closed = false
            var sent = 0
            while (!closed && System.nanoTime() < deadline) {
                try {
                    client.write(byteArrayOf(if (sent == 0) 3 else 0))
                    sent += 1
                } catch (e: IOException) {
                    closed = true
                    break
                }
                closed = client.bytesUntilClosed(300) != null
            }
            assertTrue(closed, "closed within 5 s after $sent bytes")
        }
        assertTrue(logLines.any { "Handshake timeout" in it }, "$logLines")
    }

    @Test
    fun unauthenticatedLimit() = withServer(RtmpServerLimits(maxUnauthenticated = 2)) { server ->
        RawClient(server.port).use { publisher ->
            publisher.publish(KEY)
            assertEquals("start ${stream.id}", recorder.next())
            RawClient(server.port).use { idle1 ->
                RawClient(server.port).use { idle2 ->
                    assertTrue(waitUntil(2_000) { server.numberOfClients() == 3 }, "the publisher does not count")
                    RawClient(server.port).use { third ->
                        assertEquals(0, third.bytesUntilClosed(2_000), "the third idle connection is closed at once")
                    }
                    assertEquals(null, idle1.bytesUntilClosed(200), "still open")
                    assertEquals(null, idle2.bytesUntilClosed(200), "still open")
                    assertEquals(null, publisher.bytesUntilClosed(200), "still open")
                }
            }
        }
    }

    @Test
    fun refusedPeerIsClosed() {
        val peers = LinkedBlockingQueue<Pair<InetAddress, InetAddress>>()
        withServer(acceptPeer = { local, remote ->
            peers += local to remote
            false
        }) { server ->
            RawClient(server.port).use { client ->
                client.write(byteArrayOf(3) + ByteArray(1536))
                assertEquals(0, client.bytesUntilClosed(2_000), "closed without S0")
            }
            val (local, remote) = peers.poll(1, TimeUnit.SECONDS) ?: fail("acceptPeer not called")
            assertTrue(local.isLoopbackAddress && remote.isLoopbackAddress, "$local $remote")
            assertTrue(waitUntil(1_000) { server.numberOfClients() == 0 })
        }
    }

    @Test
    fun stopClosesEverythingAndThreadsEnd() {
        val server = RtmpServer(RtmpServerSettings(0, listOf(stream)), recorder, { _, _ -> true }, RtmpServerLimits(), HostClock.system)
        server.start()
        try {
            RawClient(server.port).use { publisher ->
                RawClient(server.port).use { idle ->
                    publisher.publish(KEY)
                    assertEquals("start ${stream.id}", recorder.next())
                    assertTrue(serverThreads().isNotEmpty())
                    server.stop()
                    assertEquals("stop ${stream.id} Server stop", recorder.next())
                    assertTrue(publisher.bytesUntilClosed(2_000) != null, "the publisher is closed")
                    assertEquals(0, idle.bytesUntilClosed(2_000), "the idle client is closed")
                }
            }
            assertTrue(waitUntil(2_000) { serverThreads().isEmpty() }, "threads still alive: ${serverThreads().map { it.name }}")
            assertNull(recorder.events.poll(200, TimeUnit.MILLISECONDS), "one stop only")
        } finally {
            server.stop()
        }
    }

    @Test
    fun keyNeverLogged() = withServer { server ->
        RawClient(server.port).use { client ->
            client.publish(WRONG_KEY)
            assertTrue(client.bytesUntilClosed(3_000) != null)
        }
        RawClient(server.port).use { client ->
            client.publish(KEY)
            assertEquals("start ${stream.id}", recorder.next())
        }
        assertTrue(recorder.next().startsWith("stop ${stream.id} "))
        server.stop()
        val lines = synchronized(logLines) { logLines.toList() }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { KEY in it || WRONG_KEY in it }, "$lines")
    }

    private companion object {
        const val KEY = "secret-key-4711"
        const val WRONG_KEY = "wrong-key-0815"
    }
}
