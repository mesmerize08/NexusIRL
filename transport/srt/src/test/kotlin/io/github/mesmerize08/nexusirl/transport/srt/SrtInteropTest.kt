package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.testing.Ffmpeg
import io.github.mesmerize08.nexusirl.media.mux.testing.cutBeforeLastVideoPes
import io.github.mesmerize08.nexusirl.media.mux.testing.probeStreams
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.DatagramUdpLinkFactory
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLink
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLinkFactory
import io.github.mesmerize08.nexusirl.transport.srt.net.UdpLinkListener
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.DatagramSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Streams real H.264/AAC through [SrtStream] (Moblin's own SRT sender, pass-through transport)
 * into ffmpeg's libsrt listener, then checks what ffmpeg recorded. Skipped without ffmpeg.
 */
class SrtInteropTest {
    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    /** Wraps the default link factory and records every datagram sent. */
    private class TapLinkFactory : UdpLinkFactory {
        val sent = ConcurrentLinkedQueue<ByteArray>()
        private val inner = DatagramUdpLinkFactory()

        override fun open(
            host: String,
            port: Int,
            bondingInterface: BondingInterface?,
            executor: SerialExecutor,
            listener: UdpLinkListener,
        ): UdpLink {
            val link = inner.open(host, port, bondingInterface, executor, listener)
            return object : UdpLink {
                override fun send(packet: ByteArray) {
                    sent.add(packet.copyOf())
                    link.send(packet)
                }

                override fun close() = link.close()
            }
        }
    }

    private class Result(val recording: File, val performance: SrtPerformanceData?, val sent: List<ByteArray>)

    private fun streamToLibsrt(directory: File, query: String, receiverOptions: String): Result {
        val streams = Ffmpeg.generateElementaryStreams(directory, seconds = 3)
        val port = freeUdpPort()
        val recording = File(directory, "received.ts")
        val receiver = Ffmpeg.start(
            "ffmpeg", "-y", "-v", "error",
            "-i", "srt://127.0.0.1:$port?mode=listener&latency=1000000&timeout=3000000$receiverOptions",
            "-c", "copy", "-f", "mpegts", recording.path,
        )
        Thread.sleep(1_000)

        val executor = SerialExecutor.create("srt-test")
        val connected = CountDownLatch(1)
        val disconnects = ConcurrentLinkedQueue<String>()
        val tap = TapLinkFactory()
        val stream = SrtStream(
            SrtStreamConfig(url = "srt://127.0.0.1:$port?streamid=interop$query", latency = 1000),
            tap,
            executor,
            HostClock.system,
            object : SrtStream.Listener {
                override fun onConnected() = connected.countDown()

                override fun onDisconnected(message: String) {
                    disconnects.add(message)
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
        val receiverLog = receiver.log
        executor.shutdown()
        assertTrue(recording.exists() && recording.length() > 100_000, "nothing recorded: $receiverLog")
        return Result(recording, performance, tap.sent.toList())
    }

    private fun assertDecodesCleanly(recording: File) {
        cutBeforeLastVideoPes(recording)
        val (decodeExit, decodeLog) = Ffmpeg.run("ffmpeg", "-v", "error", "-xerror", "-i", recording.path, "-f", "null", "-")
        assertEquals(0, decodeExit, decodeLog)
        assertTrue(decodeLog.isBlank(), "decode errors:\n$decodeLog")
        val probed = probeStreams(recording).toMap()
        // 90 video / ~141 audio frames were sent; allow for the frames held by the writer at stop.
        assertTrue((probed["h264"] ?: 0) >= 85, "video frames received: $probed")
        assertTrue((probed["aac"] ?: 0) >= 130, "audio frames received: $probed")
    }

    /** True if a data packet's payload looks like plaintext MPEG-TS (sync byte every 188 bytes). */
    private fun looksLikePlainMpegTs(packet: ByteArray): Boolean = isSrtDataPacket(packet) && packet.size >= 16 + 188 * 2 &&
        packet[16].toInt() == 0x47 && packet[16 + 188].toInt() == 0x47

    @Test
    fun libsrtReceivesStreamFromMoblinSender() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("srt-interop").toFile()
        val result = streamToLibsrt(directory, query = "", receiverOptions = "")
        assertDecodesCleanly(result.recording)
        assertTrue(result.performance != null && result.performance.pktSndDropTotal == 0, "drops: ${result.performance}")
        assertTrue(result.sent.any { looksLikePlainMpegTs(it) }, "sanity: unencrypted payloads should be visible")
        directory.deleteRecursively()
    }

    @Test
    fun libsrtDecryptsAes128Stream() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("srt-interop").toFile()
        val passphrase = "correct-horse-battery-staple"
        val result = streamToLibsrt(directory, "&passphrase=$passphrase", "&passphrase=$passphrase&pbkeylen=16&enforced_encryption=1")
        assertDecodesCleanly(result.recording)
        val dataPackets = result.sent.filter { isSrtDataPacket(it) }
        // ~3 s of ~1 Mbit/s test pattern is a few hundred 1316-byte packets.
        assertTrue(dataPackets.size > 200, "data packets: ${dataPackets.size}")
        assertTrue(dataPackets.none { looksLikePlainMpegTs(it) }, "plaintext MPEG-TS visible on the wire")
        assertTrue(dataPackets.all { (it[4].toInt() and 0x18) == 0x08 }, "KK must be the even key")
        directory.deleteRecursively()
    }

    @Test
    fun libsrtDecryptsAes256Stream() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("srt-interop").toFile()
        val passphrase = "another-long-secret-phrase"
        val result = streamToLibsrt(
            directory,
            "&passphrase=$passphrase&pbkeylen=32",
            "&passphrase=$passphrase&pbkeylen=32&enforced_encryption=1",
        )
        assertDecodesCleanly(result.recording)
        assertTrue(result.sent.filter { isSrtDataPacket(it) }.none { looksLikePlainMpegTs(it) })
        directory.deleteRecursively()
    }

    @Test
    fun wrongPassphraseIsReportedAndNothingIsStreamed() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val port = freeUdpPort()
        val receiver = Ffmpeg.start(
            "ffmpeg", "-y", "-v", "error",
            "-i", "srt://127.0.0.1:$port?mode=listener&timeout=8000000&passphrase=the-right-passphrase&enforced_encryption=1",
            "-c", "copy", "-f", "null", "-",
        )
        Thread.sleep(1_000)
        val executor = SerialExecutor.create("srt-test")
        val disconnected = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val firstMessage = AtomicReference<String>()
        val stream = SrtStream(
            SrtStreamConfig(url = "srt://127.0.0.1:$port?passphrase=the-wrong-passphrase"),
            DatagramUdpLinkFactory(),
            executor,
            HostClock.system,
            object : SrtStream.Listener {
                override fun onConnected() = connected.set(true)

                override fun onDisconnected(message: String) {
                    firstMessage.compareAndSet(null, message)
                    disconnected.countDown()
                }
            },
        )
        stream.start()
        assertTrue(disconnected.await(10, TimeUnit.SECONDS), "no disconnect reported")
        stream.stop()
        receiver.destroy()
        executor.shutdown()
        assertFalse(connected.get(), "must not connect with a wrong passphrase")
        assertTrue("passphrase" in firstMessage.get(), "message: ${firstMessage.get()}")
    }

    @Test
    fun tooShortPassphraseIsRejectedAtConfiguration() {
        val error = runCatching {
            SrtStream(
                SrtStreamConfig(url = "srt://127.0.0.1:9000?passphrase=short"),
                DatagramUdpLinkFactory(),
                SerialExecutor.create("srt-test"),
                HostClock.system,
                object : SrtStream.Listener {
                    override fun onConnected() = Unit

                    override fun onDisconnected(message: String) = Unit
                },
            )
        }.exceptionOrNull()
        assertTrue(error is SrtException && "10 to 79" in error.message!!, "got $error")
    }

    @Test
    fun parsesSrtUrls() {
        assertEquals(
            SrtUrl(false, "example.com", 4000, "publish:live/key", null),
            SrtUrl.parse("srt://example.com:4000?streamid=publish%3Alive%2Fkey"),
        )
        assertEquals(SrtUrl(true, "10.0.0.1", 5000, null, null), SrtUrl.parse("srtla://10.0.0.1:5000"))
        assertEquals(32, SrtUrl.parse("srt://h:1?passphrase=abcdefghijk&pbkeylen=32").keyLength)
        assertTrue(runCatching { SrtUrl.parse("srt://h:1?pbkeylen=20") }.isFailure)
        assertTrue(runCatching { SrtUrl.parse("srt://example.com") }.isFailure)
        assertTrue(runCatching { SrtUrl.parse("rtmp://example.com:1935/app") }.isFailure)
    }
}
