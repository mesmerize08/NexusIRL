package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IngestDecodingRegistryTest {
    @Test
    fun aThirdPlaceIsRefusedUntilOneIsGivenBack() {
        val registry = IngestDecodingRegistry(2)
        val places = List(3) { IngestDecodingPlace(registry) }
        assertTrue(places[0].take())
        assertTrue(places[1].take())
        assertFalse(places[2].take())
        assertEquals(2, registry.count)
        places[0].give()
        assertTrue(places[2].take())
        assertEquals(2, registry.count)
    }

    @Test
    fun takingAgainKeepsOnePlace() {
        val registry = IngestDecodingRegistry(2)
        val place = IngestDecodingPlace(registry)
        assertTrue(place.take())
        assertTrue(place.take())
        assertEquals(1, registry.count)
    }

    @Test
    fun givingTwiceGivesBackOnce() {
        val registry = IngestDecodingRegistry(2)
        val place = IngestDecodingPlace(registry)
        place.take()
        place.give()
        place.give()
        assertEquals(0, registry.count)
        IngestDecodingPlace(registry).give()
        assertEquals(0, registry.count)
    }

    @Test
    fun concurrentGivesNeverDriveTheCountNegative() {
        val registry = IngestDecodingRegistry(2)
        val lowest = AtomicInteger(0)
        repeat(500) {
            val place = IngestDecodingPlace(registry)
            assertTrue(place.take())
            val barrier = CyclicBarrier(4)
            val threads = List(4) {
                thread {
                    barrier.await()
                    place.give()
                    lowest.accumulateAndGet(registry.count, ::minOf)
                }
            }
            threads.forEach { it.join() }
            assertEquals(0, registry.count)
        }
        assertEquals(0, lowest.get())
    }
}
