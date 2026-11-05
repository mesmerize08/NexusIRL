package io.github.mesmerize08.nexusirl.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebSocketUrlProblemTest {
    @Test
    fun wssToAnyHostIsFine() {
        assertNull(webSocketUrlProblem("wss://obs.example.com:4455"))
        assertNull(webSocketUrlProblem("wss://203.0.113.5/"))
        assertNull(webSocketUrlProblem("WSS://[2001:db8::1]:4455"))
    }

    @Test
    fun wsToLocalNetworkIsFine() {
        assertNull(webSocketUrlProblem("ws://192.168.1.10:4455"))
        assertNull(webSocketUrlProblem("ws://10.0.0.2"))
        assertNull(webSocketUrlProblem("ws://169.254.3.4:80/path"))
        assertNull(webSocketUrlProblem("ws://localhost:4455"))
        assertNull(webSocketUrlProblem("ws://127.0.0.1:4455"))
        assertNull(webSocketUrlProblem("ws://my-pc.local:4455"))
        assertNull(webSocketUrlProblem("ws://obs_pc.local:4455"))
        assertNull(webSocketUrlProblem("ws://pc.tailnet.ts.net"))
    }

    @Test
    fun wsToIpv6LocalLiteralsIsFine() {
        assertNull(webSocketUrlProblem("ws://[::1]:4455"))
        assertNull(webSocketUrlProblem("ws://[fe80::1]:4455"))
        assertNull(webSocketUrlProblem("ws://[fd7a:115c:a1e0::1]:4455/"))
    }

    @Test
    fun wsToPublicHostIsRefused() {
        assertEquals(WebSocketUrlProblem.NOT_LOCAL_CLEARTEXT, webSocketUrlProblem("ws://obs.example.com:4455"))
        assertEquals(WebSocketUrlProblem.NOT_LOCAL_CLEARTEXT, webSocketUrlProblem("ws://8.8.8.8:4455"))
        assertEquals(WebSocketUrlProblem.NOT_LOCAL_CLEARTEXT, webSocketUrlProblem("WS://[2001:db8::1]:4455"))
        assertEquals(WebSocketUrlProblem.NOT_LOCAL_CLEARTEXT, webSocketUrlProblem("ws://127.evil.example"))
    }

    @Test
    fun garbageIsInvalid() {
        assertEquals(WebSocketUrlProblem.INVALID, webSocketUrlProblem(""))
        assertEquals(WebSocketUrlProblem.INVALID, webSocketUrlProblem("obs.local:4455"))
        assertEquals(WebSocketUrlProblem.INVALID, webSocketUrlProblem("http://192.168.1.10"))
        assertEquals(WebSocketUrlProblem.INVALID, webSocketUrlProblem("ws://"))
        assertEquals(WebSocketUrlProblem.INVALID, webSocketUrlProblem("ws://[::1"))
        assertEquals(WebSocketUrlProblem.INVALID, webSocketUrlProblem("ws://a b"))
    }
}
