package io.github.mesmerize08.nexusirl.core.websocket

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleartextPolicyTest {
    @Test
    fun localNetwork() {
        val local = listOf("10.0.0.2", "172.16.1.1", "172.31.255.255", "192.168.1.10", "169.254.3.4", "100.64.0.1", "100.127.1.1")
        for (host in local + listOf("phone.local", "pc.tail1234.ts.net")) {
            assertTrue(CleartextPolicy.isLocalNetwork(host), host)
        }
        for (host in listOf(
            "8.8.8.8",
            "172.32.0.1",
            "100.128.0.1",
            "192.169.0.1",
            "example.com",
            "10.0.0",
            "10.0.0.256",
            "evil.local.example.com",
        )) {
            assertFalse(CleartextPolicy.isLocalNetwork(host), host)
        }
        assertTrue(CleartextPolicy.isAllowed("127.0.0.1", allowLocalNetwork = false))
        assertTrue(CleartextPolicy.isAllowed("localhost", allowLocalNetwork = false))
        assertFalse(CleartextPolicy.isAllowed("192.168.1.10", allowLocalNetwork = false))
        assertTrue(CleartextPolicy.isAllowed("192.168.1.10", allowLocalNetwork = true))
        assertFalse(CleartextPolicy.isAllowed("example.com", allowLocalNetwork = true))
    }

    @Test
    fun sharedClientRefusesCleartextToTheInternet() {
        val error = assertFailsWith<IOException> {
            ReconnectingWebSocket.SHARED_CLIENT.newCall(Request.Builder().url("http://example.com/").build()).execute()
        }
        assertTrue("refused" in error.message.orEmpty())
        // The local network client refuses public hosts too.
        assertFailsWith<IOException> {
            ReconnectingWebSocket.LOCAL_NETWORK_CLIENT.newCall(Request.Builder().url("http://example.com/").build()).execute()
        }
        // Loopback is allowed (tests and local tools).
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            ReconnectingWebSocket.SHARED_CLIENT.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertEquals("ok", it.body!!.string())
            }
        } finally {
            server.shutdown()
        }
    }
}
