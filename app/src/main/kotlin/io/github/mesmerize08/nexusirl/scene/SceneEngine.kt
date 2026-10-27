// The scene's widget layer (upstream Moblin/Various/Model/ModelScene.swift `sceneUpdatedOn`, which
// builds the effect list for the selected scene, and each widget effect's periodic update).
package io.github.mesmerize08.nexusirl.scene

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.chat.EmoteImages
import io.github.mesmerize08.nexusirl.effects.LutCube
import io.github.mesmerize08.nexusirl.effects.TextFormat
import io.github.mesmerize08.nexusirl.effects.TextFormatter
import io.github.mesmerize08.nexusirl.effects.TextVariables
import io.github.mesmerize08.nexusirl.effects.WidgetLayout
import io.github.mesmerize08.nexusirl.media.pipeline.Overlay
import io.github.mesmerize08.nexusirl.media.pipeline.VideoPipeline
import io.github.mesmerize08.nexusirl.settings.SettingsScene
import io.github.mesmerize08.nexusirl.settings.SettingsWidget
import io.github.mesmerize08.nexusirl.settings.WidgetType
import java.io.File
import java.util.UUID

/**
 * Turns the selected scene's widgets into overlay images for the camera pipeline. Runs on its own
 * thread, once a second (clocks, timers) and whenever the scene or its widgets change. A widget's
 * image is only redrawn when its content changes; otherwise it is just placed again.
 */
class SceneEngine(context: Context) {
    private val imagesDirectory = File(context.applicationContext.filesDir, IMAGES_DIRECTORY)
    private val thread = HandlerThread("scenes").apply { start() }
    private val handler = Handler(thread.looper)

    private class Cached(val key: String, val width: Int, val height: Int)

    // Thread confined to [thread].
    private var pipeline: VideoPipeline? = null
    private var scene: SettingsScene? = null
    private var widgets: Map<UUID, SettingsWidget> = emptyMap()
    private var variables: () -> TextVariables = { TextVariables() }
    private var chatPosts: () -> List<ChatPost> = { emptyList() }
    private var mapBitmap: (UUID) -> Bitmap? = { null }
    private var lutCube: (UUID) -> LutCube? = { null }
    private val cache = HashMap<String, Cached>()
    private var ticking = false

    /** Where the widgets go; a new pipeline needs all images uploaded again. */
    fun setPipeline(pipeline: VideoPipeline?) {
        handler.post {
            if (this.pipeline !== pipeline) {
                cache.clear()
            }
            this.pipeline = pipeline
            render()
            if (pipeline != null && !ticking) {
                ticking = true
                handler.postDelayed(::tick, TICK_MILLIS)
            }
        }
    }

    fun setScene(scene: SettingsScene?, widgets: List<SettingsWidget>) {
        handler.post {
            this.scene = scene
            this.widgets = widgets.associateBy { it.id }
            render()
        }
    }

    /** Supplies the values for text widgets ({time}, {bitrate}, ...). */
    fun setVariables(provider: () -> TextVariables) {
        handler.post { variables = provider }
    }

    /** Supplies the chat for chat widgets (newest first). */
    fun setChatPosts(provider: () -> List<ChatPost>) {
        handler.post { chatPosts = provider }
    }

    /** Supplies map widget images (MapWidgets). */
    fun setMapBitmaps(provider: (UUID) -> Bitmap?) {
        handler.post { mapBitmap = provider }
    }

    /** The colour settings' LUTs by id, for widgets' LUT effects. */
    fun setLutCubes(provider: (UUID) -> LutCube?) {
        handler.post { lutCube = provider }
    }

    /** Redraws now instead of at the next tick (new chat messages). */
    fun invalidate() {
        handler.post(::render)
    }

    private fun tick() {
        if (pipeline == null) {
            ticking = false
            return
        }
        render()
        handler.postDelayed(::tick, TICK_MILLIS)
    }

