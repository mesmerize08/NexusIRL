package io.github.mesmerize08.nexusirl.tools.push

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.media.mux.nal.getNalUnits
import io.github.mesmerize08.nexusirl.media.mux.ts.AdtsHeader
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsAudioConfig
import io.github.mesmerize08.nexusirl.media.mux.ts.MpegTsVideoConfig
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSettings
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtBelabox
import io.github.mesmerize08.nexusirl.transport.abr.AdaptiveBitrateSrtFight
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpMetadata
import io.github.mesmerize08.nexusirl.transport.rtmp.RtmpStream
import io.github.mesmerize08.nexusirl.transport.srt.SrtAdaptiveBitrateDriver
import io.github.mesmerize08.nexusirl.transport.srt.SrtStream
import io.github.mesmerize08.nexusirl.transport.srt.SrtStreamConfig
import io.github.mesmerize08.nexusirl.transport.srt.net.DatagramUdpLinkFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

private const val USAGE = """
push - stream a media file with NexusIRL's transports (desktop test tool)

Usage: push --input FILE --url URL [options]

  --input FILE        Any file ffmpeg can read (requires ffmpeg on PATH).
  --url URL           srt://host:port[?streamid=..&passphrase=..&pbkeylen=16|24|32]
                      srtla://host:port[...]   (single path on desktop)
                      rtmp://host/app/key  or  rtmps://host/app/key
  --bitrate BPS       Video encode bitrate and ABR target (default 3000000).
  --size WxH          Video size (default 1280x720).
  --fps N             Frame rate (default 30).
  --seconds N         Encode only the first N seconds (default: whole file).
  --abr fight|belabox|off   SRT adaptive bitrate algorithm to run (default fight).
  --latency MS        SRT latency (default 2000).
  --loop              Repeat the file until interrupted.

The file is pre-encoded once, so ABR cannot change its bitrate; the bitrate ABR would request is
printed in the stats line, which is useful for checking ABR behaviour against a real network.
Secrets in --url are never printed.
"""

private class Options(
    val input: File,
    val url: String,
    val bitrate: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val seconds: Int?,
    val abr: String,
    val latency: Int,
    val loop: Boolean,
)

private fun parseOptions(args: Array<String>): Options {
    val values = HashMap<String, String>()
    var loop = false
    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--loop" -> loop = true
            "--help", "-h" -> fail(null)
            else -> {
                if (!arg.startsWith("--") || i + 1 >= args.size) {
                    fail("Unexpected argument: $arg")
                }
                values[arg.removePrefix("--")] = args[++i]
            }
        }
        i++
    }
    val input = File(values["input"] ?: fail("--input is required"))
    if (!input.exists()) {
        fail("Input not found: $input")
    }
    val size = (values["size"] ?: "1280x720").split('x')
    return Options(
        input = input,
        url = values["url"] ?: fail("--url is required"),
        bitrate = values["bitrate"]?.toLong() ?: 3_000_000,
        width = size[0].toInt(),
        height = size[1].toInt(),
        fps = values["fps"]?.toInt() ?: 30,
        seconds = values["seconds"]?.toInt(),
        abr = values["abr"] ?: "fight",
        latency = values["latency"]?.toInt() ?: 2000,
        loop = loop,
    )
}

private fun fail(message: String?): Nothing {
    if (message != null) {
        System.err.println("push: $message")
    }
    System.err.println(USAGE.trimIndent())
    exitProcess(if (message == null) 0 else 2)
}

/** Redacts everything after scheme://host[:port] so stream keys never reach the console. */
private fun redact(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) {
        return "***"
    }
    val authority = url.substring(schemeEnd + 3).takeWhile { it !in "/?#" }.substringAfterLast('@')
    return url.substring(0, schemeEnd + 3) + authority + "/***"
}

private class Media(
    val sps: ByteArray,
    val pps: ByteArray,
    val accessUnits: List<Pair<ByteArray, Boolean>>,
    val audioFrames: List<ByteArray>,
)

