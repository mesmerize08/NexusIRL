package io.github.mesmerize08.nexusirl.tools.srtlareceiver

import io.github.mesmerize08.nexusirl.transport.srt.srtla.server.SrtlaServerPathStatistics
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SrtlaReceiverTest {
    @Test
    fun receiverProcessListensOnTheRequestedAddress() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("receiver.classpath"),
            "io.github.mesmerize08.nexusirl.tools.srtlareceiver.SrtlaReceiverKt",
            "--srt-port", "4001", "--srtla-port", "0", "--bind", "127.0.0.1",
        ).redirectErrorStream(true).start()
        try {
            val line = CompletableFuture.supplyAsync { process.inputReader().readLine() }.get(10, TimeUnit.SECONDS)
            val listener = Regex("""^srtla-receiver: listening for SRTLA on UDP [1-9]\d* \(127\.0\.0\.1\),""")
            assertTrue(listener.containsMatchIn(assertNotNull(line)), line)
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    @Test
    fun parsesBindAddressAndPreservesDefaults() {
        assertEquals(ReceiverOptions(4001, 5000, null), parseOptions(arrayOf("--srt-port", "4001")))
        val options = parseOptions(arrayOf("--bind", "127.0.0.1", "--srt-port", "4001", "--srtla-port", "0"))
        assertEquals("127.0.0.1", options.bindAddress?.hostAddress)
        assertEquals(0, options.srtlaPort)
    }

    @Test
    fun rejectsInvalidOptionsBeforeListening() {
        for (args in listOf(
            arrayOf("--srt-port", "65536"),
            arrayOf("--srt-port", "0"),
            arrayOf("--srt-port", "4001", "--srtla-port", "-1"),
            arrayOf("--srt-port", "4001", "--bind"),
            arrayOf("--srt-port", "4001", "--bind", ""),
            arrayOf("--srt-port", "4001", "--bindd", "127.0.0.1"),
            arrayOf("--srt-port", "4001", "--bind", "bad address", "--bind", "127.0.0.1"),
        )) {
            assertFailsWith<IllegalArgumentException>(args.joinToString(" ")) { parseOptions(args) }
        }
    }

    @Test
    fun pathReportShowsTheRateSinceTheLastReport() {
        val before = SrtlaServerPathStatistics("1.124.104.132:34225", packets = 1000, bytes = 1_000_000, dataPackets = 900)
        val now = before.copy(packets = 1500, bytes = 1_750_000)
        // 500 packets and 750 kB in 5 s: 100 pkt/s, 1.2 Mbit/s.
        assertEquals("1.124.104.132:34225 100 pkt/s 1.20 Mbit/s", describePath(now, before))
        assertEquals("1.124.104.132:34225 300 pkt/s 2.80 Mbit/s (new)", describePath(now, null))
    }
}
