package io.github.mesmerize08.nexusirl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.settings.RgbColor

/** A full-screen page with a title, a back button and scrolling content. */
@Composable
fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            actions()
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

/** A labelled drop-down choice. */
@Composable
fun <T> ChoiceField(label: String, value: T, options: List<T>, display: (T) -> String, onChange: (T) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(display(value)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (option in options) {
                    DropdownMenuItem(text = { Text(display(option)) }, onClick = {
                        expanded = false
                        onChange(option)
                    })
                }
            }
        }
    }
}

/**
 * A text field for secrets (stream URLs, keys): masked with a Show/Hide toggle, password keyboard
 * so the IME does not learn or suggest it, and no autocorrect (docs/PORT_PLAN.md §9.4).
 */
@Composable
fun SecretTextField(label: String, value: String, onValueChange: (String) -> Unit, supportingText: String? = null) {
    var reveal by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { reveal = !reveal }) {
            Text(stringResource(if (reveal) R.string.hide else R.string.show))
        }
    }
}

/** Colour choices for widgets and chat (named like upstream's colour pickers offer). */
val COLORS = listOf(
    "White" to RgbColor(255, 255, 255),
    "Black" to RgbColor(0, 0, 0),
    "Yellow" to RgbColor(255, 214, 10),
    "Red" to RgbColor(255, 59, 48),
    "Green" to RgbColor(52, 199, 89),
    "Blue" to RgbColor(10, 132, 255),
)

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.bodySmall)
    }
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ColorChoice(label: String, color: RgbColor, onChange: (RgbColor) -> Unit) {
    ChoiceField(
        label,
        COLORS.firstOrNull { it.second == color.copy(opacity = null) }?.first ?: "Custom",
        COLORS.map { it.first },
        { it },
        { name -> COLORS.firstOrNull { it.first == name }?.let { onChange(it.second) } },
    )
}