private fun encode(options: Options): Media {
    val directory = Files.createTempDirectory("push").toFile().apply { deleteOnExit() }
    val video = File(directory, "video.h264").apply { deleteOnExit() }
    val audio = File(directory, "audio.aac").apply { deleteOnExit() }
    val limit = options.seconds?.let { listOf("-t", "$it") } ?: emptyList()
    val bitrate = "${options.bitrate / 1000}k"
    run(
        listOf("ffmpeg", "-y", "-v", "error", "-i", options.input.path) + limit + listOf(
            "-map", "0:v:0", "-vf", "scale=${options.width}:${options.height},fps=${options.fps}",
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-bf", "0",
            "-g", "${options.fps * 2}", "-b:v", bitrate, "-maxrate", bitrate, "-bufsize", bitrate,
            "-pix_fmt", "yuv420p", "-x264-params", "aud=1", "-f", "h264", video.path,
        ),
    )
    run(
        listOf("ffmpeg", "-y", "-v", "error", "-i", options.input.path) + limit + listOf(
            "-map", "0:a:0?", "-ac", "2", "-ar", "48000", "-c:a", "aac", "-b:a", "128k", "-f", "adts", audio.path,
        ),
    )
    return parse(video.readBytes(), if (audio.exists()) audio.readBytes() else ByteArray(0))
}

private fun run(command: List<String>) {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) {
        fail("ffmpeg failed: $output")
    }
}

private fun parse(video: ByteArray, audio: ByteArray): Media {
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    val units = ArrayList<Pair<ByteArray, Boolean>>()
    var current = ByteArrayOutputStream()
    var idr = false
    fun finish() {
        if (current.size() > 0) {
            units.add(current.toByteArray() to idr)
        }
        current = ByteArrayOutputStream()
        idr = false
    }
    for (nal in getNalUnits(video)) {
        val data = video.copyOfRange(nal.dataOffset(), nal.dataOffset() + nal.dataLength)
        when (data[0].toInt() and 0x1F) {
            9 -> finish()
            7 -> sps = sps ?: data
            8 -> pps = pps ?: data
            else -> {
                if (data[0].toInt() and 0x1F == 5) {
                    idr = true
                }
                current.write(byteArrayOf(0, 0, 0, 1))
                current.write(data)
            }
        }
    }
    finish()
    val frames = ArrayList<ByteArray>()
    var offset = 0
    while (offset < audio.size) {
        val length = AdtsHeader.frameLength(audio, offset) ?: break
        frames.add(audio.copyOfRange(offset + AdtsHeader.SIZE, offset + length))
        offset += length
    }
    return Media(sps ?: fail("No H.264 SPS found"), pps ?: fail("No H.264 PPS found"), units, frames)
}

/** Common sink for SRT(LA) and RTMP. */
private interface Sink {
    fun writeVideo(data: ByteArray, pts: MediaTime, isSync: Boolean)

    fun writeAudio(data: ByteArray, pts: MediaTime)

    fun stats(): String

    fun stop()
}

