package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteControlAssistantTest {
    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class Events : RemoteControlAssistantDelegate {
        val queue = LinkedBlockingQueue<String>()

        override fun connected() {
            queue += "connected"
        }

        override fun disconnected() {
            queue += "disconnected"
        }

        override fun stateChanged(state: RemoteControlAssistantStreamerState) {
            queue += "state torch=${state.torchOn}"
        }

        override fun preview(jpeg: ByteArray) {
            queue += "preview ${jpeg.size}"
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    /** Both of our ends talking to each other, as an Android assistant and an Android streamer. */
    @Test
    fun assistantAndStreamer() {
        val port = freePort()
        val events = Events()
        val assistant = RemoteControlAssistant(port, "secret", events)
        val torch = LinkedBlockingQueue<Boolean>()
        val streamer = RemoteControlStreamer(
            "ws://127.0.0.1:$port",
            "secret",
            "S",
            object : RemoteControlStreamerDelegate {
                override fun getStatus() = RemoteControlResponse.GetStatus(
                    topLeft = RemoteControlStatusTopLeft(stream = RemoteControlStatusItem("IRL")),
                    topRight = RemoteControlStatusTopRight(),
                )

                override fun getSettings() = RemoteControlSettings(emptyList(), emptyList())

                override fun setTorch(on: Boolean) {
                    torch += on
                }
            },
        )
        try {
            assistant.start()
            streamer.start()
            assertEquals("connected", events.queue.poll(10, TimeUnit.SECONDS))
            waitUntil { streamer.isConnected }
            val results = LinkedBlockingQueue<Pair<RemoteControlResult, RemoteControlResponse?>>()
            assistant.request(RemoteControlRequest.GetStatus) { result, data -> results += result to data }
            val (result, data) = assertNotNull(results.poll(5, TimeUnit.SECONDS))
            assertEquals(RemoteControlResult.OK, result)
            assertEquals("IRL", (data as RemoteControlResponse.GetStatus).topLeft.stream?.message)
            assistant.request(RemoteControlRequest.SetTorch(true)) { r, _ -> results += r to null }
            assertEquals(true, torch.poll(5, TimeUnit.SECONDS))
            assertEquals(RemoteControlResult.OK, results.poll(5, TimeUnit.SECONDS)?.first)
            streamer.stateChanged(RemoteControlAssistantStreamerState(torchOn = true))
            assertEquals("state torch=true", events.queue.poll(5, TimeUnit.SECONDS))
            streamer.sendPreview(ByteArray(7))
            assertEquals("preview 7", events.queue.poll(5, TimeUnit.SECONDS))
        } finally {
            streamer.stop()
            assistant.stop()
        }
    }

    private fun connectRaw(port: Int, onText: (WebSocket, String) -> Unit): LinkedBlockingQueue<String> {
        val closed = LinkedBlockingQueue<String>()
        ReconnectingWebSocket.SHARED_CLIENT.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onText(webSocket, text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed += "closed"
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed += "closed"
                }
            },
        )
        return closed
    }

    @Test
    fun wrongPasswordLockout() {
        val port = freePort()
        var now = 0L
        val assistant = RemoteControlAssistant(port, "secret", Events(), clock = { now })
        try {
            assistant.start()
            Thread.sleep(300)
            // Five wrong passwords: each gets wrongPassword and is closed.
            repeat(RemoteControlAssistant.MAXIMUM_FAILURES) {
                val replies = LinkedBlockingQueue<String>()
                val closed = connectRaw(port) { socket, text ->
                    replies += text
                    if (text.startsWith("{\"hello\"")) {
                        socket.send("""{"identify":{"authentication":"wrong"}}""")
                    }
                }
                assertTrue(replies.poll(5, TimeUnit.SECONDS)!!.startsWith("{\"hello\""))
                assertEquals("""{"identified":{"result":{"wrongPassword":{}}}}""", replies.poll(5, TimeUnit.SECONDS))
                assertEquals("closed", closed.poll(5, TimeUnit.SECONDS))
            }
            // Locked out: closed at once, without a challenge.
            val replies = LinkedBlockingQueue<String>()
            val closed = connectRaw(port) { _, text -> replies += text }
            assertEquals("closed", closed.poll(5, TimeUnit.SECONDS))
            assertNull(replies.poll(200, TimeUnit.MILLISECONDS))
            // After a minute it is allowed again.
            now += RemoteControlAssistant.LOCKOUT_MILLIS
            val again = LinkedBlockingQueue<String>()
            connectRaw(port) { _, text -> again += text }
            assertTrue(again.poll(5, TimeUnit.SECONDS)!!.startsWith("{\"hello\""))
        } finally {
            assistant.stop()
        }
    }

    @Test
    fun onlyAllowedInterfaces() {
        val port = freePort()
        val assistant = RemoteControlAssistant(port, "secret", Events(), acceptLocalAddress = { false })
        try {
            assistant.start()
            Thread.sleep(300)
            val replies = LinkedBlockingQueue<String>()
            val closed = connectRaw(port) { _, text -> replies += text }
            assertEquals("closed", closed.poll(5, TimeUnit.SECONDS))
            assertNull(replies.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            assistant.stop()
        }
    }
}
