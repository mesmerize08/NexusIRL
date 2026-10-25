package io.github.mesmerize08.nexusirl.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import io.github.mesmerize08.nexusirl.BuildConfig
import io.github.mesmerize08.nexusirl.core.secrets.Redactor
import io.github.mesmerize08.nexusirl.media.pipeline.EncoderCapabilities
import io.github.mesmerize08.nexusirl.moblink.MoblinkController
import io.github.mesmerize08.nexusirl.moblink.MoblinkStreamerController
import io.github.mesmerize08.nexusirl.stream.Microphones
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.stream.StreamLog
import io.github.mesmerize08.nexusirl.ui.summary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A text report for bug reports: app and phone details, what the app is doing, the settings
 * without secrets, and the app's own recent log (Android lets an app read its own log without
 * any permission). The app has no crash reporting or analytics (docs/PORT_PLAN.md §9.8), so this
 * is how testers send what happened. Everything passes through the [Redactor]: stream URL paths
 * and queries, credential patterns and every value in the secret store are masked.
 */
object Diagnostics {
    /** Recent log lines to include; about a few minutes of a live stream. */
    private const val LOG_LINES = 4000

    /** Stream logs to include: all that are kept, so a field-test day arrives whole. */
    private const val STREAM_LOGS = StreamLog.KEEP

    /** Joins the sections and the log, redacting everything. Pure, so it can be tested. */
    fun compose(sections: List<Pair<String, List<String>>>, log: List<String>, redactor: Redactor): String = buildString {
        appendLine("NexusIRL diagnostics. Stream keys, passwords and tokens are removed;")
        appendLine("please check before sharing.")
        for ((title, lines) in sections + ("Log (newest last)" to log)) {
            appendLine()
            appendLine("== $title ==")
            lines.forEach { appendLine(redactor.redact(it)) }
        }
    }

    /** Writes the report to the app's cache. Slow (reads the log); call off the main thread. */
    fun create(context: Context, sections: List<Pair<String, List<String>>>): File {
        val redactor = StreamController.settings(context).redactor()
        val text = compose(sections + streamLogs(context), readOwnLog(), redactor)
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        // Only the newest report is kept.
        directory.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(directory, "nexusirl-diagnostics-$stamp.txt").apply { writeText(text) }
    }

    /** Opens the share sheet for [file] (email, chat, Drive, or save to Files). */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NexusIRL ${BuildConfig.VERSION_NAME} diagnostics")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** What the app knows now. Call on the main thread (reads the controllers' state). */
    fun sections(context: Context): List<Pair<String, List<String>>> {
        val database = StreamController.settings(context).current
        val status = StreamController.status.value
        val camera = StreamController.camera.value
        return listOf(
            "App" to listOf(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.BUILD_TYPE}",
                "Created ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}",
            ),
            "Phone" to listOfNotNull(
                "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Chipset ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null,
                "ABIs ${Build.SUPPORTED_ABIS.joinToString()}",
            ),
            "Status" to listOf(
                "State ${status.state}, uptime ${status.uptimeSeconds} s, reconnects ${status.reconnects}",
                "${status.fps} fps, ${status.videoBitrateKbps} kbps, RTT ${status.rttMs.toInt()} ms, thermal ${status.thermal}",
                "Encoder ${status.encoder.ifEmpty { "-" }}, microphone ${status.microphone.ifEmpty { "-" }}",
                "Detail ${status.detail.ifEmpty { "-" }}",
                "Recording ${StreamController.recording.value != null}",
            ),
            "Camera" to listOf(
                "Current ${camera.current?.label ?: "-"}, zoom ${camera.zoom} " +
                    "(${camera.current?.minZoom ?: "-"}..${camera.current?.maxZoom ?: "-"}), ${camera.cameras.size} cameras",
            ) + camera.cameras.map { "  ${it.label}: sensor ${it.sensorOrientation} deg, torch ${it.hasTorch}" },
            "Microphones" to Microphones.mics.value.map {
                (if (it.id == Microphones.selected.value?.id) "* " else "  ") + it.name
            },
            "Video encoders" to EncoderCapabilities.all.map {
                "${it.name} ${it.mimeType} ${if (it.hardware) "HW" else "SW"} 1080p60=${it.supports1080p60} 4k30=${it.supports4k30}"
            },
            "Networks" to networks(context),
            "Settings (no secrets)" to listOf(
                "Streams: ${database.streams.size}, selected: ${database.selectedStream?.name ?: "-"}",
            ) + database.streams.map { "  ${it.name}: ${it.summary()}" } + listOf(
                "Scenes ${database.scenes.size}, widgets ${database.widgets.size}",
                "Remote control: assistant link ${database.remoteControl.streamer.enabled}, " +
                    "web ${database.remoteControl.web.enabled}",
                "Moblink: relay running ${MoblinkController.isRunning}, " +
                    "streamer ${MoblinkStreamerController.relays.value?.size?.let { "$it relays" } ?: "off"}",
                "Location ${database.location.enabled}",
            ),
        )
    }

    private fun networks(context: Context): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val transports = TRANSPORTS.filter { capabilities.hasTransport(it.first) }.joinToString("+") { it.second }
            val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            "$transports internet=$internet down=${capabilities.linkDownstreamBandwidthKbps} kbps"
        }
    }

    /** The kept stream logs, newest first and whole: they reach back further than Android's log buffer. */
    private fun streamLogs(context: Context): List<Pair<String, List<String>>> =
        StreamLog.newest(StreamLog.directory(context), STREAM_LOGS).map { file ->
            "Stream log ${file.name}" to try {
                file.readLines()
            } catch (e: Exception) {
                listOf("Could not read: $e")
            }
        }

    private fun readOwnLog(): List<String> = try {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "-t",
            LOG_LINES.toString(),
            "--pid",
            android.os.Process.myPid().toString(),
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)
        lines
    } catch (e: Exception) {
        listOf("Could not read the log: $e")
    }

    private const val DIRECTORY = "diagnostics"

    private val TRANSPORTS = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "mobile",
        NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
        NetworkCapabilities.TRANSPORT_VPN to "VPN",
        NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth",
    )
}