    private fun render() {
        val pipeline = pipeline ?: return
        val canvasWidth = pipeline.settings.width
        val canvasHeight = pipeline.settings.height
        val overlays = mutableListOf<Overlay>()
        val values by lazy { variables() }
        for (sceneWidget in scene?.widgets.orEmpty()) {
            val widget = widgets[sceneWidget.widgetId]?.takeIf { it.enabled } ?: continue
            val id = sceneWidget.id.toString()
            val previous = cache[id]
            var bitmap: Bitmap? = null
            val key: String
            when (widget.type) {
                WidgetType.TEXT -> {
                    val fontSize = WidgetLayout.scaledFontSize(widget.text.fontSize, canvasWidth, canvasHeight)
                    val lines = TextFormatter.format(TextFormat.parse(widget.text.formatString), values)
                    key = "$lines|${widget.text}|$fontSize"
                    if (key != previous?.key) {
                        bitmap = WidgetBitmaps.text(lines, widget.text, fontSize) ?: continue
                    }
                }
                WidgetType.IMAGE -> {
                    val file = File(imagesDirectory, widget.id.toString().uppercase())
                    val luts = widget.lutEffectIds
                    key = "${file.lastModified()}|${file.length()}|$luts"
                    if (key != previous?.key) {
                        bitmap = WidgetBitmaps.image(file, maxOf(canvasWidth, canvasHeight))?.let { image ->
                            WidgetBitmaps.withLuts(image, luts.mapNotNull { lutCube(it) })
                        } ?: continue
                    }
                }
                WidgetType.QR_CODE -> {
                    key = widget.qrCode.message
                    if (key != previous?.key) {
                        bitmap = WidgetBitmaps.qrCode(key, minOf(canvasWidth, canvasHeight)) ?: continue
                    }
                }
                WidgetType.MAP -> {
                    val map = mapBitmap(widget.id)
                    if (map == null) {
                        cache.remove(id)
                        continue
                    }
                    key = "${System.identityHashCode(map)}"
                    if (key != previous?.key) {
                        bitmap = map
                    }
                }
                WidgetType.CHAT -> {
                    val posts = chatPosts()
                    key = "${System.identityHashCode(posts)}|${EmoteImages.version.value}|${widget.chat}|$canvasHeight"
                    if (key != previous?.key) {
                        val rendered = ChatWidgetBitmap.render(posts, widget.chat, EmoteImages::get)
                        if (rendered == null) {
                            cache.remove(id)
                            continue
                        }
                        // Upstream keeps at most `height` of the canvas, dropping the oldest lines.
                        val maximumHeight = (canvasHeight * widget.chat.height.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
                        bitmap = if (rendered.height > maximumHeight) {
                            Bitmap.createBitmap(rendered, 0, rendered.height - maximumHeight, rendered.width, maximumHeight)
                        } else {
                            rendered
                        }
                    }
                }
                // Browser widgets render into a surface of their own (BrowserWidgets); only place it.
                WidgetType.BROWSER -> {
                    if (!BrowserWidgets.isAllowedUrl(widget.browser.url)) {
                        continue
                    }
                    val rect = WidgetLayout.place(
                        sceneWidget,
                        widget.browser.width.toDouble(),
                        widget.browser.height.toDouble(),
                        canvasWidth.toDouble(),
                        canvasHeight.toDouble(),
                        resize = true,
                    )
                    overlays += Overlay(
                        BrowserWidgets.overlayId(widget.id),
                        null,
                        rect.x.toFloat(),
                        rect.y.toFloat(),
                        rect.width.toFloat(),
                        rect.height.toFloat(),
                    )
                    continue
                }
                // Other widget types arrive in later phases; they are kept in settings but not drawn.
                else -> continue
            }
            val width = bitmap?.width ?: previous!!.width
            val height = bitmap?.height ?: previous!!.height
            if (bitmap != null) {
                cache[id] = Cached(key, width, height)
            }
            val rect = WidgetLayout.place(
                sceneWidget,
                width.toDouble(),
                height.toDouble(),
                canvasWidth.toDouble(),
                canvasHeight.toDouble(),
                resize = widget.type.hasSize,
            )
            overlays += Overlay(id, bitmap, rect.x.toFloat(), rect.y.toFloat(), rect.width.toFloat(), rect.height.toFloat())
        }
        cache.keys.retainAll(overlays.map { it.id }.toSet())
        pipeline.setOverlays(overlays)
    }

    fun release() {
        handler.post { pipeline = null }
        thread.quitSafely()
    }

    companion object {
        /** Upstream `imagesStorageDirectory`: image widget pictures, named by widget id. */
        const val IMAGES_DIRECTORY = "Images"
        private const val TICK_MILLIS = 1000L
    }
}
