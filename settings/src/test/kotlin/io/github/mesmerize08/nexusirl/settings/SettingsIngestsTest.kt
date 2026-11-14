package io.github.mesmerize08.nexusirl.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsIngestsTest {
    private val id = "0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11"

    @Test
    fun defaultsMatchUpstream() {
        val database = Database.fromJson("{}")
        assertEquals(SettingsRtmpServer(), database.rtmpServer)
        assertFalse(database.rtmpServer.enabled)
        assertEquals(1935, database.rtmpServer.port)
        assertTrue(database.rtmpServer.streams.isEmpty())
        assertFalse(database.ingestsSoftwareVideoDecoding)
    }

    @Test
    fun iosRtmpServerRoundTrips() {
        val stream = """{"id":"$id","name":"GoPro","streamKey":"1","latency":500,"futureKey":7}"""
        val json = """{"rtmpServer":{"enabled":true,"port":11935,"streams":[$stream]},"ingestsSoftwareVideoDecoding":true}"""
        val database = Database.fromJson(json)
        val server = database.rtmpServer
        assertTrue(server.enabled)
        assertEquals(11935, server.port)
        val parsed = server.streams.single()
        assertEquals("GoPro", parsed.name)
        assertEquals("1", parsed.streamKey)
        assertEquals(500, parsed.latency)
        assertEquals(0.5, parsed.latencySeconds)
        assertTrue(database.ingestsSoftwareVideoDecoding)
        val out = database.toJson()
        assertTrue("\"futureKey\":7" in out, out)
        assertTrue(id in out, out)
    }

    @Test
    fun sceneKeys() {
        val json = """{"scenes":[{"name":"Drone","cameraPosition":"RTMP","rtmpCameraId":"$id","overrideMic":true,"micId":"$id 0"}]}"""
        val scene = Database.fromJson(json).scenes.single()
        assertEquals(id, scene.rtmpCameraId.toString().uppercase())
        assertTrue(scene.overrideMic)
        assertEquals("$id 0", scene.micId)
        assertEquals("RTMP", scene.cameraPosition)
        val out = Database.fromJson(json).toJson()
        assertTrue("\"rtmpCameraId\":\"$id\"" in out, out)
        assertTrue("\"overrideMic\":true" in out, out)
        assertTrue("\"micId\":\"$id 0\"" in out, out)
        assertTrue("\"cameraPosition\":\"RTMP\"" in out, out)
    }

    @Test
    fun validators() {
        assertEquals("Not a number", IngestValidators.latency("abc"))
        assertEquals("Too small", IngestValidators.latency("4"))
        assertEquals("Too big", IngestValidators.latency("10001"))
        assertNull(IngestValidators.latency("5"))
        assertNull(IngestValidators.latency("10000"))
        assertEquals("Too small", IngestValidators.port("0"))
        assertEquals("Too big", IngestValidators.port("65536"))
        assertEquals("Not a number", IngestValidators.port("-1"))
        assertEquals("Not a number", IngestValidators.port(""))
        assertNull(IngestValidators.port("1935"))
    }

    @Test
    fun randomHumanStringShape() {
        // 15 bytes are 20 Base64 characters; upstream then removes any + and /, so a value can be shorter.
        val values = List(100) { randomHumanString() }
        assertTrue(values.all { it.length in 10..20 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' } }, values.toString())
        assertEquals(100, values.toSet().size)
    }

    @Test
    fun streamCamera() {
        assertEquals("GoPro (RTMP)", SettingsRtmpServerStream(name = "GoPro").camera)
    }
}
