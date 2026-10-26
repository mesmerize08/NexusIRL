// Long golden JSON is clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.remotecontrol

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteControlWebTest {
    // Obviously fake, low-entropy value so secret scanners (gitleaks) do not flag the test.
    private val token = "a".repeat(32)

    /** Two free consecutive ports (HTTP and WebSocket). */
    private fun freePorts(): Int {
        repeat(50) {
            val port = ServerSocket(0).use { it.localPort }
            val free = runCatching { ServerSocket(port + 1).close() }.isSuccess
            if (free) {
                return port
            }
        }
        error("No free ports")
    }

    private fun get(port: Int, target: String, cookie: String? = null, method: String = "GET"): String {
        Socket("127.0.0.1", port).use { socket ->
            val request = "$method $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" + (cookie?.let { "Cookie: $it\r\n" } ?: "") + "\r\n"
            socket.getOutputStream().write(request.toByteArray())
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private val delegate = object : RemoteControlStreamerDelegate {
        override fun getStatus() = RemoteControlResponse.GetStatus(
            topLeft = RemoteControlStatusTopLeft(stream = RemoteControlStatusItem("IRL")),
            topRight = RemoteControlStatusTopRight(),
        )

        override fun getSettings() = RemoteControlSettings(emptyList(), emptyList())
    }

    private fun web(port: Int, previews: LinkedBlockingQueue<Boolean> = LinkedBlockingQueue(), allow: Boolean = true) = RemoteControlWeb(
        port,
        token,
        delegate,
        asset = { if (it == "index.html") "<html>remote</html>".toByteArray() else null },
        acceptLocalAddress = { allow },
        onPreviewWanted = { previews += it },
    )

    @Test
    fun recordings() {
        val files = linkedMapOf("NexusIRL 2026-10-26 13-00-00.mp4" to "video-bytes".toByteArray())
        val recordings = object : WebRecordings {
            override fun list() = files.map { (name, data) -> WebRecording(name, "${data.size} bytes") }

            override fun open(name: String) = files[name]?.let { it.size.toLong() to it.inputStream() }

            override fun thumbnail(name: String) = if (name in files) byteArrayOf(1, 2, 3) else null

            override fun delete(name: String) = files.remove(name) != null
        }
        val port = freePorts()
        val server = RemoteControlWeb(port, token, delegate, asset = { null }, acceptLocalAddress = { true }, recordings = recordings)
            .also { it.start() }
        val cookie = "nexusirl_token=$token"
        val encoded = "NexusIRL%202026-10-26%2013-00-00.mp4"
        try {
            assertTrue(get(port, "/recordings.json").startsWith("HTTP/1.1 401"), "needs the token")
            assertTrue(
                get(port, "/recordings.json", cookie).endsWith("""[{"name":"NexusIRL 2026-10-26 13-00-00.mp4","size":"11 bytes"}]"""),
            )
            val download = get(port, "/recordings/$encoded", cookie)
            assertTrue(download.startsWith("HTTP/1.1 200"), download)
            assertTrue("Content-Disposition: attachment; filename=\"NexusIRL 2026-10-26 13-00-00.mp4\"" in download)
            assertTrue(download.endsWith("video-bytes"))
            assertTrue(get(port, "/thumbnails/$encoded", cookie).contains("Content-Type: image/jpeg"))
            assertTrue(get(port, "/recordings/..%2F..%2Fsettings.json", cookie).startsWith("HTTP/1.1 404"))
            assertTrue(get(port, "/index.html", cookie, method = "DELETE").startsWith("HTTP/1.1 405"), "DELETE only for recordings")
            assertTrue(get(port, "/recordings/$encoded", cookie, method = "DELETE").startsWith("HTTP/1.1 200"))
            assertTrue(files.isEmpty())
            assertTrue(get(port, "/recordings/$encoded", cookie).startsWith("HTTP/1.1 404"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun aClientHangingUpMidDownloadDoesNotCrash() {
        val big = ByteArray(32 * 1024 * 1024)
        val recordings = object : WebRecordings {
            override fun list() = listOf(WebRecording("big.mp4", "32 MB"))

            override fun open(name: String) = big.size.toLong() to big.inputStream()

            override fun thumbnail(name: String): ByteArray? = null

            override fun delete(name: String) = false
        }
        val uncaught = LinkedBlockingQueue<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        val port = freePorts()
        val server = RemoteControlWeb(port, token, delegate, asset = { null }, acceptLocalAddress = { true }, recordings = recordings)
            .also { it.start() }
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.setSoLinger(true, 0)
                socket.getOutputStream().write("GET /recordings/big.mp4 HTTP/1.1\r\nCookie: nexusirl_token=$token\r\n\r\n".toByteArray())
                socket.getInputStream().read(ByteArray(1024))
            }
            Thread.sleep(500)
            assertTrue(get(port, "/recordings.json", "nexusirl_token=$token").startsWith("HTTP/1.1 200"), "still serving")
            assertEquals(null, uncaught.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            server.stop()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun httpNeedsTheToken() {
        val port = freePorts()
        val server = web(port).also { it.start() }
        try {
            assertTrue(get(port, "/").startsWith("HTTP/1.1 401"))
            assertTrue(get(port, "/?t=wrong").startsWith("HTTP/1.1 401"))
            val login = get(port, "/?t=$token")
            assertTrue(login.startsWith("HTTP/1.1 302"), login)
            assertTrue("Location: /\r\n" in login)
            assertTrue("Set-Cookie: nexusirl_token=$token; Path=/; HttpOnly; SameSite=Strict" in login)
            val cookie = "nexusirl_token=$token"
            assertTrue(get(port, "/", cookie).endsWith("<html>remote</html>"))
            assertTrue(get(port, "/index.html", cookie).contains("X-Frame-Options: DENY"))
            assertTrue(get(port, "/js/config.mjs", cookie).endsWith("export const websocketPort = ${port + 1};\n"))
            assertTrue(get(port, "/../../etc/passwd", cookie).startsWith("HTTP/1.1 404"))
            assertTrue(get(port, "/remote.html", cookie).startsWith("HTTP/1.1 404"), "missing asset")
            assertTrue(get(port, "/", cookie, method = "POST").startsWith("HTTP/1.1 405"))
            assertTrue(get(port, "/", "other=1; nexusirl_token=wrong").startsWith("HTTP/1.1 401"))
        } finally {
            server.stop()
        }
    }

    private fun connect(port: Int, cookie: String?, messages: LinkedBlockingQueue<String>): Pair<WebSocket, LinkedBlockingQueue<String>> {
        val events = LinkedBlockingQueue<String>()
        val request = Request.Builder().url("ws://127.0.0.1:${port + 1}").apply { cookie?.let { header("Cookie", it) } }.build()
        val socket = ReconnectingWebSocket.SHARED_CLIENT.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    events += "open"
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages += text
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    events += "failed"
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    events += "closed $code"
                }
            },
        )
        return socket to events
    }

    @Test
    fun websocketNeedsTheCookie() {
        val port = freePorts()
        val previews = LinkedBlockingQueue<Boolean>()
        val server = web(port, previews).also { it.start() }
        try {
            Thread.sleep(300)
            val refusedMessages = LinkedBlockingQueue<String>()
            val (refusedSocket, refused) = connect(port, null, refusedMessages)
            assertEquals("open", refused.poll(5, TimeUnit.SECONDS))
            assertEquals("closed 1008", refused.poll(5, TimeUnit.SECONDS))
            refusedSocket.send("""{"request":{"id":1,"data":{"getStatus":{}}}}""")
            val (wrongSocket, wrong) = connect(port, "nexusirl_token=wrong", refusedMessages)
            assertEquals("open", wrong.poll(5, TimeUnit.SECONDS))
            assertEquals("closed 1008", wrong.poll(5, TimeUnit.SECONDS))
            wrongSocket.send("""{"request":{"id":1,"data":{"getStatus":{}}}}""")
            val messages = LinkedBlockingQueue<String>()
            val (socket, events) = connect(port, "nexusirl_token=$token", messages)
            assertEquals("open", events.poll(5, TimeUnit.SECONDS))
            socket.send("""{"request":{"id":2,"data":{"getStatus":{}}}}""")
            assertEquals(
                """{"response":{"id":2,"result":{"ok":{}},"data":{"getStatus":{"topLeft":{"stream":{"message":"IRL","ok":true}},"topRight":{}}}}}""",
                messages.poll(5, TimeUnit.SECONDS),
            )
            socket.send("""{"request":{"id":3,"data":{"importSettings":{"data":"e30="}}}}""")
            assertEquals("""{"response":{"id":3,"result":{"error":{}}}}""", messages.poll(5, TimeUnit.SECONDS))
            socket.send("""{"request":{"id":4,"data":{"startPreview":{}}}}""")
            assertEquals(true, previews.poll(5, TimeUnit.SECONDS))
            messages.poll(5, TimeUnit.SECONDS)
            server.sendPreview(byteArrayOf(1, 2, 3))
            assertEquals("""{"preview":{"preview":"AQID"}}""", messages.poll(5, TimeUnit.SECONDS))
            server.stateChanged(RemoteControlAssistantStreamerState(muted = true))
            assertEquals("""{"event":{"data":{"state":{"data":{"muted":true}}}}}""", messages.poll(5, TimeUnit.SECONDS))
            socket.close(1000, null)
            assertEquals(false, previews.poll(5, TimeUnit.SECONDS))
            // Refused pages never got anything.
            assertTrue(refusedMessages.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun onlyAllowedInterfaces() {
        val port = freePorts()
        val server = web(port, allow = false).also { it.start() }
        try {
            assertEquals("", get(port, "/?t=$token"))
        } finally {
            server.stop()
        }
    }
}
