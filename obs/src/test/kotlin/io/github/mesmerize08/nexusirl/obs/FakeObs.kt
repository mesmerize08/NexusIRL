package io.github.mesmerize08.nexusirl.obs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

/**
 * A fake obs-websocket v5 server on loopback: sends [hello] on connect, accepts the Identify
 * when [accept] says so (else closes with 4009 like OBS) and answers each request with
 * [respond] (requestType, requestData) -> `"requestStatus":...,"responseData":...` body parts, or no answer for null.
 */
class FakeObs(
    private val hello: String = """{"op":0,"d":{"obsWebSocketVersion":"5.5.2","rpcVersion":1}}""",
    private val accept: (JsonObject) -> Boolean = { true },
    private val respond: (type: String, data: JsonObject?) -> String? = { _, _ -> """"requestStatus":{"result":true,"code":100}""" },
) : AutoCloseable {
    /** Every message from the client, parsed. */
    val received = LinkedBlockingQueue<JsonObject>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val server = MockWebServer()

    @Volatile
    var socket: WebSocket? = null
        private set

    init {
        repeat(4) { server.enqueue(MockResponse().withWebSocketUpgrade(listener())) }
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    // The cleartext policy trusts only a literal loopback address, not the reverse-DNS name.
    val url: String = "ws://127.0.0.1:${server.port}"

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            sockets += webSocket
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            received += message
            val d = message.getValue("d").jsonObject
            when (message.getValue("op").jsonPrimitive.int) {
                1 -> if (accept(d)) {
                    webSocket.send("""{"op":2,"d":{"negotiatedRpcVersion":1}}""")
                } else {
                    webSocket.close(4009, "Authentication failed.")
                }
                6 -> {
                    val type = d.getValue("requestType").jsonPrimitive.content
                    val id = d.getValue("requestId").jsonPrimitive.content
                    val body = respond(type, d["requestData"]?.jsonObject) ?: return
                    webSocket.send("""{"op":7,"d":{"requestType":"$type","requestId":"$id",$body}}""")
                }
                8 -> {
                    val id = d.getValue("requestId").jsonPrimitive.content
                    val results = d.getValue("requests").let { it as kotlinx.serialization.json.JsonArray }.map {
                        val request = it.jsonObject
                        val type = request.getValue("requestType").jsonPrimitive.content
                        """{"requestType":"$type",${respond(type, request["requestData"]?.jsonObject)}}"""
                    }
                    webSocket.send("""{"op":9,"d":{"requestId":"$id","results":[${results.joinToString(",")}]}}""")
                }
            }
        }
    }

    /** Waits for the next client message with [op]. */
    fun next(op: Int): JsonObject {
        while (true) {
            val message = assertNotNull(received.poll(5, TimeUnit.SECONDS), "no message with op $op")
            if (message.getValue("op").jsonPrimitive.int == op) {
                return message.getValue("d").jsonObject
            }
        }
    }

    fun awaitSocket(): WebSocket = assertNotNull(sockets.poll(5, TimeUnit.SECONDS))

    fun sendEvent(type: String, data: String) {
        socket!!.send("""{"op":5,"d":{"eventType":"$type","eventIntent":1,"eventData":$data}}""")
    }

    override fun close() = server.shutdown()
}
