// Port of Moblin/View/Settings/Scenes/** @97e26d2 (v1 subset): scenes, their widgets and layout,
// and the text, image and QR code widget editors.
package io.github.mesmerize08.nexusirl.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mesmerize08.nexusirl.R
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEffect
import io.github.mesmerize08.nexusirl.scene.SceneEngine
import io.github.mesmerize08.nexusirl.settings.HorizontalAlignment
import io.github.mesmerize08.nexusirl.settings.MoblinSettingsUrl.Companion.makeUniqueName
import io.github.mesmerize08.nexusirl.settings.RgbColor
import io.github.mesmerize08.nexusirl.settings.SettingsScene
import io.github.mesmerize08.nexusirl.settings.SettingsSceneWidget
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsWidget
import io.github.mesmerize08.nexusirl.settings.SettingsWidgetChat
import io.github.mesmerize08.nexusirl.settings.WidgetAlignment
import io.github.mesmerize08.nexusirl.settings.WidgetType
import io.github.mesmerize08.nexusirl.stream.StreamController
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Widget types Android can show today. */
private val SUPPORTED_WIDGET_TYPES = listOf(WidgetType.TEXT, WidgetType.IMAGE, WidgetType.QR_CODE, WidgetType.BROWSER, WidgetType.CHAT)

private val CAMERAS = listOf("Back", "Front", "None")

/** Effect toggles (upstream quick buttons), in upstream's application order. */
@Composable
fun EffectsPicker() {
    val context = LocalContext.current
    val effects by StreamController.effects.collectAsState()
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((effect, label) in EFFECT_LABELS) {
            FilterChip(
                selected = effect in effects,
                onClick = { StreamController.setEffect(context, effect, effect !in effects) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

private val EFFECT_LABELS = listOf(
    VideoEffect.GRAY_SCALE to R.string.effect_gray_scale,
    VideoEffect.SEPIA to R.string.effect_sepia,
    VideoEffect.PIXELLATE to R.string.effect_pixellate,
    VideoEffect.FOUR_THREE to R.string.effect_four_three,
    VideoEffect.CRT to R.string.effect_crt,
    VideoEffect.WHIRLPOOL to R.string.effect_whirlpool,
    VideoEffect.PINCH to R.string.effect_pinch,
)

/** Scene buttons on the main screen (upstream scene picker). */
@Composable
fun ScenePicker(store: SettingsStore) {
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val selected by StreamController.selectedSceneId.collectAsState()
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (scene in database.scenes.filter { it.enabled }) {
            FilterChip(
                selected = scene.id == selected,
                onClick = { StreamController.selectScene(context, scene.id) },
                label = { Text(scene.name) },
            )
        }
    }
}

@Composable
fun ScenesScreen(store: SettingsStore, onEdit: (UUID) -> Unit, onWidgets: () -> Unit, onLuts: () -> Unit, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    Page(
        title = stringResource(R.string.scenes),
        onBack = onBack,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLuts) { Text(stringResource(R.string.luts)) }
                OutlinedButton(onClick = onWidgets) { Text(stringResource(R.string.widgets)) }
                Button(onClick = {
                    val scene = SettingsScene(name = makeUniqueName("Scene", database.scenes.map { it.name }))
                    store.update { it.copy(scenes = it.scenes + scene) }
                    onEdit(scene.id)
                }) { Text(stringResource(R.string.add_scene)) }
            }
        },
    ) {
        for (scene in database.scenes) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onEdit(scene.id) }) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(scene.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${scene.cameraPosition} · ${scene.widgets.size} ${stringResource(R.string.widgets).lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = scene.enabled, onCheckedChange = { enabled ->
                    store.update { db -> db.copy(scenes = db.scenes.map { if (it.id == scene.id) it.copy(enabled = enabled) else it }) }
                })
            }
        }
    }
}

