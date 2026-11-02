package io.github.mesmerize08.nexusirl.transport.rtmp

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A server that accepts connections and never says anything, so a TLS handshake never ends. */
class RtmpSocketTest {
    private class SilentServer : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val accepted = LinkedBlockingQueue<Socket>()
        private val clients: MutableList<Socket> = Collections.synchronizedList(ArrayList())

        init {
            thread(isDaemon = true, name = "silent-server") {
                while (true) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    clients += socket
                    accepted += socket
                }
            }
        }

        override fun close() {
            server.close()
            synchronized(clients) { clients.forEach { it.close() } }
        }
    }

    private fun ioThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "rtmp-io" }

    @Test
    fun tlsHandshakeWithASilentServerTimesOut() {
        SilentServer().use { server ->
            val opened = LinkedBlockingQueue<Socket>()
            val attempt = Executors.newSingleThreadExecutor().submit<Throwable?> {
                runCatching { RtmpSocketFactory.standard(500).connect("127.0.0.1", server.server.localPort, true) { opened += it } }
                    .exceptionOrNull()
            }
            val error = try {
                attempt.get(5, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                attempt.cancel(true)
                null
            }
            assertTrue(error is java.io.IOException, "the handshake gives up: $error")
            assertTrue(opened.poll()!!.isClosed, "the socket is closed")
        }
    }

    @Test
    fun disconnectEndsAConnectionStuckInTheTlsHandshake() {
        val before = ioThreads()
        SilentServer().use { server ->
            val executor = SerialExecutor.create("rtmp-socket-test")
            val statuses = LinkedBlockingQueue<String>()
            val stream = RtmpStream(
                "rtmps://127.0.0.1:${server.server.localPort}/live/key",
                executor,
                HostClock.system,
                object : RtmpStream.Listener {
                    override fun onStatus(code: String) {
                        statuses += code
                    }

                    override fun onConnected() = Unit
                },
                // Far longer than the test: only closing the socket can end the handshake.
                RtmpSocketFactory.standard(60_000),
            )
            stream.connect()
            val client = server.accepted.poll(5, TimeUnit.SECONDS)
            assertTrue(client != null, "connected")
            Thread.sleep(200)
            assertEquals(before + 1, ioThreads())
            stream.disconnect()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (ioThreads() > before && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(before, ioThreads(), "the connection thread ended")
            // Our end is closed: after the ClientHello comes the end of the stream or a reset, never a timeout.
            client!!.soTimeout = 3_000
            val read = runCatching { client.getInputStream().readBytes() }
            assertTrue(read.isSuccess || read.exceptionOrNull() !is java.net.SocketTimeoutException, "closed: $read")
        }
    }
}
