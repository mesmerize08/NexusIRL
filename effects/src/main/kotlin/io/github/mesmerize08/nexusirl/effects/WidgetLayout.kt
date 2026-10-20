// Port of the layout functions in Moblin/VideoEffects/EffectUtils.swift @97e26d2.
package io.github.mesmerize08.nexusirl.effects

import io.github.mesmerize08.nexusirl.settings.SettingsSceneWidget
import kotlin.math.max
import kotlin.math.min

/** A widget's placement on the canvas, in pixels from the top-left corner. */
data class WidgetRect(val x: Double, val y: Double, val width: Double, val height: Double)

object WidgetLayout {
    /** Upstream `toPixels`: a percentage of [total]. */
    fun toPixels(percentage: Double, total: Double): Double = percentage * total / 100

    /**
     * Upstream `layoutScale`: the largest scale that fits the content in a box of `size` percent of
     * the canvas on both axes, keeping its aspect ratio.
     */
    fun scale(layout: SettingsSceneWidget, contentWidth: Double, contentHeight: Double, canvasWidth: Double, canvasHeight: Double): Double =
        min(toPixels(layout.size, canvasWidth) / contentWidth, toPixels(layout.size, canvasHeight) / contentHeight)

    private fun offset(offset: Double, size: Double, canvas: Double, isCenter: Boolean, isNear: Boolean): Double = when {
        isCenter -> (canvas - size) / 2
        isNear -> toPixels(offset, canvas)
        else -> canvas - toPixels(offset, canvas) - size
    }

    /**
     * Upstream `layoutPosition` with a top-left origin: x/y are percentages from the alignment
     * corner; centred axes ignore their offset.
     */
    fun place(
        layout: SettingsSceneWidget,
        contentWidth: Double,
        contentHeight: Double,
        canvasWidth: Double,
        canvasHeight: Double,
        resize: Boolean,
    ): WidgetRect {
        val factor = if (resize) scale(layout, contentWidth, contentHeight, canvasWidth, canvasHeight) else 1.0
        val width = contentWidth * factor
        val height = contentHeight * factor
        val alignment = layout.alignment
        return WidgetRect(
            x = offset(layout.x, width, canvasWidth, alignment.isHorizontalCenter, alignment.isLeft),
            y = offset(layout.y, height, canvasHeight, alignment.isVerticalCenter, alignment.isTop),
            width = width,
            height = height,
        )
    }

    /** Upstream text scaling: font sizes are designed for a 1920 pixel long side. */
    fun scaledFontSize(fontSize: Int, canvasWidth: Int, canvasHeight: Int): Float = fontSize * (max(canvasWidth, canvasHeight) / 1920f)
}
