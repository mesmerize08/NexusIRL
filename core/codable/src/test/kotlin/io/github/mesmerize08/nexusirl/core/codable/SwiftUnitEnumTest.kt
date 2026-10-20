package io.github.mesmerize08.nexusirl.core.codable

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SwiftUnitEnumTest {
    private enum class Filter(val swiftName: String) {
        GRAY_SCALE("grayScale"),
        FOUR_THREE("fourThree"),
        UNKNOWN("unknown"),
    }

    private object FilterSerializer : SwiftUnitEnumSerializer<Filter>("Filter", Filter.entries, { it.swiftName }, Filter.UNKNOWN)

    private object StrictSerializer : SwiftUnitEnumSerializer<Filter>("Filter", Filter.entries, { it.swiftName })

    @Test
    fun likeSwift() {
        assertEquals("""{"grayScale":{}}""", SwiftJson.encodeToString(FilterSerializer, Filter.GRAY_SCALE))
        assertEquals(Filter.FOUR_THREE, SwiftJson.decodeFromString(FilterSerializer, """{"fourThree":{}}"""))
        assertEquals(Filter.UNKNOWN, SwiftJson.decodeFromString(FilterSerializer, """{"sparkle":{}}"""))
        assertFailsWith<SerializationException> { SwiftJson.decodeFromString(StrictSerializer, """{"sparkle":{}}""") }
        assertFailsWith<SerializationException> { SwiftJson.decodeFromString(StrictSerializer, """{"a":{},"b":{}}""") }
    }
}
