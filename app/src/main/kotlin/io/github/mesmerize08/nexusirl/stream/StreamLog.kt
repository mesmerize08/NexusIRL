package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One file per stream with the once-a-second status line and every state change, stamped with
 * the phone's clock, so a stream far from a computer (a field test) can be analysed afterwards
 * next to the receiver's logs. Android's shared log buffer only keeps the last few minutes.
 *
 * Callers write no secrets (stream URLs only as `scheme://host`); diagnostics redact again when
 * sharing. Only the newest [KEEP] logs are kept, each up to [MAXIMUM_BYTES]. Thread-safe.
 */
class StreamLog private constructor(private val file: File?) {
    private var writer: BufferedWriter? = try {
        file?.bufferedWriter()
    } catch (e: IOException) {
        null
    }
    private var written = 0L
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun line(text: String) {
        val writer = writer ?: return
        val line = "${time.format(Date())} $text"
        if (written + line.length > MAXIMUM_BYTES) {
            return
        }
        try {
            writer.write(line)
            writer.write("\n")
            writer.flush()
            written += line.length + 1
        } catch (e: IOException) {
            close()
        }
    }

    @Synchronized
    fun close() {
        try {
            writer?.close()
        } catch (e: IOException) {
            // Nothing more to write.
        }
        writer = null
    }

    companion object {
        /** Enough for a field-test day with several streams. */
        const val KEEP = 10

        /** About 4 hours of status lines. */
        const val MAXIMUM_BYTES = 4L * 1024 * 1024
        private const val DIRECTORY = "stream-logs"

        fun directory(context: Context) = File(context.filesDir, DIRECTORY)

        /** Starts a new log, removing all but the newest logs. Never throws: logging is optional. */
        fun open(context: Context): StreamLog = open(directory(context), Date())

        internal fun open(directory: File, now: Date): StreamLog {
            val file = try {
                directory.mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
                newest(directory, Int.MAX_VALUE).drop(KEEP - 1).forEach { it.delete() }
                File(directory, "stream-$stamp.log")
            } catch (e: Exception) {
                null
            }
            return StreamLog(file).apply {
                line("Log started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}")
            }
        }

        /** The newest logs first. */
        fun newest(directory: File, count: Int): List<File> =
            (directory.listFiles { file -> file.name.startsWith("stream-") && file.name.endsWith(".log") } ?: emptyArray())
                .sortedByDescending { it.name }
                .take(count)
    }
}
