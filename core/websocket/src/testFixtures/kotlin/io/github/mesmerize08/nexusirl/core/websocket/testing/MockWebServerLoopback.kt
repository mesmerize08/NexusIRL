package io.github.mesmerize08.nexusirl.core.websocket.testing

import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockWebServer

/**
 * [MockWebServer.url] names the server by reverse DNS (a hosts file can make that anything), which
 * the cleartext policy rightly does not trust as loopback; use the literal address instead.
 */
fun MockWebServer.loopbackUrl(path: String): HttpUrl = url(path).newBuilder().host("127.0.0.1").build()
