package io.github.mesmerize08.nexusirl.core.websocket

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.net.InetAddress
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
    fun ipv6LocalNetwork() {
        // As Java prints them (with a scope), as URLs carry them, and Tailscale's fd7a:115c:a1e0::/48.
        for (host in listOf(
            "fe80:0:0:0:141b:48ff:febd:8e9c%wlan0",
            "fe80::1",
            "[fe80::1%25wlan0]",
            "fd1e:e04d:cd9d:4d73:141b:48ff:febd:8e9c",
            "fc00::1",
            "fd7a:115c:a1e0::1",
            "::ffff:192.168.1.10",
        )) {
            assertTrue(CleartextPolicy.isLocalNetwork(host), host)
        }
        for (host in listOf("2001:8004:1a23:e8bc::1", "2606:4700::1111", "::ffff:8.8.8.8", "fe:80::evil", "fe80::zz", "[::1")) {
            assertFalse(CleartextPolicy.isLocalNetwork(host), host)
        }
    }

    @Test
    fun samePrefix() {
        fun address(text: String) = InetAddress.getByName(text)
        val local = address("2001:8004:1a23:e8bc:f954:4e0c:d28e:9f5b")
        assertTrue(CleartextPolicy.isSamePrefix(local, address("2001:8004:1a23:e8bc::42"), 64))
        assertFalse(CleartextPolicy.isSamePrefix(local, address("2001:8004:1a23:e8bd::42"), 64))
        assertFalse(CleartextPolicy.isSamePrefix(local, address("2606:4700::1111"), 64))
        assertTrue(CleartextPolicy.isSamePrefix(address("172.20.10.2"), address("172.20.10.1"), 28))
        assertFalse(CleartextPolicy.isSamePrefix(address("172.20.10.2"), address("172.20.10.17"), 28))
        // Prefixes that would cover a large part of the internet, and mixed families, never match.
        assertFalse(CleartextPolicy.isSamePrefix(local, address("2001:8004::1"), 16))
        assertFalse(CleartextPolicy.isSamePrefix(address("8.8.8.8"), address("8.9.9.9"), 8))
        assertFalse(CleartextPolicy.isSamePrefix(local, address("172.20.10.1"), 64))
    }

    @Test
    fun loopback() {
        for (host in listOf("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1")) {
            assertTrue(CleartextPolicy.isLoopback(host), host)
        }
        // Names that only look like loopback, or would need a DNS lookup, never count.
        for (host in listOf(
            "127.evil.example",
            "127.0.0.1.evil.example",
            "127.0.0.256",
            "127.0.1",
            "localhost.evil.example",
            "evil-localhost",
            "example.com",
            "10.0.0.1",
            "::2",
            "[::1",
            "",
        )) {
            assertFalse(CleartextPolicy.isLoopback(host), host)
        }
        assertFalse(CleartextPolicy.isAllowed("127.evil.example", allowLocalNetwork = true))
    }

    @Test
    fun localConnection() {
        val loopback = InetAddress.getLoopbackAddress()
        assertTrue(CleartextPolicy.isLocalConnection(loopback, loopback))
        assertTrue(CleartextPolicy.isLocalConnection(InetAddress.getByName("fd1e::5"), null))
        // A public address that is not one of this machine's: no interface, so no link to be on.
        assertFalse(CleartextPolicy.isLocalConnection(InetAddress.getByName("2606:4700::1111"), InetAddress.getByName("2606:4700::1")))
        assertFalse(CleartextPolicy.isLocalConnection(InetAddress.getByName("8.8.8.8"), null))
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
            // MockWebServer names itself by reverse DNS, which the policy rightly ignores.
            val url = server.url("/").newBuilder().host("127.0.0.1").build()
            ReconnectingWebSocket.SHARED_CLIENT.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals("ok", it.body!!.string())
            }
        } finally {
            server.shutdown()
        }
    }
}
