// Port of Moblin/VideoEffects/ChatEffect.swift `ChatRenderer` and View/Utils/ChatLineStyle.swift
// @97e26d2 (v1 subset: no badges, platform icons or highlight icons yet).
package io.github.mesmerize08.nexusirl.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import io.github.mesmerize08.nexusirl.chat.ChatPost
import io.github.mesmerize08.nexusirl.settings.SettingsWidgetChat
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Draws the newest chat messages like upstream's chat widget: oldest at the top, newest at the bottom. */
object ChatWidgetBitmap {
    /** Upstream `ChatLineStyle.borderWidth` for widgets. */
    private const val BORDER_WIDTH = 1.5f

    /** Upstream: a 3 px bar left of highlighted messages, text starting after it. */
    private const val BAR_WIDTH = 3

    /** Upstream: the widget background is drawn at 60 % opacity. */
    private const val BACKGROUND_ALPHA = 0.6

    /**
     * An emote drawn as an image scaled to the line height, or (stroke pass) only taking its
     * space so the outline doesn't cover it.
     */
    private class EmoteSpan(private val bitmap: Bitmap, private val draw: Boolean) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val height = paint.fontMetricsInt.let { it.descent - it.ascent }
            return (height * bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            if (!draw) {
                return
            }
            val metrics = paint.fontMetricsInt
            val height = metrics.descent - metrics.ascent
            val width = height * bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            val destination = android.graphics.RectF(x, (y + metrics.ascent).toFloat(), x + width, (y + metrics.descent).toFloat())
            canvas.drawBitmap(bitmap, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    /**
     * Upstream `ChatRenderer.render`: at most `maximumNumberOfMessages` messages, 20 x font size
     * wide, 1 px apart. [posts] is newest first; [emote] returns a loaded emote image or null.
     */
    fun render(posts: List<ChatPost>, settings: SettingsWidgetChat, emote: (String) -> Bitmap?): Bitmap? {
        val shown = posts.take(settings.maximumNumberOfMessages).reversed()
        if (shown.isEmpty()) {
            return null
        }
        val width = (20 * settings.fontSize).roundToInt().coerceAtLeast(1)
        val textX = BAR_WIDTH
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = settings.fontSize
            color = settings.messageColor.argb()
        }
        val stroke = TextPaint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * BORDER_WIDTH
            strokeJoin = Paint.Join.ROUND
            color = settings.shadowColor.argb()
        }
        class Line(val fill: StaticLayout, val stroke: StaticLayout?, val post: ChatPost)
        val lines = shown.map { post ->
            val fillLayout = layout(text(post, settings, emote, colors = true), fill, width - textX)
            val strokeLayout = if (settings.shadowColorEnabled) {
                layout(
                    text(post, settings, emote, colors = false),
                    stroke,
                    width - textX,
                )
            } else {
                null
            }
            Line(fillLayout, strokeLayout, post)
        }
        val height = lines.sumOf { it.fill.height } + lines.size - 1
        if (height <= 0) {
            return null
        }
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val background = Paint().apply {
            val color = settings.backgroundColor
            this.color = color.copy(opacity = BACKGROUND_ALPHA).argb()
        }
        val bar = Paint()
        var y = 0
        for (line in lines) {
            val lineWidth = (0 until line.fill.lineCount).maxOf { line.fill.getLineWidth(it) }
            if (settings.backgroundColorEnabled) {
                canvas.drawRect(textX.toFloat(), y.toFloat(), textX + ceil(lineWidth), (y + line.fill.height).toFloat(), background)
            }
            line.post.highlight?.barColor?.let {
                bar.color = it.argb()
                canvas.drawRect(0f, y.toFloat(), BAR_WIDTH.toFloat(), (y + line.fill.height).toFloat(), bar)
            }
            canvas.withTranslation(textX.toFloat(), y.toFloat()) {
                line.stroke?.draw(this)
                line.fill.draw(this)
            }
            y += line.fill.height + 1
        }
        return bitmap
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    /** Upstream `ChatLineStyle.makeContent`: "user: message"; action messages in the user's colour. */
    private fun text(post: ChatPost, settings: SettingsWidgetChat, emote: (String) -> Bitmap?, colors: Boolean): CharSequence {
        val builder = SpannableStringBuilder()
        val usernameColor = (post.userColor ?: settings.usernameColor).argb()
        fun span(start: Int, what: Any) = builder.setSpan(what, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        var start = builder.length
        builder.append(post.displayName)
        if (colors) {
            span(start, ForegroundColorSpan(usernameColor))
        }
        if (settings.boldUsername) {
            span(start, StyleSpan(Typeface.BOLD))
        }
        builder.append(if (post.isAction) " " else ": ")
        start = builder.length
        for (segment in post.segments) {
            val image = segment.url ?: segment.bigGifUrl
            val bitmap = image?.url(animated = false)?.let(emote)
            when {
                bitmap != null -> {
                    val emoteStart = builder.length
                    builder.append("￼")
                    builder.setSpan(EmoteSpan(bitmap, draw = colors), emoteStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.append(" ")
                }
                image != null -> image.name?.let { builder.append("$it ") }
                else -> segment.text?.let { builder.append(it) }
            }
        }
        if (colors && post.isAction) {
            span(start, ForegroundColorSpan(usernameColor))
        }
        if (settings.boldMessage) {
            span(start, StyleSpan(Typeface.BOLD))
        }
        return builder
    }
}
