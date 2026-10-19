package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwiftPrimitivesTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): String =
        SwiftJson.encodeToString(serializer, value).also {
            assertEquals(value, SwiftJson.decodeFromString(serializer, it))
        }

    @Test
    fun numbersFormatLikeSwift() {
        assertEquals("1", formatSwiftNumber(1.0))
        assertEquals("-1", formatSwiftNumber(-1.0))
        assertEquals("0", formatSwiftNumber(0.0))
        assertEquals("0", formatSwiftNumber(-0.0))
        assertEquals("1700.75", formatSwiftNumber(1700.75))
        assertEquals("55.60587", formatSwiftNumber(55.60587))
        assertEquals("0.1", formatSwiftNumber(0.1))
        assertEquals("745043166", formatSwiftNumber(745043166.0))
        assertEquals("0.1", formatSwiftNumber(0.1f.toDouble(), 0.1f.toString()))
        assertFailsWith<SerializationException> { formatSwiftNumber(Double.NaN) }
        assertFailsWith<SerializationException> { formatSwiftNumber(Double.POSITIVE_INFINITY) }
    }

    @Test
    fun doubleAndFloatSerializers() {
        assertEquals("2", roundTrip(SwiftDoubleSerializer, 2.0))
        assertEquals("2.5", roundTrip(SwiftDoubleSerializer, 2.5))
        assertEquals("2", roundTrip(SwiftFloatSerializer, 2f))
        assertEquals("0.3", roundTrip(SwiftFloatSerializer, 0.3f))
    }

    @Test
    fun uuidIsUppercaseAndDecodesAnyCase() {
        val id = UUID.fromString("a1b2c3d4-e5f6-4789-90ab-cdef01234567")
        assertEquals("\"A1B2C3D4-E5F6-4789-90AB-CDEF01234567\"", roundTrip(SwiftUuidSerializer, id))
        assertEquals(id, SwiftJson.decodeFromString(SwiftUuidSerializer, "\"a1b2c3d4-e5f6-4789-90ab-cdef01234567\""))
        assertFailsWith<SerializationException> { SwiftJson.decodeFromString(SwiftUuidSerializer, "\"not-a-uuid\"") }
    }

    @Test
    fun dateUsesAppleReferenceEpoch() {
        assertEquals("0", roundTrip(AppleDateSerializer, Instant.parse("2001-01-01T00:00:00Z")))
        assertEquals("745043166", roundTrip(AppleDateSerializer, Instant.ofEpochSecond(1_723_350_366)))
        assertEquals("-978307200", roundTrip(AppleDateSerializer, Instant.EPOCH))
        assertEquals("0.5", roundTrip(AppleDateSerializer, Instant.parse("2001-01-01T00:00:00.500Z")))
    }

    @Test
    fun dataIsBase64() {
        val bytes = byteArrayOf(0, 1, 2, -1, 127)
        val encoded = SwiftJson.encodeToString(SwiftDataSerializer, bytes)
        assertEquals("\"AAEC/38=\"", encoded)
        assertContentEquals(bytes, SwiftJson.decodeFromString(SwiftDataSerializer, encoded))
        assertFailsWith<SerializationException> { SwiftJson.decodeFromString(SwiftDataSerializer, "\"%%%\"") }
    }

    @Test
    fun enumKeyedDictionaryIsFlatArray() {
        val serializer = SwiftKeyedArrayMapSerializer(MoblinkResult.Serializer, Boolean.serializer())
        val value = linkedMapOf<MoblinkResult, Boolean>(MoblinkResult.Ok to true, MoblinkResult.WrongPassword to false)
        assertEquals("""[{"ok":{}},true,{"wrongPassword":{}},false]""", roundTrip(serializer, value))
        assertFailsWith<SerializationException> {
            SwiftJson.decodeFromString(serializer, """[{"ok":{}}]""")
        }
    }

    @Test
    fun enumRequiresExactlyOneCaseKey() {
        assertFailsWith<SerializationException> {
            SwiftJson.decodeFromString(MoblinkResult.Serializer, """{"ok":{},"wrongPassword":{}}""")
        }
        assertFailsWith<SerializationException> { SwiftJson.decodeFromString(MoblinkResult.Serializer, "{}") }
        assertFailsWith<SerializationException> {
            SwiftJson.decodeFromString(MoblinkResult.Serializer, """{"somethingNew":{}}""")
        }
    }

    private object ResultWithFallbackSerializer : SwiftEnumSerializer<MoblinkResult>(
        "MoblinkResult",
        listOf(swiftCase("ok", MoblinkResult.Ok.serializer())),
        fallback = MoblinkResult.WrongPassword,
    )

    @Test
    fun unknownEnumCaseUsesFallback() {
        assertEquals(
            MoblinkResult.WrongPassword,
            SwiftJson.decodeFromString(ResultWithFallbackSerializer, """{"somethingNew":{"x":1}}"""),
        )
    }

    private object ThermalWithFallbackSerializer : SwiftRawValueEnumSerializer<MoblinkThermalState>(
        "MoblinkThermalState",
        MoblinkThermalState.entries,
        fallback = MoblinkThermalState.WHITE,
    )

    @Test
    fun rawValueEnums() {
        assertEquals("\"yellow\"", roundTrip(MoblinkThermalState.Serializer, MoblinkThermalState.YELLOW))
        assertFailsWith<SerializationException> {
            SwiftJson.decodeFromString(MoblinkThermalState.Serializer, "\"purple\"")
        }
        assertEquals(MoblinkThermalState.WHITE, SwiftJson.decodeFromString(ThermalWithFallbackSerializer, "\"purple\""))
    }
}
