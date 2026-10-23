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
import io.github.mesmerize08.nexusirl.transport.srt.net.DefaultRoute
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import io.github.mesmerize08.nexusirl.transport.srt.srtla.BondingConnection
import io.github.mesmerize08.nexusirl.transport.srt.srtla.server.SrtlaServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.DatagramSocket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A bonding path on the default route; distinct sockets give the server distinct endpoints. */
private class LoopbackInterface(override val id: String, override val type: InterfaceType) : BondingInterface {
    override val name: String = id

    override fun bind(socket: DatagramSocket) = Unit
}

/**
 * End-to-end SRTLA bonding: [SrtStream] over two paths -> [SrtlaServer] (ported from Moblin) ->
 * ffmpeg's libsrt listener. Skipped without ffmpeg.
 */
class SrtlaInteropTest {
    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    /**
     * Desktop tools have no connectivity layer: with [DefaultRoute] as the only path SRTLA must
     * still connect. Without any interface it sent nothing (found streaming to a test server).
     */
    @Test
    fun singleDefaultRoutePathConnects() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("srtla-default-route").toFile()
        val srtPort = freeUdpPort()
        val receiver = Ffmpeg.start(
            "ffmpeg", "-y", "-v", "error",
            "-i", "srt://127.0.0.1:$srtPort?mode=listener&latency=1000000&timeout=3000000",
            "-c", "copy", "-f", "mpegts", File(directory, "received.ts").path,
        )
        val serverExecutor = SerialExecutor.create("srtla-server")
        val server = SrtlaServer(0, srtPort, serverExecutor, HostClock.system)
        server.start()
        Thread.sleep(1_000)
        val executor = SerialExecutor.create("srtla-client")
        val connected = CountDownLatch(1)
        val stream = SrtStream(
            SrtStreamConfig(url = "srtla://127.0.0.1:${server.localPort}?streamid=default", latency = 1000),
            DatagramUdpLinkFactory(),
            executor,
            HostClock.system,
            object : SrtStream.Listener {
                override fun onConnected() = connected.countDown()

                override fun onDisconnected(message: String) = Unit
            },
        )
        stream.setInterfaces(listOf(DefaultRoute))
        stream.start()
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS), "SRTLA over the default route did not connect")
        } finally {
            stream.stop()
            server.stop()
            receiver.destroy()
            executor.shutdown()
            serverExecutor.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun bondedStreamOverTwoPathsReachesLibsrt() {
        assumeTrue(Ffmpeg.available, "ffmpeg/ffprobe not installed")
        val directory = Files.createTempDirectory("srtla-interop").toFile()
        val streams = Ffmpeg.generateElementaryStreams(directory, seconds = 4)
        val srtPort = freeUdpPort()
        val recording = File(directory, "received.ts")
        val receiver = Ffmpeg.start(
            "ffmpeg", "-y", "-v", "error",
            "-i", "srt://127.0.0.1:$srtPort?mode=listener&latency=1000000&timeout=3000000",
            "-c", "copy", "-f", "mpegts", recording.path,
        )
        val serverExecutor = SerialExecutor.create("srtla-server")
        val server = SrtlaServer(0, srtPort, serverExecutor, HostClock.system)
        server.start()
        Thread.sleep(1_000)

        val executor = SerialExecutor.create("srtla-client")
        val connected = CountDownLatch(1)
        val disconnects = ArrayList<String>()
        val stream = SrtStream(
            SrtStreamConfig(url = "srtla://127.0.0.1:${server.localPort}?streamid=bonded", latency = 1000),
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
        stream.setInterfaces(
            listOf(LoopbackInterface("cell0", InterfaceType.CELLULAR), LoopbackInterface("wlan0", InterfaceType.WIFI)),
        )
        stream.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
        stream.setVideoConfig(MpegTsVideoConfig.Avc(streams.sps, streams.pps))
        stream.start()
        assertTrue(connected.await(10, TimeUnit.SECONDS), "SRTLA/SRT connect failed: $disconnects")

        val usage = HashMap<String, Long>()
        fun collectStatistics() {
            val future = CompletableFuture<List<BondingConnection>>()
            executor.execute { future.complete(stream.connectionStatistics()) }
            for (connection in future.get(5, TimeUnit.SECONDS)) {
                usage.merge(connection.name, connection.usage, Long::plus)
            }
        }

        val startNanos = System.nanoTime()
        val base = MediaTime(startNanos, MediaTime.NANOSECONDS)
        var nextStatistics = 1.0
        fun waitUntil(seconds: Double) {
            if (seconds >= nextStatistics) {
                collectStatistics()
                nextStatistics += 1.0
            }
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
        collectStatistics()
        stream.stop()
        assertTrue(receiver.waitFor(30, TimeUnit.SECONDS), "ffmpeg did not finish")
        server.stop()
        executor.shutdown()
        serverExecutor.shutdown()

        // Both paths registered and carried data.
        assertEquals(setOf("Cellular", "WiFi"), usage.keys, "paths: $usage")
        assertTrue(usage.values.all { it > 50_000 }, "per-path bytes: $usage")

        cutBeforeLastVideoPes(recording)
        val (decodeExit, decodeLog) = Ffmpeg.run("ffmpeg", "-v", "error", "-xerror", "-i", recording.path, "-f", "null", "-")
        assertEquals(0, decodeExit, decodeLog)
        assertTrue(decodeLog.isBlank(), "decode errors:\n$decodeLog")
        val probed = probeStreams(recording).toMap()
        assertTrue((probed["h264"] ?: 0) >= 115, "video frames: $probed")
        assertTrue((probed["aac"] ?: 0) >= 175, "audio frames: $probed")
        directory.deleteRecursively()
    }
}
