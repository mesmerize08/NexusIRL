package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/** One tracker per stream is shared by the video selection thread and the audio output thread. */
class DriftTrackerThreadingTest {
    @Test
    fun publicMethodsAreSynchronized() {
        val names = listOf("addMedia", "getDrift", "setDrift", "update")
        for (name in names) {
            val method = DriftTracker::class.java.declaredMethods.single { it.name == name }
            assertTrue(Modifier.isSynchronized(method.modifiers), "$name is not synchronized")
        }
    }
}