fun main(args: Array<String>) {
    val options = parseOptions(args)
    println("push: encoding ${options.input.name} (${options.width}x${options.height}@${options.fps}, ${options.bitrate / 1000} kbps)...")
    val media = encode(options)
    println("push: ${media.accessUnits.size} video frames, ${media.audioFrames.size} audio frames")
    val executor = SerialExecutor.create("push")
    val connected = CountDownLatch(1)
    val failure = AtomicLong(0)
    val scheme = options.url.substringBefore("://").lowercase()
    val sink: Sink = when (scheme) {
        "srt", "srtla" -> {
            val requested = AtomicLong(options.bitrate)
            val stream = SrtStream(
                SrtStreamConfig(url = options.url, latency = options.latency),
                DatagramUdpLinkFactory(),
                executor,
                HostClock.system,
                object : SrtStream.Listener {
                    override fun onConnected() {
                        println("push: connected to ${redact(options.url)}")
                        connected.countDown()
                    }

                    override fun onDisconnected(message: String) {
                        println("push: $message")
                        failure.incrementAndGet()
                        connected.countDown()
                    }
                },
            )
            val algorithm = when (options.abr) {
                "fight" -> AdaptiveBitrateSrtFight(options.bitrate, { requested.set(it) }, HostClock.system)
                    .apply { setSettings(AdaptiveBitrateSettings.FAST) }
                "belabox" -> AdaptiveBitrateSrtBelabox(options.bitrate, { requested.set(it) }, HostClock.system)
                else -> null
            }
            val driver = algorithm?.let { SrtAdaptiveBitrateDriver(stream, it, executor, options.bitrate) }
            stream.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2))
            stream.setVideoConfig(MpegTsVideoConfig.Avc(media.sps, media.pps))
            stream.start()
            driver?.start()
            object : Sink {
                override fun writeVideo(data: ByteArray, pts: MediaTime, isSync: Boolean) = stream.writeVideo(data, pts, null, isSync)

                override fun writeAudio(data: ByteArray, pts: MediaTime) = stream.writeAudio(data, 0, data.size, pts)

                override fun stats(): String {
                    val p = stream.getPerformanceData() ?: return "connecting"
                    val abr = if (algorithm != null) " abr=${requested.get() / 1000}kbps" else ""
                    return "rtt=%.0fms pif=%d send=%.2fMbps retrans=%d nak=%d drop=%d%s".format(
                        p.msRtt,
                        p.pktFlightSize,
                        p.mbpsSendRate,
                        p.pktRetransTotal,
                        p.pktRecvNakTotal,
                        p.pktSndDropTotal,
                        abr,
                    )
                }

                override fun stop() {
                    driver?.stop()
                    stream.stop()
                }
            }
        }
        "rtmp", "rtmps" -> {
            val stream = RtmpStream(
                options.url,
                executor,
                HostClock.system,
                object : RtmpStream.Listener {
                    override fun onStatus(code: String) {
                        println("push: $code")
                        if (code.endsWith("Failed") || code.endsWith("Closed") || code.endsWith("Rejected")) {
                            failure.incrementAndGet()
                            connected.countDown()
                        }
                    }

                    override fun onConnected() {
                        println("push: publishing to ${redact(options.url)}")
                        connected.countDown()
                    }
                },
            )
            stream.setMetadata(
                RtmpMetadata(options.width, options.height, options.fps.toDouble(), false, options.bitrate, 128_000, 48_000.0),
            )
            stream.setAudioConfig(MpegTsAudioConfig.aacLc(48000, 2), 48000)
            stream.setVideoConfig(MpegTsVideoConfig.Avc(media.sps, media.pps))
            stream.connect()
            object : Sink {
                override fun writeVideo(data: ByteArray, pts: MediaTime, isSync: Boolean) = stream.writeVideo(data, pts, null, isSync)

                override fun writeAudio(data: ByteArray, pts: MediaTime) = stream.writeAudio(data, 0, data.size, pts)

                override fun stats(): String {
                    val s = stream.info.stats
                    return "rtt=%.0fms inflight=%d speed=%dkbps".format(
                        s.rttMs,
                        s.packetsInFlight,
                        stream.info.bitrateStats.latestSpeed * 8 / 1000,
                    )
                }

                override fun stop() = stream.disconnect()
            }
        }
        else -> fail("Unsupported URL scheme: $scheme")
    }
    if (!connected.await(20, TimeUnit.SECONDS) || failure.get() > 0) {
        println("push: could not connect")
        sink.stop()
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { sink.stop() })
    val startNanos = System.nanoTime()
    val base = MediaTime(startNanos, MediaTime.NANOSECONDS)
    val frameDuration = 1.0 / options.fps
    val clipDuration = media.accessUnits.size * frameDuration
    var nextStats = 1.0
    var iteration = 0
    do {
        val offset = iteration * clipDuration
        var videoIndex = 0
        var audioIndex = 0
        while (videoIndex < media.accessUnits.size || audioIndex < media.audioFrames.size) {
            if (failure.get() > 0) {
                println("push: stopped")
                sink.stop()
                exitProcess(1)
            }
            val videoTime = offset + videoIndex * frameDuration
            val audioTime = offset + audioIndex * 1024 / 48000.0
            val useAudio = audioIndex < media.audioFrames.size && (videoIndex >= media.accessUnits.size || audioTime <= videoTime)
            val time = if (useAudio) audioTime else videoTime
            while (System.nanoTime() < startNanos + (time * 1e9).toLong()) {
                Thread.sleep(1)
            }
            if (time >= nextStats) {
                println("push: t=%4.0fs %s".format(time, sink.stats()))
                nextStats += 1.0
            }
            val pts = base + MediaTime.fromSeconds(time)
            if (useAudio) {
                sink.writeAudio(media.audioFrames[audioIndex++], pts)
            } else {
                val (data, isSync) = media.accessUnits[videoIndex++]
                sink.writeVideo(data, pts, isSync)
            }
        }
        iteration += 1
    } while (options.loop)
    Thread.sleep(500)
    println("push: done")
    sink.stop()
    Thread.sleep(1_500)
    exitProcess(0)
}
