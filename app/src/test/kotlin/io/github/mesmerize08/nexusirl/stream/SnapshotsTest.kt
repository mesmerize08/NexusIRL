package io.github.mesmerize08.nexusirl.stream

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotsTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val client = OkHttpClient()

    @Test
    fun multipartBodyMatchesUpstream() {
        val body = Snapshots.multipartBody("B", byteArrayOf(1, 2), "Snapshot taken by viewer.")
        val expected = "\r\n--B\r\ncontent-disposition: form-data; name=\"content\"\r\n\r\nSnapshot taken by viewer." +
            "\r\n--B\r\ncontent-disposition: form-data; name=\"snapshot\"; filename=\"snapshot.jpg\"\r\n" +
            "content-type: image/jpeg\r\n\r\n\u0001\u0002\r\n--B--\r\n"
        assertEquals(expected, body.toString(Charsets.ISO_8859_1))
        assertFalse("name=\"content\"" in Snapshots.multipartBody("B", byteArrayOf(), null).toString(Charsets.ISO_8859_1))
    }

    @Test
    fun uploadsToWebhook() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(400))
            val url = server.url("/api/webhooks/1/token").toString()
            assertTrue(Snapshots.uploadToDiscord(url, jpeg, "hi", client))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            assertTrue("filename=\"snapshot.jpg\"" in request.body.readUtf8())
            assertFalse(Snapshots.uploadToDiscord(url, jpeg, null, client))
            assertFalse(Snapshots.uploadToDiscord("not a url", jpeg, null, client))
        }
    }

    @Test
    fun fetchesFromWebRemoteControl() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(jpeg)))
            server.enqueue(MockResponse().setBody("not a jpeg"))
            server.enqueue(MockResponse().setResponseCode(401))
            val base = server.url("/?t=secret").toString()
            assertContentEquals(jpeg, Snapshots.fetchFromWebRemoteControl(base, 0f, client))
            val request = server.takeRequest()
            assertEquals("/snapshot?age=0.0", request.path)
            assertEquals("nexusirl_token=secret", request.getHeader("Cookie"))
            assertNull(Snapshots.fetchFromWebRemoteControl(base, 0f, client))
            assertNull(Snapshots.fetchFromWebRemoteControl(base, 0f, client))
        }
    }
}
