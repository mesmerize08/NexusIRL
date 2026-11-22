package io.github.mesmerize08.nexusirl.tools.srtlareceiver

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.transport.srt.srtla.server.SrtlaServer
import io.github.mesmerize08.nexusirl.transport.srt.srtla.server.SrtlaServerPathStatistics
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.system.exitProcess

/**
 * Desktop SRTLA receiver: accepts bonded SRTLA paths and relays them as one SRT connection to an
 * SRT listener on this machine (for example `ffmpeg -i "srt://0.0.0.0:4001?mode=listener" ...`,
 * OBS, or srt-live-transmit). Uses the SRTLA relay ported from Moblin.
 */
fun main(args: Array<String>) {
    val help = "--help" in args
    if (help || args.isEmpty()) {
        println(
            """
            srtla-receiver - receive SRTLA bonded streams and relay them to a local SRT listener

            Usage: srtla-receiver --srt-port PORT [--srtla-port 5000] [--bind ADDRESS]

              --srt-port PORT     UDP port of the SRT listener on 127.0.0.1 to relay to.
              --srtla-port PORT   UDP port to accept SRTLA on (default 5000).
              --bind ADDRESS     Local SRTLA bind address (default all interfaces).
            """.trimIndent(),
        )
        exitProcess(if (help) 0 else 2)
    }
    val (srtPort, srtlaPort, bindAddress) = try {
        parseOptions(args)
    } catch (error: IllegalArgumentException) {
        System.err.println("${error.message} See --help.")
        exitProcess(2)
    }
    val executor = SerialExecutor.create("srtla-receiver")
    val server = SrtlaServer(srtlaPort, srtPort, executor, HostClock.system, bindAddress)
    server.start()
    println(
        "srtla-receiver: listening for SRTLA on UDP ${server.localPort} (${server.localAddress?.hostAddress}), " +
            "relaying to SRT 127.0.0.1:$srtPort",
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    // Per path every 5 s while streaming: enough to analyse a field test from the logs afterwards.
    var previous = emptyMap<String, SrtlaServerPathStatistics>()
    while (true) {
        Thread.sleep(REPORT_MILLIS)
        val latch = java.util.concurrent.CountDownLatch(1)
        var paths = emptyList<SrtlaServerPathStatistics>()
        server.pathStatistics {
            paths = it
            latch.countDown()
        }
        latch.await()
        if (paths.isEmpty()) {
            if (previous.isNotEmpty()) {
                println("srtla-receiver: no bonded paths")
            }
        } else {
            println("srtla-receiver: ${server.numberOfClients} group(s), ${paths.size} path(s)")
            for (path in paths) {
                println("srtla-receiver:   " + describePath(path, previous[path.endpoint]))
            }
        }
        previous = paths.associateBy { it.endpoint }
    }
}

internal data class ReceiverOptions(val srtPort: Int, val srtlaPort: Int, val bindAddress: InetAddress?)

internal fun parseOptions(args: Array<String>): ReceiverOptions {
    val values = mutableMapOf<String, String>()
    for (pair in args.toList().chunked(2)) {
        val name = pair[0].removePrefix("--")
        require(name in setOf("srt-port", "srtla-port", "bind")) { "Unknown option: ${pair[0]}." }
        val value = pair.getOrNull(1)
        require(!value.isNullOrBlank() && !value.startsWith("--")) { "--$name requires a value." }
        require(values.putIfAbsent(name, value) == null) { "--$name was supplied more than once." }
    }
    val srtPort = values["srt-port"]?.toIntOrNull()
    require(srtPort != null && srtPort in 1..65535) { "--srt-port must be in 1..65535." }
    val srtlaPort = (values["srtla-port"] ?: "5000").toIntOrNull()
    require(srtlaPort != null && srtlaPort in 0..65535) { "--srtla-port must be in 0..65535." }
    val bindAddress = try {
        values["bind"]?.let { InetAddress.getByName(it) }
    } catch (error: UnknownHostException) {
        throw IllegalArgumentException("--bind could not be resolved.", error)
    }
    return ReceiverOptions(srtPort, srtlaPort, bindAddress)
}

private const val REPORT_MILLIS = 5_000L

/** "1.2.3.4:5678 112 pkt/s 1.20 Mbit/s (new)": the change since the last report. */
fun describePath(path: SrtlaServerPathStatistics, before: SrtlaServerPathStatistics?): String {
    val seconds = REPORT_MILLIS / 1000.0
    val packets = path.packets - (before?.packets ?: 0)
    val bytes = path.bytes - (before?.bytes ?: 0)
    val rate = String.format(java.util.Locale.US, "%.2f", bytes * 8 / seconds / 1_000_000)
    return "${path.endpoint} ${(packets / seconds).toLong()} pkt/s $rate Mbit/s" + if (before == null) " (new)" else ""
}
