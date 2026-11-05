package io.github.mesmerize08.nexusirl.media.mux.testing

import java.net.DatagramSocket

/** A UDP port that was free a moment ago (bound to port 0, then released). */
fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

/**
 * Sleeps in 1 ms steps until [seconds] after [startNanos] (a [System.nanoTime] value), so interop
 * tests feed media in real time, as the capture pipeline does.
 */
fun waitUntil(startNanos: Long, seconds: Double) {
    val deadline = startNanos + (seconds * 1e9).toLong()
    while (System.nanoTime() < deadline) {
        Thread.sleep(1)
    }
}
