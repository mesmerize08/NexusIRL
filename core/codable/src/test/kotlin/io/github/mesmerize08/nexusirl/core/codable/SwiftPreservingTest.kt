package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Inner.Serializer::class)
private data class Inner(
    val latency: Int = 3000,
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<Inner>(generatedSerializer())
}

@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = Outer.Serializer::class)
private data class Outer(
    val name: String = "My stream",
    val srt: Inner = Inner(),
    @Transient override var extra: JsonObject = JsonObject(emptyMap()),
) : SwiftPreserving {
    object Serializer : SwiftPreservingSerializer<Outer>(generatedSerializer())
}

class SwiftPreservingTest {
    @Test
    fun unknownKeysSurviveARoundTripAtEveryLevel() {
        val input = """{"name":"A","twitchChannelName":"x","srt":{"latency":2000,"bigPackets":true},"obs":{"a":[1,2]}}"""
        val decoded = SwiftJson.decodeFromString<Outer>(input)
        assertEquals("A", decoded.name)
        assertEquals(2000, decoded.srt.latency)
        val output = SwiftJson.encodeToString(Outer.Serializer, decoded.copy(name = "B"))
        assertEquals(
            SwiftJson.parseToJsonElement(
                """{"name":"B","twitchChannelName":"x","srt":{"latency":2000,"bigPackets":true},"obs":{"a":[1,2]}}""",
            ),
            SwiftJson.parseToJsonElement(output),
        )
    }

    @Test
    fun missingKeysUseDefaultsAndNothingExtraIsInvented() {
        val decoded = SwiftJson.decodeFromString<Outer>("{}")
        assertEquals(Outer(), decoded)
        assertEquals(setOf("name", "srt"), SwiftJson.encodeToJsonElement(Outer.Serializer, decoded).jsonObject.keys)
    }
}
