package io.github.mesmerize08.nexusirl.settings

import io.github.mesmerize08.nexusirl.core.codable.SwiftJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SrtConnectionPrioritiesTest {
    private val cellularId = UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11")
    private val relayEntryId = UUID.fromString("5E0F7D6C-2B1A-4C3D-8E9F-0A1B2C3D4E5F")
    private val relayId = UUID.fromString("8C7D4E5F-1A2B-4C3D-9E8F-7A6B5C4D3E2F")

    // As upstream SettingsStreamSrtConnectionPriority encodes it (relayId: null when not a relay).
    private val iosSrt = """
        {"latency": 2000, "connectionPriorities": {"enabled": true, "futureKey": 1, "priorities": [
          {"id": "0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11", "name": "Cellular", "priority": 7, "enabled": true, "relayId": null},
          {"id": "1A2B3C4D-5E6F-4A1B-8C2D-3E4F5A6B7C8D", "name": "WiFi", "priority": 2, "enabled": false},
          {"id": "5E0F7D6C-2B1A-4C3D-8E9F-0A1B2C3D4E5F", "name": "Pixel", "priority": 10, "enabled": true,
           "relayId": "8C7D4E5F-1A2B-4C3D-9E8F-7A6B5C4D3E2F", "futureEntryKey": "x"}
        ]}}
    """.trimIndent()

    private fun decode(json: String) = SwiftJson.decodeFromString(SettingsStreamSrt.Serializer, json)

    @Test
    fun decodesUpstreamJson() {
        val priorities = decode(iosSrt).connectionPriorities
        assertTrue(priorities.enabled)
        assertEquals(listOf("Cellular", "WiFi", "Pixel"), priorities.priorities.map { it.name })
        assertEquals(listOf(7, 2, 10), priorities.priorities.map { it.priority })
        assertEquals(listOf(true, false, true), priorities.priorities.map { it.enabled })
        assertEquals(cellularId, priorities.priorities[0].id)
        assertNull(priorities.priorities[0].relayId)
        assertEquals(relayId, priorities.priorities[2].relayId)
    }

    @Test
    fun encodeRoundTripKeepsUnknownKeysAndUppercaseUuids() {
        val encoded = SwiftJson.encodeToString(SettingsStreamSrt.Serializer, decode(iosSrt))
        val priorities = Json.parseToJsonElement(encoded).jsonObject["connectionPriorities"]!!.jsonObject
        assertEquals("1", priorities["futureKey"].toString())
        assertTrue(encoded.contains("\"id\":\"0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11\""))
        assertTrue(encoded.contains("\"relayId\":\"8C7D4E5F-1A2B-4C3D-9E8F-7A6B5C4D3E2F\""))
        assertTrue(encoded.contains("\"futureEntryKey\":\"x\""))
        assertTrue(encoded.contains("\"enabled\":true,\"priorities\""))
        assertEquals(decode(iosSrt), decode(encoded))
    }

    @Test
    fun defaultsWhenMissing() {
        // Upstream defaults: disabled, Cellular and WiFi at priority 1, enabled.
        val priorities = decode("{}").connectionPriorities
        assertFalse(priorities.enabled)
        assertEquals(listOf("Cellular", "WiFi"), priorities.priorities.map { it.name })
        assertTrue(priorities.priorities.all { it.priority == 1 && it.enabled && it.relayId == null })
        assertEquals(2, priorities.priorities.map { it.id }.distinct().size)
        val entry = SwiftJson.decodeFromString(SettingsStreamSrtConnectionPriority.Serializer, "{}")
        assertEquals("", entry.name)
        assertEquals(1, entry.priority)
        assertTrue(entry.enabled)
        // Empty arrays stay empty, as upstream.
        assertEquals(emptyList(), decode("""{"connectionPriorities": {"priorities": []}}""").connectionPriorities.priorities)
    }

    @Test
    fun relayTunnelAddsThenRenames() {
        // Upstream moblinkStreamerTunnelAdded.
        val start = SettingsStreamSrtConnectionPriorities()
        val added = start.withRelay(relayId, "Pixel")
        val entry = added.priorities.last()
        assertEquals(3, added.priorities.size)
        assertEquals("Pixel", entry.name)
        assertEquals(relayId, entry.relayId)
        assertEquals(1, entry.priority)
        assertTrue(entry.enabled)
        val renamed = added.withPriority(entry.id, 4, false).withRelay(relayId, "Pixel 9")
        assertEquals(3, renamed.priorities.size)
        assertEquals(SettingsStreamSrtConnectionPriority(entry.id, "Pixel 9", 4, false, relayId), renamed.priorities.last())
        assertSame(renamed, renamed.withRelay(relayId, "Pixel 9"))
    }

    @Test
    fun wiredInterfaceAddedOnce() {
        // Upstream handleIpStatusUpdate: a wired Ethernet interface gets an entry by its name.
        val added = SettingsStreamSrtConnectionPriorities().withInterface("eth0")
        assertEquals(listOf("Cellular", "WiFi", "eth0"), added.priorities.map { it.name })
        assertSame(added, added.withInterface("eth0"))
    }

    @Test
    fun setPriorityClampsAndIgnoresUnknownIds() {
        // Upstream remoteControlStreamerSetSrtConnectionPriority.
        val start = decode(iosSrt).connectionPriorities
        assertEquals(10, start.withPriority(cellularId, 50, true).priorities[0].priority)
        assertEquals(1, start.withPriority(cellularId, -3, false).priorities[0].priority)
        assertFalse(start.withPriority(cellularId, 5, false).priorities[0].enabled)
        assertSame(start, start.withPriority(UUID.randomUUID(), 5, false))
        assertEquals(10, clampConnectionPriority(11))
        assertEquals(1, clampConnectionPriority(0))
    }

    @Test
    fun onlyOtherEntriesCanBeDeleted() {
        val start = decode(iosSrt).connectionPriorities
        assertEquals(listOf(false, false, true), start.priorities.map { it.canDelete })
        assertEquals(listOf("Cellular", "WiFi"), start.without(relayEntryId).priorities.map { it.name })
        assertSame(start, start.without(cellularId))
    }

    @Test
    fun protocolNamesLikeUpstream() {
        fun stream(url: String) = SettingsStream(url = url)
        assertTrue(stream("srtla://host:5000{secret:0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11}").isSrtla)
        assertFalse(stream("srt://host:5000").isSrtla)
        assertEquals(
            listOf("SRTLA", "SRT", "RTMP", "RTMPS", "RIST", "WHIP", "WHIP", "Mobcam", "RTMP"),
            listOf("srtla://h", "srt://h", "rtmp://h", "rtmps://h", "rist://h", "whip://h", "whips://h", "mobcam://h", "x").map {
                stream(it).protocolString()
            },
        )
    }
}

class DatabaseConnectionPrioritiesTest {
    private val a = SettingsStream(name = "A")
    private val b = SettingsStream(name = "B")
    private val database = Database(streams = listOf(a, b))

    @Test
    fun updatesOneStreamsPriorities() {
        val updated = database.updateConnectionPriorities(b.id) { it.copy(enabled = true) }
        assertFalse(updated.streams[0].srt.connectionPriorities.enabled)
        assertTrue(updated.streams[1].srt.connectionPriorities.enabled)
        assertSame(database, database.updateConnectionPriorities(UUID.randomUUID()) { it.copy(enabled = true) })
    }

    @Test
    fun wiredInterfaceGoesToEveryStream() {
        // Upstream handleIpStatusUpdate adds it to all streams.
        val updated = database.withWiredInterface("eth0")
        assertTrue(updated.streams.all { stream -> stream.srt.connectionPriorities.priorities.any { it.name == "eth0" } })
        assertSame(updated, updated.withWiredInterface("eth0"))
    }
}
