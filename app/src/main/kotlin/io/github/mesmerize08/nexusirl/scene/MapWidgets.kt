// Port of Moblin/VideoEffects/MapEffect.swift @97e26d2: MapKit's snapshotter is replaced by
// MapLibre's, with a user-selectable style (OpenFreeMap by default; docs/PORT_PLAN.md §8.1).
package io.github.mesmerize08.nexusirl.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mesmerize08.nexusirl.effects.MapCamera
import io.github.mesmerize08.nexusirl.location.LocationController
import io.github.mesmerize08.nexusirl.settings.SettingsWidget
import io.github.mesmerize08.nexusirl.settings.SettingsWidgetMap
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders map widgets as square images around the current position, once a second while the
 * position changes. Inside a privacy region, or without location, no map is shown (upstream shows
 * an empty position there). Main thread, except [bitmap].
 */
class MapWidgets(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private class Entry(var settings: SettingsWidgetMap, var side: Int) {
        var snapshotter: MapSnapshotter? = null
        var styleUrl = ""
        var inProgress = false
        var rendered: Triple<Double, Double, Float>? = null
        var renderedSide = 0
    }

    private val entries = HashMap<UUID, Entry>()
    private val bitmaps = ConcurrentHashMap<UUID, Bitmap>()

    /** Recent positions for upstream's per-widget delay; null entries are hidden positions. */
    private val history = ArrayDeque<Pair<Long, Location?>>()
    private var ticking = false
    private var onChanged: () -> Unit = {}

    init {
        MapLibre.getInstance(this.context)
    }

    /** The latest image of a map widget, or null. Any thread. */
    fun bitmap(widgetId: UUID): Bitmap? = bitmaps[widgetId]

    /** Shows exactly [widgets] (the selected scene's map widgets), [side] pixels square each. */
    fun update(widgets: List<Pair<SettingsWidget, Int>>, onChanged: () -> Unit) {
        this.onChanged = onChanged
        val wanted = widgets.associate { (widget, side) -> widget.id to (widget.map to side.coerceIn(40, 2048)) }
        for (id in entries.keys.toList()) {
            if (id !in wanted) {
                entries.remove(id)?.snapshotter?.cancel()
                bitmaps.remove(id)
            }
        }
        for ((id, value) in wanted) {
            val (settings, side) = value
            val entry = entries.getOrPut(id) { Entry(settings, side) }
            entry.settings = settings
            entry.side = side
        }
        if (entries.isNotEmpty() && !ticking) {
            ticking = true
            handler.post(::tick)
        }
    }

    private fun tick() {
        if (entries.isEmpty()) {
            ticking = false
            history.clear()
            return
        }
        val now = SystemClock.elapsedRealtime()
        history.addLast(now to LocationController.mapLocation())
        val longestDelayMillis = (entries.values.maxOf { it.settings.delay } * 1000).toLong()
        while (history.size > 1 && history[1].first + longestDelayMillis <= now) {
            history.removeFirst()
        }
        for ((id, entry) in entries) {
            render(id, entry, delayedLocation(now, entry.settings.delay))
        }
        handler.postDelayed(::tick, 1000)
    }

    /** Upstream `nextNewLocation`: the newest position at least `delay` old, else the oldest kept. */
    private fun delayedLocation(now: Long, delaySeconds: Double): Location? {
        val delayMillis = (delaySeconds * 1000).toLong()
        return (history.lastOrNull { it.first + delayMillis <= now } ?: history.firstOrNull())?.second
    }

    private fun render(id: UUID, entry: Entry, location: Location?) {
        if (location == null) {
            entry.rendered = null
            if (bitmaps.remove(id) != null) {
                onChanged()
            }
            return
        }
        val key = Triple(location.latitude, location.longitude, location.speed)
        // https only (docs/PORT_PLAN.md §9.5); anything else falls back to the default style.
        val styleUrl = entry.settings.styleUrl.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: SettingsWidgetMap.DEFAULT_STYLE_URL
        if (entry.inProgress || (key == entry.rendered && entry.renderedSide == entry.side && styleUrl == entry.styleUrl)) {
            return
        }
        val side = entry.side
        val camera = MapCamera.compute(
            location.latitude,
            location.longitude,
            if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            if (location.hasBearing()) location.bearing.toDouble() else -1.0,
            entry.settings.northUp,
            entry.settings.size,
            side,
        )
        val position = CameraPosition.Builder()
            .target(LatLng(camera.latitude, camera.longitude))
            .zoom(camera.zoom)
            .bearing(camera.bearing)
            .tilt(0.0)
            .build()
        var snapshotter = entry.snapshotter
        if (snapshotter == null || styleUrl != entry.styleUrl || side != entry.renderedSide) {
            snapshotter?.cancel()
            val options = MapSnapshotter.Options(side, side)
                .withStyleBuilder(Style.Builder().fromUri(styleUrl))
                .withCameraPosition(position)
                .withPixelRatio(1f)
                .withLogo(false)
            snapshotter = MapSnapshotter(context, options)
            entry.snapshotter = snapshotter
            entry.styleUrl = styleUrl
        } else {
            snapshotter.setCameraPosition(position)
        }
        entry.inProgress = true
        snapshotter.start(
            { snapshot ->
                entry.inProgress = false
                if (entries[id] !== entry) {
                    return@start
                }
                bitmaps[id] = withDotAndAttribution(snapshot.bitmap, camera.dotOffsetRatio, snapshot.attributions)
                entry.rendered = key
                entry.renderedSide = side
                onChanged()
            },
            { _ ->
                // Offline or a bad style URL: try again on a later tick.
                entry.inProgress = false
            },
        )
    }

    /**
     * Upstream's position dot (centred, moved down by the dot offset when moving fast), plus the
     * map data attribution the tile licences require (e.g. "© OpenStreetMap"), which the
     * snapshotter leaves out at small sizes.
     */
    private fun withDotAndAttribution(map: Bitmap, dotOffsetRatio: Double, attributions: Array<String>): Bitmap {
        val bitmap = map.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val side = bitmap.width.toFloat()
        val radius = (side * 0.04f).coerceAtLeast(5f)
        val x = side / 2
        val y = bitmap.height / 2 + (dotOffsetRatio * bitmap.height / 2).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, radius * 1.3f, paint)
        paint.color = Color.rgb(0, 122, 255)
        canvas.drawCircle(x, y, radius, paint)
        val text = attributionText(attributions)
        if (text.isNotEmpty()) {
            paint.textSize = (side * 0.045f).coerceIn(9f, 18f)
            // Shrink to fit the width.
            while (paint.measureText(text) > side - 4 && paint.textSize > 6f) {
                paint.textSize -= 1f
            }
            val width = paint.measureText(text)
            val metrics = paint.fontMetrics
            val top = bitmap.height - (metrics.descent - metrics.ascent) - 2
            paint.color = Color.argb(170, 255, 255, 255)
            canvas.drawRect(side - width - 4, top, side, bitmap.height.toFloat(), paint)
            paint.color = Color.rgb(40, 40, 40)
            canvas.drawText(text, side - width - 2, bitmap.height - metrics.descent - 1, paint)
        }
        return bitmap
    }

    /** The style's attributions as plain text (they are HTML links). */
    private fun attributionText(attributions: Array<String>): String = attributions
        .map { it.replace(Regex("<[^>]*>"), "").replace("&copy;", "©").replace("&amp;", "&").trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")

    fun release() {
        entries.values.forEach { it.snapshotter?.cancel() }
        entries.clear()
        bitmaps.clear()
    }
}