@Composable
fun SceneEditorScreen(store: SettingsStore, sceneId: UUID, onEditWidget: (UUID) -> Unit, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    val scene = database.scenes.firstOrNull { it.id == sceneId } ?: return onBack()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var layoutOf by remember { mutableStateOf<UUID?>(null) }

    fun update(transform: (SettingsScene) -> SettingsScene) {
        store.update { db -> db.copy(scenes = db.scenes.map { if (it.id == sceneId) transform(it) else it }) }
    }

    Page(
        title = scene.name,
        onBack = onBack,
        actions = { TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.delete)) } },
    ) {
        OutlinedTextField(
            value = scene.name,
            onValueChange = { name -> update { it.copy(name = name) } },
            label = { Text(stringResource(R.string.stream_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceField(
            label = stringResource(R.string.camera),
            value = scene.cameraPosition.takeIf { it in CAMERAS } ?: "Back",
            options = CAMERAS,
            display = { it },
            onChange = { camera -> update { it.copy(cameraPosition = camera) } },
        )
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.widgets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.add_widget)) }
        }
        // Upstream draws later widgets on top of earlier ones.
        scene.widgets.forEachIndexed { index, sceneWidget ->
            val widget = database.widgets.firstOrNull { it.id == sceneWidget.widgetId }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).clickable { widget?.let { onEditWidget(it.id) } }) {
                    Text(widget?.name ?: "?", style = MaterialTheme.typography.bodyLarge)
                    val sizeLabel = stringResource(R.string.size)
                    val sizeText = if (widget?.type?.hasSize == true) " · $sizeLabel ${sceneWidget.size.roundToInt()}%" else ""
                    Text(
                        "${sceneWidget.alignment.rawValue} · x ${sceneWidget.x.roundToInt()}% · y ${sceneWidget.y.roundToInt()}%$sizeText",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { layoutOf = sceneWidget.id }) { Text(stringResource(R.string.position)) }
                TextButton(enabled = index > 0, onClick = {
                    update { it.copy(widgets = it.widgets.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                }) { Text("↑") }
                TextButton(onClick = { update { it.copy(widgets = it.widgets.filterNot { w -> w.id == sceneWidget.id }) } }) {
                    Text(stringResource(R.string.remove))
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.add_widget)) },
            text = {
                Column {
                    if (database.widgets.isEmpty()) {
                        Text(stringResource(R.string.no_widgets))
                    }
                    for (widget in database.widgets) {
                        Text(
                            "${widget.name} (${widget.type.rawValue})",
                            modifier = Modifier.fillMaxWidth().clickable {
                                update { it.copy(widgets = it.widgets + SettingsSceneWidget.defaultFor(widget)) }
                                adding = false
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    layoutOf?.let { id ->
        scene.widgets.firstOrNull { it.id == id }?.let { sceneWidget ->
            val hasSize = database.widgets.firstOrNull { it.id == sceneWidget.widgetId }?.type?.hasSize == true
            LayoutDialog(sceneWidget, hasSize, onChange = { changed ->
                update { it.copy(widgets = it.widgets.map { w -> if (w.id == id) changed else w }) }
            }, onDismiss = { layoutOf = null })
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_scene_title, scene.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    store.update { db -> db.copy(scenes = db.scenes.filterNot { it.id == sceneId }) }
                    onBack()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Upstream widget position editor: x/y (and size) as percentages from the alignment corner. */
@Composable
private fun LayoutDialog(
    sceneWidget: SettingsSceneWidget,
    hasSize: Boolean,
    onChange: (SettingsSceneWidget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.position)) },
        text = {
            Column {
                ChoiceField(stringResource(R.string.alignment), sceneWidget.alignment, WidgetAlignment.entries, { it.rawValue }, {
                    onChange(sceneWidget.copy(alignment = it))
                })
                PercentSlider("x", sceneWidget.x) { onChange(sceneWidget.copy(x = it)) }
                PercentSlider("y", sceneWidget.y) { onChange(sceneWidget.copy(y = it)) }
                if (hasSize) {
                    PercentSlider(stringResource(R.string.size), sceneWidget.size, minimum = 1.0) { onChange(sceneWidget.copy(size = it)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun PercentSlider(label: String, value: Double, minimum: Double = 0.0, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text("%.1f%%".format(value), style = MaterialTheme.typography.bodySmall)
    }
    Slider(
        value = value.toFloat().coerceIn(minimum.toFloat(), 100f),
        onValueChange = { onChange((it * 10).roundToInt() / 10.0) },
        valueRange = minimum.toFloat()..100f,
    )
}

@Composable
fun WidgetsScreen(store: SettingsStore, onEdit: (UUID) -> Unit, onBack: () -> Unit) {
    val database by store.database.collectAsState()
    var adding by remember { mutableStateOf(false) }
    Page(
        title = stringResource(R.string.widgets),
        onBack = onBack,
        actions = { Button(onClick = { adding = true }) { Text(stringResource(R.string.add_widget)) } },
    ) {
        if (database.widgets.isEmpty()) {
            Text(stringResource(R.string.no_widgets))
        }
        for (widget in database.widgets) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onEdit(widget.id) }) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(widget.name, style = MaterialTheme.typography.titleMedium)
                    val note = if (widget.type !in SUPPORTED_WIDGET_TYPES) " · ${stringResource(R.string.not_shown_on_android)}" else ""
                    Text(widget.type.rawValue + note, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = widget.enabled, onCheckedChange = { enabled ->
                    store.update { db -> db.copy(widgets = db.widgets.map { if (it.id == widget.id) it.copy(enabled = enabled) else it }) }
                })
            }
        }
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.add_widget)) },
            text = {
                Column {
                    for (type in SUPPORTED_WIDGET_TYPES) {
                        Text(
                            type.rawValue,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val widget =
                                    SettingsWidget(name = makeUniqueName(type.rawValue, database.widgets.map { it.name }), type = type)
                                store.update { it.copy(widgets = it.widgets + widget) }
                                adding = false
                                onEdit(widget.id)
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Colour presets for text widgets (a full picker comes later). */
private val COLORS = listOf(
    "White" to RgbColor(255, 255, 255),
    "Black" to RgbColor(0, 0, 0),
    "Yellow" to RgbColor(255, 214, 10),
    "Red" to RgbColor(255, 59, 48),
    "Green" to RgbColor(52, 199, 89),
    "Blue" to RgbColor(10, 132, 255),
)

private fun copyImage(context: Context, uri: Uri, widgetId: UUID): Boolean = try {
    val directory = File(context.filesDir, SceneEngine.IMAGES_DIRECTORY).apply { mkdirs() }
    context.contentResolver.openInputStream(uri)?.use { input ->
        File(directory, widgetId.toString().uppercase()).outputStream().use { input.copyTo(it) }
    } != null
} catch (e: Exception) {
    false
}

@Composable
fun WidgetEditorScreen(store: SettingsStore, widgetId: UUID, onBack: () -> Unit) {
    val context = LocalContext.current
    val database by store.database.collectAsState()
    val widget = database.widgets.firstOrNull { it.id == widgetId } ?: return onBack()
    var deleting by remember { mutableStateOf(false) }

    fun update(transform: (SettingsWidget) -> SettingsWidget) {
        store.update { db -> db.copy(widgets = db.widgets.map { if (it.id == widgetId) transform(it) else it }) }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && copyImage(context, uri, widgetId)) {
            // Touch the settings so the scene redraws with the new picture.
            update { it.copy() }
            StreamController.refreshScene(context)
        }
    }

    Page(
        title = widget.name,
        onBack = onBack,
        actions = { TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.delete)) } },
    ) {
        OutlinedTextField(
            value = widget.name,
            onValueChange = { name -> update { it.copy(name = name) } },
            label = { Text(stringResource(R.string.stream_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(widget.type.rawValue, style = MaterialTheme.typography.bodySmall)
        when (widget.type) {
            WidgetType.TEXT -> {
                val text = widget.text
                OutlinedTextField(
                    value = text.formatString,
                    onValueChange = { format -> update { it.copy(text = it.text.copy(formatString = format)) } },
                    label = { Text(stringResource(R.string.text_format)) },
                    supportingText = { Text(stringResource(R.string.text_format_help)) },
                    // Placeholders must be typed exactly.
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.font_size), modifier = Modifier.weight(1f))
                    Text("${text.fontSize}", style = MaterialTheme.typography.bodySmall)
                }
                Slider(
                    value = text.fontSize.toFloat(),
                    onValueChange = { size -> update { it.copy(text = it.text.copy(fontSize = size.roundToInt())) } },
                    valueRange = 10f..200f,
                )
                ChoiceField(
                    stringResource(R.string.text_color),
                    COLORS.firstOrNull {
                        it.second == text.foregroundColor.copy(opacity = null)
                    }?.first ?: "Custom",
                    COLORS.map { it.first },
                    { it },
                    { name ->
                        COLORS.firstOrNull { it.first == name }?.let { (_, color) ->
                            update {
                                it.copy(
                                    text = it.text.copy(foregroundColor = color),
                                )
                            }
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.background), modifier = Modifier.weight(1f))
                    Switch(checked = !text.clearBackgroundColor, onCheckedChange = { on ->
                        update {
                            it.copy(text = it.text.copy(clearBackgroundColor = !on))
                        }
                    })
                }
                ChoiceField(
                    stringResource(
                        R.string.alignment,
                    ),
                    text.horizontalAlignment,
                    HorizontalAlignment.entries,
                    { it.rawValue },
                    { alignment ->
                        update { it.copy(text = it.text.copy(horizontalAlignment = alignment)) }
                    },
                )
            }
            WidgetType.IMAGE -> {
                val file = File(File(context.filesDir, SceneEngine.IMAGES_DIRECTORY), widgetId.toString().uppercase())
                Text(stringResource(if (file.exists()) R.string.image_set else R.string.image_missing))
                Button(onClick = { pickImage.launch("image/*") }) { Text(stringResource(R.string.choose_image)) }
            }
            WidgetType.QR_CODE -> OutlinedTextField(
                value = widget.qrCode.message,
                onValueChange = { message -> update { it.copy(qrCode = it.qrCode.copy(message = message)) } },
                label = { Text(stringResource(R.string.qr_message)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            WidgetType.BROWSER -> {
                val browser = widget.browser
                OutlinedTextField(
                    value = browser.url,
                    onValueChange = { url -> update { it.copy(browser = it.browser.copy(url = url.trim())) } },
                    label = { Text(stringResource(R.string.browser_url)) },
                    supportingText = { Text(stringResource(R.string.browser_url_help)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = browser.width.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { w ->
                                update {
                                    it.copy(
                                        browser = it.browser.copy(width = w.coerceIn(1, 4096)),
                                    )
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.width)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = browser.height.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { h ->
                                update {
                                    it.copy(
                                        browser = it.browser.copy(height = h.coerceIn(1, 4096)),
                                    )
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.height)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = browser.styleSheet,
                    onValueChange = { css -> update { it.copy(browser = it.browser.copy(styleSheet = css)) } },
                    label = { Text(stringResource(R.string.style_sheet)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.moblin_access), modifier = Modifier.weight(1f))
                    Switch(
                        checked = browser.moblinAccess,
                        onCheckedChange = { on -> update { it.copy(browser = it.browser.copy(moblinAccess = on)) } },
                    )
                }
            }
            WidgetType.CHAT -> ChatWidgetEditor(widget.chat) { chat -> update { it.copy(chat = chat) } }
            else -> Text(stringResource(R.string.not_shown_on_android))
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_widget_title, widget.name)) },
            text = { Text(stringResource(R.string.delete_widget_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    // Upstream also removes the widget from every scene.
                    store.update { db ->
                        db.copy(
                            widgets = db.widgets.filterNot { it.id == widgetId },
                            scenes = db.scenes.map { scene -> scene.copy(widgets = scene.widgets.filterNot { it.widgetId == widgetId }) },
                        )
                    }
                    File(File(context.filesDir, SceneEngine.IMAGES_DIRECTORY), widgetId.toString().uppercase()).delete()
                    onBack()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Upstream `WidgetChatSettingsView` (v1 subset). */
@Composable
private fun ChatWidgetEditor(chat: SettingsWidgetChat, onChange: (SettingsWidgetChat) -> Unit) {
    SliderRow(stringResource(R.string.font_size), chat.fontSize, 10f..80f, "${chat.fontSize.roundToInt()}") {
        onChange(chat.copy(fontSize = it.roundToInt().toFloat()))
    }
    SliderRow(stringResource(R.string.chat_messages), chat.maximumNumberOfMessages.toFloat(), 1f..20f, "${chat.maximumNumberOfMessages}") {
        onChange(chat.copy(maximumNumberOfMessages = it.roundToInt()))
    }
    SliderRow(stringResource(R.string.chat_height), chat.height, 0.1f..1f, "${(chat.height * 100).roundToInt()} %") {
        onChange(chat.copy(height = (it * 20).roundToInt() / 20f))
    }
    ColorChoice(stringResource(R.string.username_color), chat.usernameColor) { onChange(chat.copy(usernameColor = it)) }
    ColorChoice(stringResource(R.string.text_color), chat.messageColor) { onChange(chat.copy(messageColor = it)) }
    SwitchRow(stringResource(R.string.background), chat.backgroundColorEnabled) { onChange(chat.copy(backgroundColorEnabled = it)) }
    SwitchRow(stringResource(R.string.border), chat.shadowColorEnabled) { onChange(chat.copy(shadowColorEnabled = it)) }
    SwitchRow(stringResource(R.string.bold_username), chat.boldUsername) { onChange(chat.copy(boldUsername = it)) }
    SwitchRow(stringResource(R.string.bold_message), chat.boldMessage) { onChange(chat.copy(boldMessage = it)) }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(valueText, style = MaterialTheme.typography.bodySmall)
    }
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorChoice(label: String, color: RgbColor, onChange: (RgbColor) -> Unit) {
    ChoiceField(
        label,
        COLORS.firstOrNull { it.second == color.copy(opacity = null) }?.first ?: "Custom",
        COLORS.map { it.first },
        { it },
        { name -> COLORS.firstOrNull { it.first == name }?.let { onChange(it.second) } },
    )
}
