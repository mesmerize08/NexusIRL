// Camera preview with the camera controls of upstream Moblin/View/Stream/StreamView.swift and
// Moblin/View/ControlBar/QuickButton/*.swift @97e26d2: tap to focus, long press for auto focus,
// pinch to zoom, zoom presets, torch and camera picker.
package io.github.mesmerize08.nexusirl.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Zoom presets shown under the preview (upstream default back-camera presets), within the camera's range. */
private val ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 5f)

private fun formatZoom(zoom: Float): String = if (zoom == zoom.roundToInt().toFloat()) "${zoom.roundToInt()}x" else "%.1fx".format(zoom)

@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val camera by StreamController.camera.collectAsState()
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        CameraSettingsDialog(onDismiss = { showSettings = false })
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1500)
            focusPoint = null
        }
    }
    Box(modifier = modifier.aspectRatio(16f / 9f)) {
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
        // Gestures on a transparent overlay (the SurfaceView itself does not take Compose input).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            focusPoint = offset
                            StreamController.focusAt(offset.x / size.width, offset.y / size.height, size.width.toFloat() / size.height)
                        },
                        onLongPress = {
                            focusPoint = null
                            StreamController.setAutoFocus()
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (zoomChange != 1f) {
                            StreamController.setZoom(StreamController.camera.value.zoom * zoomChange)
                        }
                    }
                },
        )
        focusPoint?.let { point ->
            val half = with(LocalDensity.current) { 32.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt()) }
                    .size(64.dp)
                    .border(2.dp, Color.Yellow, RoundedCornerShape(4.dp)),
            )
        }
        val current = camera.current
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current != null) {
                for (preset in ZOOM_PRESETS.filter { it >= current.minZoom && it <= current.maxZoom }) {
                    val selected = kotlin.math.abs(camera.zoom - preset) < 0.05f
                    Text(
                        formatZoom(preset),
                        color = if (selected) Color.Yellow else Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .pointerInput(preset) { detectTapGestures { StreamController.setZoom(preset) } },
                    )
                }
                if (ZOOM_PRESETS.none { kotlin.math.abs(camera.zoom - it) < 0.05f }) {
                    Text(
                        formatZoom(camera.zoom),
                        color = Color.Yellow,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.background(
                            Color.Black.copy(alpha = 0.5f),
                            CircleShape,
                        ).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        FilledTonalButton(onClick = { showSettings = true }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text(stringResource(R.string.camera_settings))
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (current?.hasTorch == true) {
                FilledTonalButton(onClick = { StreamController.setTorch(!camera.torch) }) {
                    Text(stringResource(if (camera.torch) R.string.torch_off else R.string.torch_on))
                }
            }
            if (camera.cameras.size > 1) {
                FilledTonalButton(onClick = {
                    val index = camera.cameras.indexOfFirst { it.id == current?.id }
                    StreamController.switchCamera(camera.cameras[(index + 1) % camera.cameras.size].id)
                }) {
                    Text(current?.label ?: stringResource(R.string.camera))
                }
            }
        }
    }
}
