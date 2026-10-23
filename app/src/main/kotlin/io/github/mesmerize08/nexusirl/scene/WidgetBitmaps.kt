// Bitmaps for the v1 widgets: upstream Moblin/VideoEffects/Text/TextEffect.swift (TextView),
// ImageEffect.swift and QrCodeEffect.swift @97e26d2.
package io.github.mesmerize08.nexusirl.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.mesmerize08.nexusirl.effects.TextSegment
import io.github.mesmerize08.nexusirl.settings.HorizontalAlignment
import io.github.mesmerize08.nexusirl.settings.SettingsWidgetText
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object WidgetBitmaps {
    /**
     * Draws text lines like upstream's TextView: each line on its own rounded background, 2 px
     * apart, padded by `7 * size / 30 + min(cornerRadius / 5, size / 7.5)` on each side. Ratings
     * are five stars, filled ones yellow.
     */
    fun text(lines: List<List<TextSegment>>, settings: SettingsWidgetText, fontSize: Float): Bitmap? {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = Typeface.DEFAULT
            if (settings.fontMonospacedDigits) {
                fontFeatureSettings = "tnum"
            }
        }
        val foreground = if (settings.clearForegroundColor) Color.TRANSPARENT else settings.foregroundColor.argb()
        val background = if (settings.clearBackgroundColor) Color.TRANSPARENT else settings.backgroundColor.argb()
        val padding = 7 * fontSize / 30 + min(settings.cornerRadius / 5f, fontSize / 7.5f)
        val metrics = paint.fontMetrics
        val lineHeight = ceil(metrics.descent - metrics.ascent)
        val spacing = 2f
        val widths = lines.map { line -> line.sumOf { segment -> segmentWidth(segment, paint).toDouble() }.toFloat() }
        val minWidth = if (settings.widthEnabled) settings.width * (fontSize / settings.fontSize.coerceAtLeast(1)) else 0f
        val lineWidths = widths.map { max(it + 2 * padding, minWidth) }
        val width = ceil(lineWidths.maxOrNull() ?: 0f).toInt()
        val height = ceil(lines.size * lineHeight + (lines.size - 1).coerceAtLeast(0) * spacing).toInt()
        if (width <= 0 || height <= 0 || widths.all { it == 0f }) {
            return null
        }
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val radius = settings.cornerRadius * (fontSize / settings.fontSize.coerceAtLeast(1))
        var top = 0f
        lines.forEachIndexed { index, line ->
            val lineWidth = lineWidths[index]
            // Lines are aligned within the widget like upstream's VStack alignment.
            val left = when (settings.horizontalAlignment) {
                HorizontalAlignment.LEADING -> 0f
                HorizontalAlignment.TRAILING -> width - lineWidth
                HorizontalAlignment.CENTER -> (width - lineWidth) / 2
            }
            if (widths[index] > 0f || line.isEmpty()) {
                canvas.drawRoundRect(RectF(left, top, left + lineWidth, top + lineHeight), radius, radius, backgroundPaint)
            }
            var x = left + when (settings.horizontalAlignment) {
                HorizontalAlignment.LEADING -> padding
                HorizontalAlignment.TRAILING -> lineWidth - padding - widths[index]
                HorizontalAlignment.CENTER -> (lineWidth - widths[index]) / 2
            }
            val baseline = top - metrics.ascent
            for (segment in line) {
                when (segment) {
                    is TextSegment.Text -> {
                        paint.color = foreground
                        canvas.drawText(segment.text, x, baseline, paint)
                    }
                    is TextSegment.Rating -> {
                        for (star in 0 until 5) {
                            val filled = star < segment.stars
                            paint.color = if (filled) Color.YELLOW else foreground
                            canvas.drawText(if (filled) "★" else "☆", x, baseline, paint)
                            x += paint.measureText("★")
                        }
                        continue
                    }
                }
                x += segmentWidth(segment, paint)
            }
            top += lineHeight + spacing
        }
        return bitmap
    }

    private fun segmentWidth(segment: TextSegment, paint: Paint): Float = when (segment) {
        is TextSegment.Text -> paint.measureText(segment.text)
        is TextSegment.Rating -> paint.measureText("★") * 5
    }

    /** An image widget's picture (upstream stores it as `Images/<widget id>`), scaled down to [maxSide]. */
    fun image(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Upstream QrCodeEffect: black modules on white with a quiet zone. */
    fun qrCode(message: String, side: Int): Bitmap? {
        if (message.isEmpty()) {
            return null
        }
        val matrix = try {
            QRCodeWriter().encode(message, BarcodeFormat.QR_CODE, side, side, mapOf(EncodeHintType.MARGIN to 2))
        } catch (e: Exception) {
            return null
        }
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix.get(index % matrix.width, index / matrix.width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
