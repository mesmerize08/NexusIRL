package io.github.mesmerize08.nexusirl.media.mux.testing

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Ffmpeg.run] with a stand-in child (this JVM's `java` running a one-file program) instead of ffmpeg. */
class FfmpegRunTest {
    private val java = ProcessHandle.current().info().command().get()

    private fun program(body: String): File {
        val file = Files.createTempDirectory("ffmpeg-run").resolve("Child.java").toFile()
        file.writeText("public class Child { public static void main(String[] a) throws Exception { $body } }")
        return file
    }

    @Test
    fun returnsTheExitCodeAndOutput() {
        val child = program("System.out.println(\"hello\"); System.exit(3);")
        val (exit, output) = Ffmpeg.run(java, child.path)
        assertEquals(3, exit)
        assertEquals("hello", output.trim())
    }

    @Test
    fun aChildThatNeverExitsIsKilledAtTheDeadline() {
        // Prints, then holds its output open forever: reading the output to the end first would never return.
        val child = program("System.out.println(\"started\"); System.out.flush(); Thread.sleep(Long.MAX_VALUE);")
        val error = assertTimeoutPreemptively<IllegalStateException>(Duration.ofSeconds(60)) {
            assertFailsWith<IllegalStateException> { Ffmpeg.run(java, child.path, timeoutSeconds = 5) }
        }
        val message = error.message.orEmpty()
        assertTrue("timed out after 5 s" in message, message)
        assertTrue("Child.java" in message, message)
    }
}
