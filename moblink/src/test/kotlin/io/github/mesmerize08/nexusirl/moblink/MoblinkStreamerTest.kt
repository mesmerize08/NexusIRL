package io.github.mesmerize08.nexusirl.moblink

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.remotecontrol.remoteControlHashPassword
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.java_websocket.framing.CloseFrame
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MoblinkStreamerTest {
    private class Cellular : BondingInterface {
        override val id = "cellular"
        override val type = InterfaceType.CELLULAR
        override val name = "rmnet0"

        override fun bind(socket: DatagramSocket) = Unit
    }

    private class Events(private val onRemoved: () -> Unit = {}) : MoblinkStreamerListener {
        val added = LinkedBlockingQueue<MoblinkTunnel>()
        val removed = LinkedBlockingQueue<MoblinkTunnel>()
        val changed = LinkedBlockingQueue<Unit>()

        override fun tunnelAdded(tunnel: MoblinkTunnel) {
            added += tunnel
        }

        override fun tunnelRemoved(tunnel: MoblinkTunnel) {
            removed += tunnel
            onRemoved()
        }

        override fun relaysChanged() {
            changed += Unit
        }
    }

    /** Our streamer and our relay together: the relay tunnels SRTLA from the streamer to the server. */
    @Test
    fun streamerAndRelay() {
        val port = ServerSocket(0).use { it.localPort }
        val events = Events()
        val streamer = MoblinkStreamer(port, "1234", events).also { it.start() }
        val server = DatagramSocket(0).apply { soTimeout = 5000 }
        val relayId = UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11")
        val relay = MoblinkRelay(
            relayId,
            "Pixel",
            "ws://127.0.0.1:$port",
            "1234",
            { MoblinkResponse.Status(42, MoblinkThermalState.YELLOW) },
            ReconnectingWebSocket.LOCAL_NETWORK_CLIENT,
        ) {}
        try {
            Thread.sleep(300)
            relay.setInterfaces(listOf(Cellular()))
            // Going live with SRTLA: tunnels to the server.
            streamer.startTunnels("127.0.0.1", server.localPort)
            val tunnel = assertNotNull(events.added.poll(10, TimeUnit.SECONDS))
            assertEquals(relayId, tunnel.relayId)
            assertEquals("Pixel", tunnel.relayName)
            assertEquals("127.0.0.1", tunnel.host)
            DatagramSocket(0).use { media ->
                media.soTimeout = 5000
                media.send(DatagramPacket("srtla".toByteArray(), 5, InetSocketAddress(tunnel.host, tunnel.port)))
                val received = DatagramPacket(ByteArray(100), 100)
                server.receive(received)
                assertEquals("srtla", String(received.data, 0, received.length))
                server.send(DatagramPacket("ack".toByteArray(), 3, received.socketAddress))
                val reply = DatagramPacket(ByteArray(100), 100)
                media.receive(reply)
                assertEquals("ack", String(reply.data, 0, reply.length))
            }
            // The relay's battery and thermal state arrive after identifying.
            val deadline = System.currentTimeMillis() + 5000
            while (streamer.statuses().firstOrNull()?.batteryPercentage == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(listOf(MoblinkRelayStatus("Pixel", 42, MoblinkThermalState.YELLOW)), streamer.statuses())
            // Stopping the stream removes the tunnel.
            streamer.stopTunnels()
            assertEquals(tunnel, events.removed.poll(5, TimeUnit.SECONDS))
        } finally {
            relay.stop()
            streamer.stop()
            server.close()
        }
    }

    @Test
    fun delayedTunnelReplyCannotReplaceCurrentDestination() {
        val port = ServerSocket(0).use { it.localPort }
        val events = Events()
        val streamer = MoblinkStreamer(port, "1234", events).also { it.start() }
        val requests = LinkedBlockingQueue<MoblinkMessageToRelay.Request>()
        val relay = connectRelay(port, requests)
        try {
            streamer.startTunnels("127.0.0.1", 15000)
            fun nextTunnel(): MoblinkMessageToRelay.Request {
                while (true) {
                    val request = assertNotNull(requests.poll(5, TimeUnit.SECONDS))
                    if (request.data is MoblinkRequest.StartTunnel) return request
                }
            }
            val old = nextTunnel()
            streamer.stopTunnels()
            streamer.startTunnels("127.0.0.1", 15001)
            val current = nextTunnel()

            // The current reply follows the stale reply on the same socket.
            replyToTunnel(relay, old, 16000)
            replyToTunnel(relay, current, 16001)
            assertEquals(16001, assertNotNull(events.added.poll(5, TimeUnit.SECONDS)).port)
            streamer.stopTunnels()
            assertEquals(16001, assertNotNull(events.removed.poll(5, TimeUnit.SECONDS)).port)
        } finally {
            relay.cancel()
            streamer.stop()
        }
    }

    @Test
    fun stoppingFromRemovalCallbackCancelsReplacement() {
        val port = ServerSocket(0).use { it.localPort }
        lateinit var streamer: MoblinkStreamer
        val events = Events { streamer.stopTunnels() }
        streamer = MoblinkStreamer(port, "1234", events).also { it.start() }
        val requests = LinkedBlockingQueue<MoblinkMessageToRelay.Request>()
        // Set the destination before identifying so the first request is the tunnel.
        streamer.startTunnels("127.0.0.1", 15000)
        val relay = connectRelay(port, requests)
        try {
            val initial = assertNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertEquals(MoblinkRequest.StartTunnel("127.0.0.1", 15000), initial.data)
            replyToTunnel(relay, initial, 16000)
            val tunnel = assertNotNull(events.added.poll(5, TimeUnit.SECONDS))
            assertEquals(MoblinkRequest.Status, requests.poll(5, TimeUnit.SECONDS)?.data)

            streamer.startTunnels("127.0.0.1", 15001)
            assertEquals(tunnel, events.removed.poll(5, TimeUnit.SECONDS))
            // This request gives us a barrier on the same socket. Any replacement
            // sent after the removal callback would arrive before the status.
            streamer.updateStatus()
            assertEquals(MoblinkRequest.Status, requests.poll(5, TimeUnit.SECONDS)?.data)
        } finally {
            relay.cancel()
            streamer.stop()
        }
    }

    private fun connectRelay(port: Int, requests: LinkedBlockingQueue<MoblinkMessageToRelay.Request>): WebSocket {
        Thread.sleep(300)
        val relayId = UUID.randomUUID()
        return ReconnectingWebSocket.LOCAL_NETWORK_CLIENT.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val message = MoblinkMessageToRelay.fromJson(text)) {
                        is MoblinkMessageToRelay.Hello -> {
                            val auth = message.authentication
                            val hash = remoteControlHashPassword(auth.challenge, auth.salt, "1234")
                            webSocket.send(MoblinkMessageToStreamer.Identify(relayId, "Relay", hash).toJson())
                        }
                        is MoblinkMessageToRelay.Request -> requests += message
                        else -> Unit
                    }
                }
            },
        )
    }

    private fun replyToTunnel(relay: WebSocket, request: MoblinkMessageToRelay.Request, port: Int) {
        relay.send(MoblinkMessageToStreamer.Response(request.id, MoblinkResult.OK, MoblinkResponse.StartTunnel(port)).toJson())
    }

    @Test
    fun wrongPasswordLocksOut() {
        val port = ServerSocket(0).use { it.localPort }
        var now = 0L
        val events = Events()
        val streamer = MoblinkStreamer(port, "1234", events, clock = { now }).also { it.start() }
        val states = LinkedBlockingQueue<MoblinkRelayState>()
        val relay = MoblinkRelay(
            UUID.randomUUID(),
            "Wrong",
            "ws://127.0.0.1:$port",
            "0000",
            { MoblinkResponse.Status() },
            ReconnectingWebSocket.LOCAL_NETWORK_CLIENT,
        ) { states += it }
        try {
            Thread.sleep(300)
            relay.setInterfaces(listOf(Cellular()))
            var state: MoblinkRelayState? = null
            val deadline = System.currentTimeMillis() + 10_000
            while (state != MoblinkRelayState.WRONG_PASSWORD && System.currentTimeMillis() < deadline) {
                state = states.poll(1, TimeUnit.SECONDS)
            }
            assertEquals(MoblinkRelayState.WRONG_PASSWORD, state)
            assertEquals(emptyList(), streamer.statuses())
        } finally {
            relay.stop()
            streamer.stop()
        }
    }

    @Test
    fun oversizedMessagesCloseTheConnection() {
        val port = ServerSocket(0).use { it.localPort }
        val streamer = MoblinkStreamer(port, "1234", Events(), bindAddress = InetAddress.getLoopbackAddress()).also { it.start() }
        val closed = LinkedBlockingQueue<Int>()
        try {
            Thread.sleep(300)
            ReconnectingWebSocket.LOCAL_NETWORK_CLIENT.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$port").build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Real messages are a few hundred bytes.
                        webSocket.send("x".repeat(MoblinkStreamer.MAXIMUM_MESSAGE_SIZE + 1))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        closed += code
                    }
                },
            )
            assertEquals(CloseFrame.TOOBIG, closed.poll(5, TimeUnit.SECONDS))
        } finally {
            streamer.stop()
        }
    }
}
