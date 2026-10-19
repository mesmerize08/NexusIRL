package io.github.mesmerize08.nexusirl

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEncoderSettings
import io.github.mesmerize08.nexusirl.stream.StreamController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The URL field can show a stream key (docs/PORT_PLAN.md §9.4).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        requestPermissionsIfNeeded()
        updateDisplayRotation()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StreamScreen()
                }
            }
        }
        // Debug builds only: allow automated tests to start a stream via an intent extra.
        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEBUG_URL)?.let { StreamController.goLive(this, it, DEFAULT_SETTINGS) }
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
        val DEFAULT_SETTINGS = VideoEncoderSettings(width = 1280, height = 720, fps = 30, bitrate = 3_000_000)
    }
}

@Composable
private fun StreamScreen() {
    val context = LocalContext.current
    val status by StreamController.status.collectAsState()
    var url by remember { mutableStateOf("") }
    var revealUrl by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    SurfaceView(viewContext).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = StreamController.setPreviewSurface(holder.surface)

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) = StreamController.setPreviewSurface(null)
                        })
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text(stringResource(R.string.stream_url)) },
                singleLine = true,
                visualTransformation = if (revealUrl) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { revealUrl = !revealUrl }) {
                Text(stringResource(if (revealUrl) R.string.hide else R.string.show))
            }
        }
        Button(
            onClick = {
                if (status.live || status.state == "Starting") {
                    StreamController.stop(context)
                } else {
                    StreamController.goLive(context, url, MainActivity.DEFAULT_SETTINGS)
                }
            },
            enabled = url.isNotEmpty() || status.live,
        ) {
            Text(stringResource(if (status.live) R.string.end_stream else R.string.go_live))
        }
        Text(
            "${status.state}  ${status.fps} fps  ${status.videoBitrateKbps} kbps  RTT ${status.rttMs.toInt()} ms  " +
                "audio ${status.audioLevelDbfs.toInt()} dBFS",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (status.detail.isNotEmpty()) {
            Text(status.detail, style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.attribution), style = MaterialTheme.typography.bodySmall)
    }
}
