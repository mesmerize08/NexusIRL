package io.github.mesmerize08.nexusirl.tools.srtlareceiver

import io.github.mesmerize08.nexusirl.transport.srt.srtla.server.SrtlaServerPathStatistics
import kotlin.test.Test
import kotlin.test.assertEquals

class SrtlaReceiverTest {
    @Test
    fun pathReportShowsTheRateSinceTheLastReport() {
        val before = SrtlaServerPathStatistics("1.124.104.132:34225", packets = 1000, bytes = 1_000_000, dataPackets = 900)
        val now = before.copy(packets = 1500, bytes = 1_750_000)
        // 500 packets and 750 kB in 5 s: 100 pkt/s, 1.2 Mbit/s.
        assertEquals("1.124.104.132:34225 100 pkt/s 1.20 Mbit/s", describePath(now, before))
        assertEquals("1.124.104.132:34225 300 pkt/s 2.80 Mbit/s (new)", describePath(now, null))
    }
}
