package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.testing.Ffmpeg
import io.github.mesmerize08.nexusirl.media.mux.testing.cutBeforeLastVideoPes
import io.github.mesmerize08.nexusirl.media.mux.testing.probeStreams
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.transport.srt.net.DatagramUdpLinkFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.DatagramSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streams real H.264/AAC through [SrtStream] (Moblin's own SRT sender, pass-through transport)
 * into ffmpeg's libsrt listener, then checks what ffmpeg recorded. Skipped without ffmpeg.
 */
class SrtInteropTest {
    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    @Test
    fun libsrtReceivesStreamFromMoblinSender() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("srt-interop").toFile()
        val streams = Ffmpeg.generateElementaryStreams(directory, seconds = 3)
        val port = freeUdpPort()
        val recording = File(directory, "received.ts")
        val receiver = Ffmpeg.start(
            "ffmpeg", "-y", "-v", "error",
            "-i", "srt://127.0.0.1:$port?mode=listener&latency=1000000&timeout=3000000",
            "-c", "copy", "-f", "mpegts", recording.path,
        )
        Thread.sleep(1_000)

        val executor = SerialExecutor.create("srt-test")
        val connected = CountDownLatch(1)
        val disconnects = ArrayList<String>()
        val stream = SrtStream(
            SrtStreamConfig(url = "srt://127.0.0.1:$port?streamid=interop", latency = 1000),
            DatagramUdpLinkFactory(),
            executor,
            HostClock.system,
            object : SrtStream.Listener {
                override fun onConnected() = connected.countDown()

                override fun onDisconnected(message: String) {
                    synchronized(disconnects) { disconnects.add(message) }
                }
            },
        )
        stream.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
        stream.setVideoConfig(MpegTsVideoConfig.Avc(streams.sps, streams.pps))
        stream.start()
        assertTrue(connected.await(10, TimeUnit.SECONDS), "SRT handshake with libsrt failed: $disconnects")

        // Feed in real time with host-clock timestamps, as the capture pipeline will.
        val startNanos = System.nanoTime()
        val base = MediaTime(startNanos, MediaTime.NANOSECONDS)
        fun waitUntil(seconds: Double) {
            val deadline = startNanos + (seconds * 1e9).toLong()
            while (System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
        }
        streams.forEachInterleaved(
            video = { unit, time ->
                waitUntil(time)
                stream.writeVideo(unit.data, base + MediaTime.fromSeconds(time), null, unit.isIdr)
            },
            audio = { frame, time ->
                waitUntil(time)
                stream.writeAudio(frame, 0, frame.size, base + MediaTime.fromSeconds(time))
            },
        )
        Thread.sleep(500)
        val performance = stream.getPerformanceData()
        stream.stop()
        // The sender, like upstream, sends no shutdown; ffmpeg ends on its 3 s I/O timeout.
        assertTrue(receiver.waitFor(30, TimeUnit.SECONDS), "ffmpeg did not finish")
        val receiverLog = receiver.inputStream.bufferedReader().readText()
        executor.shutdown()

        assertTrue(recording.exists() && recording.length() > 100_000, "nothing recorded: $receiverLog")
        cutBeforeLastVideoPes(recording)
        val (decodeExit, decodeLog) = Ffmpeg.run("ffmpeg", "-v", "error", "-xerror", "-i", recording.path, "-f", "null", "-")
        assertEquals(0, decodeExit, decodeLog)
        assertTrue(decodeLog.isBlank(), "decode errors:\n$decodeLog")
        val probed = probeStreams(recording).toMap()
        val videoFrames = probed["h264"] ?: 0
        val audioFrames = probed["aac"] ?: 0
        // 90 video / ~141 audio frames were sent; allow for the frames held by the writer at stop.
        assertTrue(videoFrames >= 85, "video frames received: $videoFrames ($probed)")
        assertTrue(audioFrames >= 130, "audio frames received: $audioFrames ($probed)")
        assertTrue(performance != null && performance.pktSndDropTotal == 0, "drops: $performance")
        directory.deleteRecursively()
    }

    @Test
    fun passphraseIsRejectedUntilEncryptionIsSupported() {
        val error = runCatching {
            SrtStream(
                SrtStreamConfig(url = "srt://127.0.0.1:9000?passphrase=secret12345"),
                DatagramUdpLinkFactory(),
                SerialExecutor.create("srt-test"),
                HostClock.system,
                object : SrtStream.Listener {
                    override fun onConnected() = Unit

                    override fun onDisconnected(message: String) = Unit
                },
            )
        }.exceptionOrNull()
        assertTrue(error is SrtException && "passphrase" in error.message!!, "got $error")
    }

    @Test
    fun parsesSrtUrls() {
        assertEquals(SrtUrl(false, "example.com", 4000, "publish:live/key", null), SrtUrl.parse("srt://example.com:4000?streamid=publish%3Alive%2Fkey"))
        assertEquals(SrtUrl(true, "10.0.0.1", 5000, null, null), SrtUrl.parse("srtla://10.0.0.1:5000"))
        assertTrue(runCatching { SrtUrl.parse("srt://example.com") }.isFailure)
        assertTrue(runCatching { SrtUrl.parse("rtmp://example.com:1935/app") }.isFailure)
    }
}
