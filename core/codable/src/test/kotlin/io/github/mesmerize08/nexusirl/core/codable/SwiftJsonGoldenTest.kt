// Long regex / golden JSON literals are clearer unsplit.
@file:Suppress("ktlint:standard:max-line-length")

package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Golden vectors copied from upstream Swift tests (@97e26d2):
 *   MoblinTests/Moblin/RemoteControl/RemoteControlSuite.swift
 *   MoblinTests/Moblin/Moblink/MoblinkSuite.swift
 *
 * Swift's sorted-keys output is compared structurally: key order and `\/` escapes are
 * normalized, but number literals must match exactly (`1` is not `1.0`).
 */
class SwiftJsonGoldenTest {
    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun assertJson(expected: String, actual: String) {
        assertEquals(parse(expected), parse(actual), "actual JSON: $actual")
    }

    private fun encode(request: Request): String = SwiftJson.encodeToString(Request.Serializer, request)

    @Test
    fun gimbalRequests() {
        assertJson("""{"setGimbalTracking":{"on":true}}""", encode(Request.SetGimbalTracking(on = true)))
        assertJson("""{"setGimbalMovement":{"x":1,"y":-1}}""", encode(Request.SetGimbalMovement(1f, -1f)))
        assertJson(
            """{"animateGimbal":{"motion":{"kapow":{}}}}""",
            encode(Request.AnimateGimbal(GimbalMotion.Kapow)),
        )
        assertJson("""{"saveGimbalPreset":{}}""", encode(Request.SaveGimbalPreset))
    }

    @Test
    fun sendMessageRequest() {
        assertJson("""{"sendMessage":{"text":"Hello"}}""", encode(Request.SendMessage("Hello")))
    }

    @Test
    fun musicRequests() {
        assertJson("""{"music":{"command":{"play":{}}}}""", encode(Request.Music(MusicCommand.Play)))
        assertJson(
            """{"music":{"command":{"add":{"title":"Song"}}}}""",
            encode(Request.Music(MusicCommand.Add("Song"))),
        )
        assertJson(
            """{"music":{"command":{"add":{"title":"Song","user":"Erik"}}}}""",
            encode(Request.Music(MusicCommand.Add("Song", user = "Erik"))),
        )
        assertJson(
            """{"music":{"command":{"add":{"requestId":"ORDER1","title":"Song","user":"Erik"}}}}""",
            encode(Request.Music(MusicCommand.Add("Song", user = "Erik", requestId = "ORDER1"))),
        )
        assertJson(
            """{"music":{"command":{"find":{"title":"Song"}}}}""",
            encode(Request.Music(MusicCommand.Find("Song"))),
        )
        assertJson(
            """{"music":{"command":{"remove":{"id":"1"}}}}""",
            encode(Request.Music(MusicCommand.Remove("1"))),
        )
        val decoded = SwiftJson.decodeFromString(
            Request.Serializer,
            """{"music":{"command":{"add":{"title":"Song","user":"Erik"}}}}""",
        )
        val add = assertIs<MusicCommand.Add>(assertIs<Request.Music>(decoded).command)
        assertEquals("Song", add.title)
        assertEquals("Erik", add.user)
        assertNull(add.requestId)
    }

    @Test
    fun musicResponses() {
        val found = Response.MusicFind(MusicFoundSong(id = "1440818839", title = "Song", artist = "Band", duration = 213))
        assertJson(
            """{"musicFind":{"song":{"artist":"Band","duration":213,"id":"1440818839","title":"Song"}}}""",
            SwiftJson.encodeToString(Response.Serializer, found),
        )
        assertJson("""{"musicFind":{}}""", SwiftJson.encodeToString(Response.Serializer, Response.MusicFind(null)))
    }

