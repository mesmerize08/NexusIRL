package io.github.mesmerize08.nexusirl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.settings.MoblinSettingsUrl
import io.github.mesmerize08.nexusirl.stream.StreamConfig
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.stream.ThermalState
import io.github.mesmerize08.nexusirl.ui.CameraPreview
import io.github.mesmerize08.nexusirl.ui.ImportExportScreen
import io.github.mesmerize08.nexusirl.ui.SettingsLinkDialog
import io.github.mesmerize08.nexusirl.ui.StreamEditorScreen
import io.github.mesmerize08.nexusirl.ui.StreamsScreen
import io.github.mesmerize08.nexusirl.ui.summary
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** A `moblin://` link waiting for confirmation. */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleLinkIntent(intent)
        }
        // Stream editors and import/export can show stream keys (docs/PORT_PLAN.md §9.4).
        // Debug builds only: automated UI tests may turn it off to take screenshots.
        if (!(BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_NO_SECURE, false))) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        requestPermissionsIfNeeded()
        updateDisplayRotation()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(pendingLink)
                }
            }
        }
        // Debug builds only: allow automated tests to start a stream via an intent extra.
        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEBUG_URL)?.let {
                val video = DEFAULT_SETTINGS.copy(hevc = intent.getBooleanExtra(EXTRA_DEBUG_HEVC, false))
                StreamController.goLive(this, StreamConfig(url = it, video = video, srtLatency = 2000))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        StreamController.setUiVisible(this, true)
    }

    override fun onStop() {
        StreamController.setUiVisible(this, false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLinkIntent(intent)
    }

    private fun handleLinkIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("moblin", ignoreCase = true)) {
            pendingLink.value = intent.dataString
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDisplayRotation()
    }

    private fun updateDisplayRotation() {
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val degrees = when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        StreamController.setDisplayRotation(degrees)
    }

    private fun requestPermissionsIfNeeded() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    companion object {
        const val EXTRA_DEBUG_URL = "debug_url"
        const val EXTRA_DEBUG_HEVC = "debug_hevc"
        const val EXTRA_DEBUG_NO_SECURE = "debug_no_secure"
        val DEFAULT_SETTINGS = VideoEncoderSettings(width = 1280, height = 720, fps = 30, bitrate = 3_000_000)
    }
}

/** App screens (simple state-based navigation). */
private sealed interface Screen {
    data object Main : Screen

    data object Streams : Screen

    data class EditStream(val id: UUID?) : Screen

    data object ImportExport : Screen
}

@Composable
private fun App(pendingLink: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val store = remember { StreamController.settings(context) }
    val status by StreamController.status.collectAsState()
    val database by store.database.collectAsState()
    val link by pendingLink.collectAsState()
    // Upstream handleSettingsUrls: links are rejected while live and validated before anything else.
    var parsedLink by remember { mutableStateOf<MoblinSettingsUrl?>(null) }
    LaunchedEffect(link) {
        parsedLink = null
        val text = link ?: return@LaunchedEffect
        if (status.active) {
            Toast.makeText(context, R.string.link_not_while_live, Toast.LENGTH_LONG).show()
            pendingLink.value = null
            return@LaunchedEffect
        }
        try {
            parsedLink = MoblinSettingsUrl.parse(text)
        } catch (e: MoblinSettingsUrl.InvalidSettingsUrlException) {
            Toast.makeText(context, context.getString(R.string.link_failed, e.message), Toast.LENGTH_LONG).show()
            pendingLink.value = null
        }
    }
    parsedLink?.let { settingsLink ->
        SettingsLinkDialog(
            link = settingsLink,
            database = database,
            onApply = { replaceCollisions ->
                store.update { settingsLink.apply(it, replaceCollisions) }
                pendingLink.value = null
                Toast.makeText(context, R.string.link_imported, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { pendingLink.value = null },
        )
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    // Upstream makeFlameRedToast(): warn once the device reaches the critical thermal state.
    LaunchedEffect(status.thermal) {
        if (status.thermal == ThermalState.CRITICAL) {
            Toast.makeText(context, R.string.thermal_critical, Toast.LENGTH_LONG).show()
        }
    }
    BackHandler(enabled = screen != Screen.Main) {
        screen = if (screen is Screen.EditStream) Screen.Streams else Screen.Main
    }
    when (val current = screen) {
        Screen.Main -> MainScreen(onStreams = { screen = Screen.Streams }, onImportExport = { screen = Screen.ImportExport })
        Screen.Streams -> StreamsScreen(
            store,
            status.active,
            onEdit = { screen = Screen.EditStream(it) },
            onBack = { screen = Screen.Main },
        )
        is Screen.EditStream -> StreamEditorScreen(store, current.id, onDone = { screen = Screen.Streams })
        Screen.ImportExport -> ImportExportScreen(store, status.active, onBack = { screen = Screen.Main })
    }
}

@Composable
private fun MainScreen(onStreams: () -> Unit, onImportExport: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StreamController.settings(context) }
    val database by store.database.collectAsState()
    val status by StreamController.status.collectAsState()
    val stream = database.selectedStream
    // The preview canvas follows the selected stream's resolution and frame rate.
    LaunchedEffect(stream?.resolution, stream?.fps) {
        StreamController.refreshPreview(context)
    }
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
    // Landscape: preview on the left (as large as fits at 16:9), controls scroll on the right.
    Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            CameraPreview()
        }
        Column(
            modifier = Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            if (stream != null) {
                Text(stream.name, style = MaterialTheme.typography.titleMedium)
                Text(stream.summary(), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.no_streams))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (status.active) {
                            StreamController.stop(context)
                        } else {
                            StreamController.goLive(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        }
                    },
                    enabled = stream != null || status.active,
                ) {
                    Text(stringResource(if (status.active) R.string.end_stream else R.string.go_live))
                }
                OutlinedButton(onClick = onStreams) { Text(stringResource(R.string.streams)) }
            }
            Text(
                "${status.state}  ${status.uptimeSeconds}s  ${status.fps} fps  ${status.videoBitrateKbps} kbps  " +
                    "RTT ${status.rttMs.toInt()} ms  " +
                    "audio ${status.audioLevelDbfs.toInt()} dBFS  mic ${status.microphone}  thermal ${status.thermal}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.encoder.isNotEmpty()) {
                Text("Encoder ${status.encoder}", style = MaterialTheme.typography.bodySmall)
            }
            if (status.detail.isNotEmpty()) {
                Text(status.detail, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onImportExport) { Text(stringResource(R.string.import_export)) }
            Text(stringResource(R.string.attribution), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showLicenses = true }) {
                Text(stringResource(R.string.open_source_licenses))
            }
        }
    }
}
