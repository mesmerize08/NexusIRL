package io.github.mesmerize08.nexusirl.transport.srt

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.testing.Ffmpeg
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrate
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateListener
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtBelabox
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtFight
import io.github.mesmerize08.nexusirl.transport.srt.net.DatagramUdpLinkFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Adaptive bitrate end to end: a synthetic encoder that follows the requested bitrate streams
 * through [SrtStream] and a bandwidth-limited relay into libsrt (ffmpeg). The bitrate must fall
 * below a 2 Mbit/s cap and recover when the cap is lifted. Skipped without ffmpeg.
 */
class SrtAdaptiveBitrateInteropTest {
    private class Sample(val seconds: Double, val bitrate: Long)

    private fun run(makeAlgorithm: (AdaptiveBitrateListener) -> AdaptiveBitrate): List<Sample> {
        val testStart = System.nanoTime()
        val serverPort = DatagramSocket(0).use { it.localPort }
        val receiver = Ffmpeg.start(
            "ffmpeg", "-y", "-v", "error",
            // The synthetic payload is not decodable, so ffmpeg is used as a pure libsrt byte sink:
            // the raw "data" format in and out, with no demuxing or codec probing.
            "-f", "data", "-i", "srt://127.0.0.1:$serverPort?mode=listener&latency=2000000&timeout=4000000",
            "-map", "0", "-c", "copy", "-f", "data", java.io.File.createTempFile("abr-", ".bin").apply { deleteOnExit() }.path,
        )
        Thread.sleep(1_000)
        val relay = ShapingUdpRelay(serverPort)
        val executor = SerialExecutor.create("abr-test")
        val connected = CountDownLatch(1)
        val disconnects = ConcurrentLinkedQueue<String>()
        val stream = SrtStream(
            SrtStreamConfig(url = "srt://127.0.0.1:${relay.port}", latency = 2000),
            DatagramUdpLinkFactory(),
            executor,
            HostClock.system,
            object : SrtStream.Listener {
                override fun onConnected() = connected.countDown()

                override fun onDisconnected(message: String) {
                    disconnects.add("%.1fs %s".format((System.nanoTime() - testStart) / 1e9, message))
                }
            },
        )
        val requestedBitrate = AtomicLong(1_000_000)
        val algorithm = makeAlgorithm { requestedBitrate.set(it) }
        val driver = SrtAdaptiveBitrateDriver(stream, algorithm, executor, targetBitrate = 6_000_000)
        stream.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
        stream.setVideoConfig(MpegTsVideoConfig.Avc(byteArrayOf(0x67, 0x42, 0, 0x1F), byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())))
        stream.start()
        assertTrue(connected.await(10, TimeUnit.SECONDS), "connect failed: $disconnects")
        driver.start()

        val samples = ArrayList<Sample>()
        val random = Random(1)
        val startNanos = System.nanoTime()
        val base = MediaTime(startNanos, MediaTime.NANOSECONDS)
        var frame = 0
        relay.rateBitsPerSecond = 2_000_000
        while (true) {
            val seconds = frame / 30.0
            if (seconds >= 30.0) {
                break
            }
            if (seconds >= 15.0) {
                relay.rateBitsPerSecond = 100_000_000 // Cap lifted.
            }
            while (System.nanoTime() < startNanos + (seconds * 1e9).toLong()) {
                Thread.sleep(1)
            }
            val pts = base + MediaTime.fromSeconds(seconds)
            // Synthetic encoder: frame size follows the requested bitrate.
            val size = (requestedBitrate.get() / 8 / 30).toInt().coerceAtLeast(200)
            val isSync = frame % 30 == 0
            val accessUnit = byteArrayOf(0, 0, 0, 1, if (isSync) 0x65 else 0x41) + random.nextBytes(size)
            stream.writeVideo(accessUnit, pts, null, isSync)
            if (frame % 2 == 0) {
                val audio = random.nextBytes(1024)
                stream.writeAudio(audio, 0, audio.size, pts)
            }
            if (frame % 15 == 0) {
                samples.add(Sample(seconds, requestedBitrate.get()))
            }
            frame += 1
        }
        driver.stop()
        stream.stop()
        relay.close()
        receiver.destroy()
        executor.shutdown()
        val trace = samples.joinToString(" ") { "%.1f:%d".format(it.seconds, it.bitrate / 1000) }
        assertTrue(
            disconnects.isEmpty(),
            "disconnected during the test: $disconnects (relay drops ${relay.droppedPackets.get()}) $trace\nreceiver: ${receiver.log.takeLast(2000)}",
        )
        return samples
    }

    private fun checkBehaviour(name: String, samples: List<Sample>, recoveredAbove: Long) {
        val capped = samples.filter { it.seconds in 10.0..15.0 }.map { it.bitrate }
        val recovered = samples.filter { it.seconds >= 27.0 }.map { it.bitrate }
        val trace = samples.joinToString(" ") { "%.1f:%d".format(it.seconds, it.bitrate / 1000) }
        println("$name bitrate trace (seconds:kbps), cap 2 Mbit/s until 15 s: $trace")
        // Under the 2 Mbit/s cap (video plus ~280 kbit/s audio and SRT overhead) it must back off.
        assertTrue(capped.average() < 2_000_000, "$name did not back off under the cap: $trace")
        // With the cap lifted it climbs well above it again (Belabox climbs more slowly by design:
        // increases at most every 400 ms and only at minimum RTT).
        assertTrue(recovered.average() > recoveredAbove, "$name did not recover: $trace")
    }

    @Test
    fun srtFightBacksOffUnderCapAndRecovers() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val samples = run { listener ->
            AdaptiveBitrateSrtFight(6_000_000, listener, HostClock.system).apply { setSettings(AdaptiveBitrateSettings.FAST) }
        }
        checkBehaviour("SrtFight", samples, recoveredAbove = 4_500_000)
    }

    @Test
    fun belaboxBacksOffUnderCapAndRecovers() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val samples = run { listener -> AdaptiveBitrateSrtBelabox(6_000_000, listener, HostClock.system) }
        checkBehaviour("Belabox", samples, recoveredAbove = 2_500_000)
    }
}
