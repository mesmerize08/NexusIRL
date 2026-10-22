package io.github.mesmerize08.nexusirl.diagnostics

import io.github.mesmerize08.nexusirl.core.secrets.InMemorySecretStore
import io.github.mesmerize08.nexusirl.core.secrets.Redactor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun reportHasNoSecrets() {
        val secrets = InMemorySecretStore()
        secrets.put("live_123456789_abcdefSECRET")
        secrets.put("hunter2-remote-password")
        val report = Diagnostics.compose(
            sections = listOf(
                "Settings (no secrets)" to listOf("  Twitch: rtmp://live.twitch.tv/app/live_123456789_abcdefSECRET · 1080p"),
            ),
            log = listOf(
                "10-22 09:00:00.000 I StreamSession: connecting to srt://ingest.example.com:4000?streamid=publish/key&passphrase=topsecret",
                "10-22 09:00:01.000 D Remote: password hunter2-remote-password rejected",
                "10-22 09:00:02.000 D Twitch: Authorization: Bearer abc.def.ghi",
            ),
            redactor = Redactor(secrets),
        )
        for (secret in listOf("live_123456789_abcdefSECRET", "publish/key", "topsecret", "hunter2-remote-password", "abc.def.ghi")) {
            assertFalse(secret in report, "leaked $secret:\n$report")
        }
        // Still useful: which server and protocol, and what happened.
        assertTrue("live.twitch.tv" in report)
        assertTrue("ingest.example.com" in report)
        assertTrue("rejected" in report)
        assertTrue("== Log (newest last) ==" in report)
    }
}
