package io.github.mesmerize08.nexusirl.core.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Every secret type in the inventory (docs/PORT_PLAN.md §9.2) must be masked. */
class RedactorTest {
    private val redactor = Redactor()

    @Test
    fun streamUrlsKeepOnlySchemeAndHost() {
        assertEquals(
            "Connecting to rtmp://live.twitch.tv/***",
            redactor.redact("Connecting to rtmp://live.twitch.tv/app/live_123456_AbCdEf"),
        )
        assertEquals(
            "rtmps://a.rtmp.youtube.com:443/***",
            redactor.redact("rtmps://a.rtmp.youtube.com:443/live2/abcd-efgh-ijkl"),
        )
        assertEquals(
            "srtla://example.com:5000/***",
            redactor.redact("srtla://example.com:5000?streamid=publish/live/key&passphrase=hunter22hunter22"),
        )
        assertEquals("srt://192.168.1.10:4000", redactor.redact("srt://192.168.1.10:4000"))
    }

    @Test
    fun unparseableUrlsStillDropUserInfo() {
        // java.net.URI rejects '|', forcing the manual fallback parser.
        val redacted = Redactor.redactUrl("rist://user:pass@host.example/x|y")
        assertFalse("pass" in redacted, redacted)
        assertEquals("rist://host.example/***", redacted)
    }

    @Test
    fun userInfoIsMasked() {
        val redacted = redactor.redact("rtmp://user:secretpass@host.example/app")
        assertFalse("secretpass" in redacted, redacted)
        assertEquals("rtmp://host.example/***", redacted)
    }

    @Test
    fun webhookAndApiUrls() {
        val redacted = redactor.redact("POST https://discord.com/api/webhooks/123456/AbCdEfToken failed")
        assertEquals("POST https://discord.com/*** failed", redacted)
    }

    @Test
    fun credentialPatterns() {
        assertEquals("Authorization: Bearer ***", redactor.redact("Authorization: Bearer abc.def-ghi"))
        assertEquals("PASS oauth:***", redactor.redact("PASS oauth:x1y2z3"))
        assertEquals("streamid=*** latency=2000", redactor.redact("streamid=publish:key latency=2000"))
        assertEquals("""{"password":"***","name":"a"}""", redactor.redact("""{"password":"hunter2","name":"a"}"""))
        assertEquals("""{"accessToken" : "***"}""", redactor.redact("""{"accessToken" : "tok"}"""))
    }

    @Test
    fun storedSecretsAreMaskedByExactMatch() {
        val store = InMemorySecretStore()
        store.put("live_123456_AbCdEf")
        store.put("abc") // Too short to mask safely.
        val redacted = Redactor(store).redact("key live_123456_AbCdEf rejected; abc")
        assertEquals("key *** rejected; abc", redacted)
    }

    @Test
    fun plainTextIsUnchanged() {
        val text = "Stream started at 1920x1080 30 fps, bitrate 5000 kbps"
        assertEquals(text, redactor.redact(text))
    }
}
