// Golden JSON from upstream MoblinTests/Moblin/Moblink/MoblinkSuite.swift @97e26d2 and Swift's
// Codable rules.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.moblink

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.remotecontrol.remoteControlHashPassword
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MoblinkTest {
    @Test
    fun decodeStatusResponseLikeUpstream() {
        val message = MoblinkMessageToStreamer.fromJson(
            """{"response":{"id":5,"data":{"status":{"thermalState":"white","batteryPercentage":30,"temperature": 39}},"result":{"ok":{}}}}""",
        )
        assertEquals(MoblinkMessageToStreamer.Response(5, MoblinkResult.OK, MoblinkResponse.Status(30, MoblinkThermalState.WHITE)), message)
    }

    @Test
    fun messages() {
        val id = UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11")
        assertEquals(
            """{"identify":{"id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","name":"Relay","authentication":"h"}}""",
            MoblinkMessageToStreamer.Identify(id, "Relay", "h").toJson(),
        )
        assertEquals(
            """{"response":{"id":2,"result":{"ok":{}},"data":{"startTunnel":{"port":5000}}}}""",
            MoblinkMessageToStreamer.Response(2, MoblinkResult.OK, MoblinkResponse.StartTunnel(5000)).toJson(),
        )
        assertEquals(
            MoblinkMessageToRelay.Request(3, MoblinkRequest.StartTunnel("1.2.3.4", 5000)),
            MoblinkMessageToRelay.fromJson("""{"request":{"id":3,"data":{"startTunnel":{"address":"1.2.3.4","port":5000}}}}"""),
        )
        assertEquals(
            MoblinkMessageToRelay.Request(4, MoblinkRequest.Unknown),
            MoblinkMessageToRelay.fromJson("""{"request":{"id":4,"data":{"somethingNew":{}}}}"""),
        )
    }

    @Test
    fun relayIdsLikeUpstream() {
        val id = UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11")
        // Reference values computed independently from upstream's UUID.add(data:).
        assertEquals("0B3B5C3E-6D5A-4D47-9D7B-6B7C82039241", moblinkRelayId(id, "eth0").toString().uppercase())
        assertEquals("38A4CAB2-D22D-E01E-6552-1117EA6BFDA8", moblinkRelayId(id, "a-very-long-interface-name0").toString().uppercase())
    }

    private class LoopbackInterface(override val type: InterfaceType) : BondingInterface {
        override val id = type.name
        override val name = "lo-${type.name.lowercase()}"

        override fun bind(socket: DatagramSocket) = Unit
    }

    /** A fake streamer: authenticates the relay, starts a tunnel to a local "server" and sends UDP through it. */
    @Test
    fun tunnelThroughRelay() {
        val fromRelay = LinkedBlockingQueue<String>()
        val streamerSockets = LinkedBlockingQueue<WebSocket>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    streamerSockets += webSocket
                    webSocket.send("""{"hello":{"apiVersion":"0.1","authentication":{"challenge":"c","salt":"s"}}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromRelay += text
                }
            }),
        )
        server.start()
        val destination = DatagramSocket(0)
        destination.soTimeout = 5000
        val states = LinkedBlockingQueue<MoblinkRelayState>()
        val relayId = UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11")
        val relay = MoblinkRelay(
            relayId,
            "Relay",
            server.url("/").toString().replace("http", "ws"),
            "1234",
            { MoblinkResponse.Status(55, MoblinkThermalState.YELLOW) },
            ReconnectingWebSocket.LOCAL_NETWORK_CLIENT,
        ) { states += it }
        try {
            relay.setInterfaces(listOf(LoopbackInterface(InterfaceType.CELLULAR), LoopbackInterface(InterfaceType.WIFI)))
            assertEquals(
                """{"identify":{"id":"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11","name":"Relay","authentication":"${remoteControlHashPassword(
                    "c",
                    "s",
                    "1234",
                )}"}}""",
                fromRelay.poll(5, TimeUnit.SECONDS),
            )
            val streamer = assertNotNull(streamerSockets.poll(5, TimeUnit.SECONDS))
            streamer.send("""{"identified":{"result":{"ok":{}}}}""")
            streamer.send("""{"request":{"id":1,"data":{"status":{}}}}""")
            assertEquals(
                """{"response":{"id":1,"result":{"ok":{}},"data":{"status":{"batteryPercentage":55,"thermalState":"yellow"}}}}""",
                fromRelay.poll(5, TimeUnit.SECONDS),
            )
            streamer.send("""{"request":{"id":2,"data":{"startTunnel":{"address":"127.0.0.1","port":${destination.localPort}}}}}""")
            val response = MoblinkMessageToStreamer.fromJson(fromRelay.poll(5, TimeUnit.SECONDS)!!) as MoblinkMessageToStreamer.Response
            val tunnelPort = (response.data as MoblinkResponse.StartTunnel).port
            // Streamer -> relay -> destination, and the reply back to the streamer.
            DatagramSocket(0).use { media ->
                media.soTimeout = 5000
                media.send(DatagramPacket("srt".toByteArray(), 3, InetSocketAddress("127.0.0.1", tunnelPort)))
                val received = DatagramPacket(ByteArray(100), 100)
                destination.receive(received)
                assertEquals("srt", String(received.data, 0, received.length))
                destination.send(DatagramPacket("ack".toByteArray(), 3, received.socketAddress))
                val reply = DatagramPacket(ByteArray(100), 100)
                media.receive(reply)
                assertEquals("ack", String(reply.data, 0, reply.length))
            }
            // Only cellular and Ethernet are destinations; Wi-Fi carries the control connection.
            assertEquals(MoblinkRelayState.CONNECTED, generateSequence { states.poll(100, TimeUnit.MILLISECONDS) }.last())
        } finally {
            relay.stop()
            destination.close()
            server.shutdown()
        }
    }
}
