package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Global video effects (upstream quick buttons "Whirlpool", "Pinch", "4:3", "Gray scale", "Sepia",
 * "Pixellate", "CRT"). Declared in upstream's application order (ModelScene.swift).
 */
enum class VideoEffect {
    WHIRLPOOL,
    PINCH,
    FOUR_THREE,
    GRAY_SCALE,
    SEPIA,
    PIXELLATE,
    CRT,
}

/** Effect settings (upstream `pixellateStrength`, `whirlpoolAngle`, `pinchScale`). */
data class EffectSettings(
    val pixellateStrength: Float = 0.3f,
    val whirlpoolAngle: Float = (Math.PI / 2).toFloat(),
    val pinchScale: Float = 0.5f,
)
