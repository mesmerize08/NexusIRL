package io.github.mesmerize08.nexusirl.stream

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamLogTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun writesStampedLines() {
        val log = StreamLog.open(directory, Date(0))
        log.line("Encoded 30 frames/s state=LIVE")
        log.close()
        log.line("after close is ignored")
        val lines = StreamLog.newest(directory, 1).single().readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Log started 1970-01-01"), lines[0])
        assertTrue(Regex("""^\d\d:\d\d:\d\d\.\d{3} Encoded 30 frames/s state=LIVE$""").matches(lines[1]), lines[1])
    }

    @Test
    fun keepsOnlyTheNewestLogs() {
        for (second in 0 until StreamLog.KEEP + 3) {
            StreamLog.open(directory, Date(second * 1000L)).close()
        }
        val kept = StreamLog.newest(directory, Int.MAX_VALUE)
        assertEquals(StreamLog.KEEP, kept.size)
        assertTrue(kept.first().name > kept.last().name, "newest first")
    }

    @Test
    fun stopsAtTheSizeLimit() {
        val log = StreamLog.open(directory, Date(0))
        val line = "x".repeat(1000)
        repeat((StreamLog.MAXIMUM_BYTES / 1000 + 100).toInt()) { log.line(line) }
        log.close()
        assertTrue(StreamLog.newest(directory, 1).single().length() <= StreamLog.MAXIMUM_BYTES)
    }
}