    private fun createStats() = Stats(
        date = Instant.ofEpochSecond(1_723_350_366),
        timeZone = "Europe/Stockholm",
        speed = 5.5,
        averageSpeed = 7.25,
        altitude = 243.5,
        latitude = 55.60587,
        longitude = 13.00073,
        distance = 1700.75,
        splitDistance = 5400.5,
        slopePercent = 5.5,
        altitudeAscent = 120.25,
        altitudeDescent = 80.5,
        splitAltitudeAscent = 40.75,
        splitAltitudeDescent = 30.25,
        temperature = 22.0,
        feelsLikeTemperature = 17.0,
        windSpeed = 3.0,
        windGust = 8.5,
        country = "Sweden",
        countryFlag = "🇸🇪",
        state = "Skåne",
        area = "Malmö",
        city = "Malmö",
        neighborhood = "Möllevången",
        heartRates = mapOf("Watch" to 75, "Belt" to null),
        activeEnergyBurned = 350,
        workoutDistance = 4200,
        power = 210,
        stepCount = 8000,
        cyclingPower = 250,
        cyclingCadence = 90,
        cyclingSpeed = 10.0,
        gForce = GForce(now = 1.5, recentMax = 2.5, max = 3.5),
    )

    @Test
    fun stats() {
        val expected = """
            {
              "activeEnergyBurned" : 350,
              "altitude" : 243.5,
              "altitudeAscent" : 120.25,
              "altitudeDescent" : 80.5,
              "area" : "Malmö",
              "averageSpeed" : 7.25,
              "city" : "Malmö",
              "country" : "Sweden",
              "countryFlag" : "🇸🇪",
              "cyclingCadence" : 90,
              "cyclingPower" : 250,
              "cyclingSpeed" : 10,
              "date" : 745043166,
              "distance" : 1700.75,
              "feelsLikeTemperature" : 17,
              "gForce" : {
                "max" : 3.5,
                "now" : 1.5,
                "recentMax" : 2.5
              },
              "heartRates" : {
                "Belt" : null,
                "Watch" : 75
              },
              "latitude" : 55.60587,
              "longitude" : 13.00073,
              "neighborhood" : "Möllevången",
              "power" : 210,
              "slopePercent" : 5.5,
              "speed" : 5.5,
              "splitAltitudeAscent" : 40.75,
              "splitAltitudeDescent" : 30.25,
              "splitDistance" : 5400.5,
              "state" : "Skåne",
              "stepCount" : 8000,
              "temperature" : 22,
              "timeZone" : "Europe\/Stockholm",
              "windGust" : 8.5,
              "windSpeed" : 3,
              "workoutDistance" : 4200
            }
        """.trimIndent()
        assertJson(expected, SwiftJson.encodeToString(Stats.serializer(), createStats()))
    }

    @Test
    fun statsDecode() {
        val stats = createStats()
        val decoded = SwiftJson.decodeFromString(Stats.serializer(), SwiftJson.encodeToString(Stats.serializer(), stats))
        assertEquals(stats, decoded)
        assertEquals(mapOf("Watch" to 75, "Belt" to null), decoded.heartRates)
    }

    @Test
    fun moblinkDecodeStatusResponse() {
        // Note the extra "temperature" key: newer peers may add fields, which must be ignored.
        val message = SwiftJson.decodeFromString(
            MoblinkMessageToStreamer.Serializer,
            """{"response":{"id":5,"data":{"status":{"thermalState":"white","batteryPercentage":30,"temperature": 39}},"result":{"ok":{}}}}""",
        )
        val response = assertIs<MoblinkMessageToStreamer.Response>(message)
        assertEquals(5, response.id)
        assertEquals(MoblinkResult.Ok, response.result)
        val status = assertIs<MoblinkResponse.Status>(response.data)
        assertEquals(30, status.batteryPercentage)
        assertEquals(MoblinkThermalState.WHITE, status.thermalState)
    }

    @Test
    fun moblinkIdentifyUsesUppercaseUuid() {
        val id = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301")
        assertJson(
            """{"identify":{"id":"3F2504E0-4F89-11D3-9A0C-0305E82C3301","name":"Relay","authentication":"abc"}}""",
            SwiftJson.encodeToString(
                MoblinkMessageToStreamer.Serializer,
                MoblinkMessageToStreamer.Identify(id, "Relay", "abc"),
            ),
        )
    }
}
