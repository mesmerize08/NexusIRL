package io.github.mesmerize08.nexusirl.tools.srtlareceiver

import io.github.mesmerize08.nexusirl.core.clock.HostClock
import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import io.github.mesmerize08.nexusirl.transport.srt.srtla.server.SrtlaServer
import kotlin.system.exitProcess

/**
 * Desktop SRTLA receiver: accepts bonded SRTLA paths and relays them as one SRT connection to an
 * SRT listener on this machine (for example `ffmpeg -i "srt://0.0.0.0:4001?mode=listener" ...`,
 * OBS, or srt-live-transmit). Uses the SRTLA relay ported from Moblin.
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).filter { it.size == 2 }.associate { it[0].removePrefix("--") to it[1] }
    if ("help" in args.map { it.removePrefix("--") } || options["srt-port"] == null) {
        println(
            """
            srtla-receiver - receive SRTLA bonded streams and relay them to a local SRT listener

            Usage: srtla-receiver --srt-port PORT [--srtla-port 5000]

              --srt-port PORT     UDP port of the SRT listener on 127.0.0.1 to relay to.
              --srtla-port PORT   UDP port to accept SRTLA on (default 5000).
            """.trimIndent(),
        )
        exitProcess(if (options["srt-port"] == null) 2 else 0)
    }
    val srtPort = options.getValue("srt-port").toInt()
    val srtlaPort = options["srtla-port"]?.toInt() ?: 5000
    val executor = SerialExecutor.create("srtla-receiver")
    val server = SrtlaServer(srtlaPort, srtPort, executor, HostClock.system)
    server.start()
    println("srtla-receiver: listening for SRTLA on UDP ${server.localPort}, relaying to SRT 127.0.0.1:$srtPort")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    while (true) {
        Thread.sleep(5_000)
        println("srtla-receiver: ${server.numberOfClients} bonded group(s)")
    }
}
