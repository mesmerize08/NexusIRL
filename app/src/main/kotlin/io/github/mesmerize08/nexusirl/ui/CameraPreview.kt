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
import androidx.compose.material3.Button
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

/**
 * The presets the current camera can do. The wide preset is the camera's own minimum when that is
 * between 0.5x and 1x (e.g. 0.6x on Galaxy phones), so the ultra-wide lens is always one tap away.
 */
private fun zoomPresets(minZoom: Float, maxZoom: Float): List<Float> {
    val wide = if (minZoom < 0.95f) listOf(maxOf(0.5f, (minZoom * 10).roundToInt() / 10f)) else emptyList()
    return wide + ZOOM_PRESETS.filter { it >= 1f && it <= maxZoom }
}

private fun formatZoom(zoom: Float): String = if (zoom == zoom.roundToInt().toFloat()) "${zoom.roundToInt()}x" else "%.1fx".format(zoom)

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    /** Canvas width / height. */
    aspect: Float = 16f / 9f,
    showControls: Boolean = true,
    overlay: @Composable () -> Unit = {},
) {
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
    Box(modifier = modifier.aspectRatio(aspect)) {
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
        // Under the gesture layer so taps still reach focus and zoom.
        overlay()
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
        // Upstream "Local overlays" quick button hides these.
        if (!showControls) {
            return@Box
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current != null) {
                val presets = zoomPresets(current.minZoom, current.maxZoom)
                for (preset in presets) {
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
                if (presets.none { kotlin.math.abs(camera.zoom - it) < 0.05f }) {
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
                // Filled while the torch is on.
                if (camera.torch) {
                    Button(onClick = { StreamController.setTorch(false) }) { Text(stringResource(R.string.torch)) }
                } else {
                    FilledTonalButton(onClick = { StreamController.setTorch(true) }) { Text(stringResource(R.string.torch)) }
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
