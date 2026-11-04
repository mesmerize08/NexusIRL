package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.settings.StreamKeyPaste.Failure
import io.github.mesmerize08.nexusirl.settings.StreamKeyPaste.Result
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamKeyPasteTest {
    private fun pasted(url: String, wholeUrl: Boolean = false) = Result.Pasted(url, wholeUrl)

    @Test
    fun fullUrlReplacesTheField() {
        assertEquals(
            pasted("rtmp://live.twitch.tv/app/live_123_abc", wholeUrl = true),
            StreamKeyPaste.apply("srt://old:9000", "  rtmp://live.twitch.tv/app/live_123_abc\n"),
        )
        assertEquals(
            pasted("srtla://ingest.example.com:5000?streamid=abc&passphrase=0123456789", wholeUrl = true),
            StreamKeyPaste.apply("", "SRTLA://ingest.example.com:5000?streamid=abc&passphrase=0123456789"),
        )
    }

    @Test
    fun invalidUrlIsRefused() {
        assertEquals(Result.Failed(Failure.INVALID_URL), StreamKeyPaste.apply("", "srt://host-without-port"))
        assertEquals(Result.Failed(Failure.INVALID_URL), StreamKeyPaste.apply("", "https://example.com/key"))
    }

    @Test
    fun rtmpKeyIsAppendedToTheServer() {
        assertEquals(pasted("rtmp://live.twitch.tv/app/live_1"), StreamKeyPaste.apply("rtmp://live.twitch.tv/app", "live_1"))
        assertEquals(pasted("rtmp://live.twitch.tv/app/live_1"), StreamKeyPaste.apply("rtmp://live.twitch.tv/app/", "live_1"))
        assertEquals(
            pasted("rtmps://live-api-s.facebook.com:443/rtmp/FB-1"),
            StreamKeyPaste.apply("rtmps://live-api-s.facebook.com:443/rtmp/", "FB-1"),
        )
    }

    @Test
    fun rtmpKeyReplacesTheOldKey() {
        assertEquals(
            pasted("rtmp://a.rtmp.youtube.com/live2/new-key"),
            StreamKeyPaste.apply("rtmp://a.rtmp.youtube.com/live2/old-key", "new-key"),
        )
        // Twitch's bandwidth test option is part of the old key's segment.
        assertEquals(
            pasted("rtmp://live.twitch.tv/app/live_2"),
            StreamKeyPaste.apply("rtmp://live.twitch.tv/app/live_1?bandwidthtest=true", "live_2"),
        )
        // RTMP user info stays.
        assertEquals(
            pasted("rtmp://user:pass@host.example/app/new"),
            StreamKeyPaste.apply("rtmp://user:pass@host.example/app/old", "new"),
        )
    }

    @Test
    fun srtKeyBecomesTheStreamId() {
        assertEquals(pasted("srt://host.example:9000?streamid=abc"), StreamKeyPaste.apply("srt://host.example:9000", "abc"))
        assertEquals(
            pasted("srtla://host.example:5000?latency=2000&streamid=new&passphrase=0123456789"),
            StreamKeyPaste.apply("srtla://host.example:5000?latency=2000&streamid=old&passphrase=0123456789", "new"),
        )
        // Characters with a meaning in URLs are encoded (the SRT sender decodes them).
        assertEquals(
            pasted("srt://host.example:9000?streamid=%23%21%3A%3Ar%3Dlive%2Ftest%2Cm%3Dpublish"),
            StreamKeyPaste.apply("srt://host.example:9000", "#!::r=live/test,m=publish"),
        )
    }

    @Test
    fun srtQueryIsMerged() {
        assertEquals(
            pasted("srt://host.example:9000?latency=2000&streamid=abc&passphrase=0123456789"),
            StreamKeyPaste.apply("srt://host.example:9000?latency=2000&streamid=old", "?streamid=abc&passphrase=0123456789"),
        )
        assertEquals(
            pasted("srt://host.example:9000?passphrase=0123456789"),
            StreamKeyPaste.apply("srt://host.example:9000", "passphrase=0123456789"),
        )
    }

    @Test
    fun bareKeyNeedsAServerFirst() {
        assertEquals(Result.Failed(Failure.NEEDS_SERVER), StreamKeyPaste.apply("", "live_1"))
        assertEquals(Result.Failed(Failure.NEEDS_SERVER), StreamKeyPaste.apply("rist://host:1234", "live_1"))
    }

    @Test
    fun textThatIsNotAKeyIsRefused() {
        assertEquals(Result.Failed(Failure.EMPTY), StreamKeyPaste.apply("rtmp://h/app", " \n"))
        assertEquals(Result.Failed(Failure.NOT_A_KEY), StreamKeyPaste.apply("rtmp://h/app", "Stream key: live_1"))
        assertEquals(Result.Failed(Failure.NOT_A_KEY), StreamKeyPaste.apply("rtmp://h/app", "a".repeat(2049)))
    }
}
