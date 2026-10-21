package io.github.mesmerize08.nexusirl.transport.srt

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Test-only UDP relay that emulates a congested uplink: client -> server packets pass a bounded
 * queue drained at [rateBitsPerSecond] (tail drop when full); server -> client is forwarded
 * immediately. The rate can be changed while running.
 */
class ShapingUdpRelay(private val serverPort: Int, queuePackets: Int = 150) : AutoCloseable {
    private val clientSide = DatagramSocket(0)
    private val serverSide = DatagramSocket(0)
    private val queue = ArrayBlockingQueue<ByteArray>(queuePackets)
    private val server = InetSocketAddress("127.0.0.1", serverPort)

    @Volatile
    private var client: SocketAddress? = null

    @Volatile
    var rateBitsPerSecond: Long = 100_000_000

    @Volatile
    private var running = true
    val droppedPackets = AtomicLong()

    val port: Int
        get() = clientSide.localPort

    init {
        thread("relay-in") {
            val buffer = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    clientSide.receive(packet)
                } catch (e: SocketException) {
                    return@thread
                }
                client = packet.socketAddress
                if (!queue.offer(packet.data.copyOf(packet.length))) {
                    droppedPackets.incrementAndGet()
                }
            }
        }
        thread("relay-pacer") {
            var nextSendNanos = System.nanoTime()
            while (running) {
                val data = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val now = System.nanoTime()
                if (nextSendNanos > now) {
                    val wait = nextSendNanos - now
                    Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                } else if (now - nextSendNanos > MAXIMUM_LAG_NANOS) {
                    // Idle for a while: no burst to "catch up". Smaller lags are caught up below, so
                    // the rate holds even when sleeps overshoot (Windows timers can be ~15.6 ms
                    // coarse, depending on what other programs request).
                    nextSendNanos = now
                }
                // Account for IP/UDP overhead like a real link.
                nextSendNanos += (data.size + 28) * 8L * 1_000_000_000L / rateBitsPerSecond
                try {
                    serverSide.send(DatagramPacket(data, data.size, server))
                } catch (e: SocketException) {
                    return@thread
                }
            }
        }
        thread("relay-out") {
            val buffer = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    serverSide.receive(packet)
                } catch (e: SocketException) {
                    return@thread
                }
                val target = client ?: continue
                try {
                    clientSide.send(DatagramPacket(packet.data, packet.length, target))
                } catch (e: SocketException) {
                    return@thread
                }
            }
        }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread(body, name).apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running = false
        clientSide.close()
        serverSide.close()
    }

    private companion object {
        const val MAXIMUM_LAG_NANOS = 50_000_000L
    }
}
