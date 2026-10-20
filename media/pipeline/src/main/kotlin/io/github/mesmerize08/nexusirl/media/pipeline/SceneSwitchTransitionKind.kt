// Port of the transition maths in Moblin/Media/HaishinKit/Media/Video/VideoEffectsProcessor.swift
// (`calcBlurRadius`, `calcBlurScale`) @97e26d2.
package io.github.mesmerize08.nexusirl.media.pipeline

/** Upstream `SceneSwitchTransition` (VideoUnit.swift): what is shown while a new camera starts. */
enum class SceneSwitchTransitionKind {
    BLUR,
    FREEZE,
    BLUR_AND_ZOOM,
    ;

    /**
     * Blur radius in pixels [seconds] into the transition on a canvas whose long side is
     * [longSide]: 15 + 15 per second up to 2 s (Blur), 5 per second up to 5 s (Blur & zoom), none
     * (Freeze). Upstream scales by long side / 1920.
     */
    fun blurRadius(seconds: Double, longSide: Int): Float = when (this) {
        BLUR -> 15 + seconds.coerceIn(0.0, 2.0).toFloat() * 15
        BLUR_AND_ZOOM -> seconds.coerceIn(0.0, 5.0).toFloat() * 5
        FREEZE -> 0f
    } * (longSide / 1920f)

    /** How much the frame is enlarged: the centre 1 - 0.05 per second (up to 5 s) fills it. */
    fun zoom(seconds: Double): Float = if (this == BLUR_AND_ZOOM) (1 / (1 - seconds.coerceIn(0.0, 5.0) * 0.05)).toFloat() else 1f
}
