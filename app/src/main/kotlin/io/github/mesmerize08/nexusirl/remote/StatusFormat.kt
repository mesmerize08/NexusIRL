// Port of formatBytesPerSecond and the size and uptime formatters (Common/Various/CommonUtils.swift,
// Moblin/Various/Model/ModelStream.swift) @97e26d2.
package io.github.mesmerize08.nexusirl.remote

import java.util.Locale

/**
 * Status texts in upstream's formats: Apple's `ByteCountFormatter` (decimal units, whole kB,
 * one decimal for MB and GB) and an abbreviated `DateComponentsFormatter` for uptime. Remote
 * assistants show them as they are, and Moblin's test harness parses them.
 */
object StatusFormat {
    /** For example "5.0 Mbps", "800 kbps". */
    fun bitsPerSecond(bits: Long): String = scaled(bits, listOf("bps", "kbps", "Mbps", "Gbps"))

    /** For example "12.3 MB", "640 kB". */
    fun bytes(bytes: Long): String = if (bytes < 1000) "$bytes bytes" else scaled(bytes, listOf("bytes", "kB", "MB", "GB"))

    /** For example "1h 2m 3s", "45s"; zero units are left out, like upstream. */
    fun uptime(seconds: Long): String {
        val parts = listOf(seconds / 86_400 to "d", seconds / 3600 % 24 to "h", seconds / 60 % 60 to "m", seconds % 60 to "s")
            .filter { it.first > 0 }
            .joinToString(" ") { "${it.first}${it.second}" }
        return parts.ifEmpty { "0s" }
    }

    private fun scaled(value: Long, units: List<String>): String {
        var scaled = value.toDouble()
        var unit = 0
        while (scaled >= 1000 && unit < units.size - 1) {
            scaled /= 1000
            unit += 1
        }
        return when (unit) {
            0 -> "$value ${units[0]}"
            1 -> "${scaled.toLong()} ${units[1]}"
            else -> String.format(Locale.US, "%.1f %s", scaled, units[unit])
        }
    }
}
