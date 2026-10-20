// Manual camera controls (upstream Moblin/View/ControlBar/QuickButton/QuickButtonCameraView and the
// camera settings views @97e26d2): exposure bias, focus, shutter, ISO, white balance, stabilization.
package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.media.pipeline.CameraFactors
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlin.math.roundToInt

/** A control that is automatic or manual, with a slider when manual. */
@Composable
private fun ManualControl(
    title: String,
    supported: Boolean,
    value: Float?,
    label: (Float) -> String,
    onManual: (Float?) -> Unit,
    initial: () -> Float,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, modifier = Modifier.weight(1f))
            if (!supported) {
                Text(stringResource(R.string.not_supported_by_camera), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    if (value == null) stringResource(R.string.auto) else label(value),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Switch(checked = value != null, onCheckedChange = { manual -> onManual(if (manual) initial() else null) })
            }
        }
        if (supported && value != null) {
            Slider(value = value, onValueChange = { onManual(it) })
        }
    }
}

@Composable
fun CameraSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val camera by StreamController.camera.collectAsState()
    val info = camera.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.camera_settings) + (info?.let { " · ${it.label}" } ?: "")) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (info == null) {
                    Text(stringResource(R.string.no_camera))
                    return@Column
                }
                // Exposure bias, within the camera's range (upstream allows -2..+2 EV).
                if (info.exposureCompensationStep > 0f) {
                    val minBias = maxOf(-2f, info.exposureCompensationMin * info.exposureCompensationStep)
                    val maxBias = minOf(2f, info.exposureCompensationMax * info.exposureCompensationStep)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.exposure_bias), modifier = Modifier.weight(1f))
                        Text("%+.1f EV".format(camera.exposureBias), style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = camera.exposureBias.coerceIn(minBias, maxBias),
                        onValueChange = {
                            StreamController.setExposureBias(
                                (it / info.exposureCompensationStep).roundToInt() * info.exposureCompensationStep,
                            )
                        },
                        valueRange = minBias..maxBias,
                    )
                }
                ManualControl(
                    title = stringResource(R.string.focus),
                    supported = info.supportsManualFocus,
                    value = camera.manualFocus,
                    label = { "%.0f%%".format(it * 100) },
                    onManual = { StreamController.setManualFocus(it) },
                    initial = { StreamController.currentCameraFactors()?.third ?: 0.5f },
                )
                ManualControl(
                    title = stringResource(R.string.shutter),
                    supported = info.supportsManualExposure,
                    value = camera.manualExposure,
                    label = { StreamController.exposureLabel(it) },
                    onManual = { StreamController.setManualExposureAndIso(it, camera.manualIso) },
                    initial = { StreamController.currentCameraFactors()?.first ?: 0.5f },
                )
                ManualControl(
                    title = stringResource(R.string.iso),
                    supported = info.supportsManualExposure,
                    value = camera.manualIso,
                    label = { "ISO ${CameraFactors.isoFromFactor(info.minIso, info.maxIso, it)}" },
                    onManual = { StreamController.setManualExposureAndIso(camera.manualExposure, it) },
                    initial = { StreamController.currentCameraFactors()?.second ?: 0.5f },
                )
                ManualControl(
                    title = stringResource(R.string.white_balance),
                    supported = info.supportsManualWhiteBalance,
                    value = camera.manualWhiteBalance,
                    label = { "${CameraFactors.kelvinFromFactor(it).roundToInt()} K" },
                    onManual = { StreamController.setManualWhiteBalance(it) },
                    initial = { 0.5f },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stabilization), modifier = Modifier.weight(1f))
                    if (info.supportsStabilization) {
                        Switch(checked = camera.stabilization, onCheckedChange = { StreamController.setStabilization(context, it) })
                    } else {
                        Text(stringResource(R.string.not_supported_by_camera), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}
