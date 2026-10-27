// Port of Moblin/Various/BondingStatisticsFormatter.swift @97e26d2 (the texts; the pie chart
// colours are UI).
package io.github.mesmerize08.nexusirl.transport.srt.srtla

/** The bonding status texts: each path's share of the data and each path's round-trip time. */
data class BondingStatistics(
    /** For example "60% Cellular, 40% WiFi". */
    val usage: String,
    /** For example "48 ms Cellular, 12 ms WiFi". */
    val rtts: String,
)

/** Upstream `BondingStatisticsFormatter.format`: null without paths. */
fun formatBondingStatistics(connections: List<BondingConnection>): BondingStatistics? {
    if (connections.isEmpty()) {
        return null
    }
    val total = connections.sumOf { it.usage }.coerceAtLeast(1)
    val percentages = connections.map { 100 * it.usage / total }.toMutableList()
    // The last path gets the rounding remainder, so the shares add up to 100.
    percentages[percentages.lastIndex] = 100 - percentages.dropLast(1).sum()
    return BondingStatistics(
        usage = connections.indices.joinToString(", ") { "${percentages[it]}% ${connections[it].name}" },
        rtts = connections.joinToString(", ") { "${it.rtt} ms ${it.name}" },
    )
}
