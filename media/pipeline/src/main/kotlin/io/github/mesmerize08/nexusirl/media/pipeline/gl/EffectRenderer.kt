// GLSL versions of upstream's global effects (Moblin/VideoEffects/*Effect.swift @97e26d2): the
// colour matrices and parameters are upstream's MetalPetal ones.
package io.github.mesmerize08.nexusirl.media.pipeline.gl

import android.opengl.GLES20
import io.github.mesmerize08.nexusirl.media.pipeline.VideoEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/** Applies one [VideoEffect] from a canvas texture into the bound framebuffer. */
internal class EffectRenderer {
    private class Program(val id: Int) {
        val position = GLES20.glGetAttribLocation(id, "aPosition")
        val size = GLES20.glGetUniformLocation(id, "uSize")
        val parameter = GLES20.glGetUniformLocation(id, "uParameter")
    }

    private val programs = HashMap<VideoEffect, Program>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    /** [parameter] is the effect's setting: pixellate strength, whirlpool angle or pinch scale. */
    fun apply(effect: VideoEffect, sourceTexture: Int, width: Int, height: Int, parameter: Float) {
        val program = programs.getOrPut(effect) { Program(GlPrograms.create(VERTEX_SHADER, PRELUDE + fragmentShader(effect))) }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program.id)
        GLES20.glUniform2f(program.size, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(program.parameter, parameterFor(effect, parameter, width, height))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        quad.position(0)
        GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glEnableVertexAttribArray(program.position)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun parameterFor(effect: VideoEffect, parameter: Float, width: Int, height: Int): Float = when (effect) {
        // Upstream `pixellateCalcScale`: block size in pixels.
        VideoEffect.PIXELLATE -> 20 * (max(width, height) / 1920f) * (1 + 5 * parameter)
        else -> parameter
    }

    fun release() {
        programs.values.forEach { GLES20.glDeleteProgram(it.id) }
        programs.clear()
    }

    private fun fragmentShader(effect: VideoEffect): String = when (effect) {
        // Upstream GrayScaleEffect: Rec. 709 luminance times 0.75.
        VideoEffect.GRAY_SCALE ->
            """
            void main() {
                vec4 c = texture2D(uTexture, uv());
                float gray = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)) * 0.75;
                gl_FragColor = vec4(vec3(gray), c.a);
            }
        """
        // Upstream SepiaEffect colour matrix.
        VideoEffect.SEPIA ->
            """
            void main() {
                vec4 c = texture2D(uTexture, uv());
                gl_FragColor = vec4(
                    min(dot(c.rgb, vec3(0.393, 0.769, 0.189)), 1.0),
                    min(dot(c.rgb, vec3(0.349, 0.686, 0.168)), 1.0),
                    min(dot(c.rgb, vec3(0.272, 0.534, 0.131)), 1.0),
                    c.a);
            }
        """
        // Upstream PixellateEffect: square blocks anchored at the origin.
        VideoEffect.PIXELLATE ->
            """
            void main() {
                vec2 pixel = gl_FragCoord.xy;
                vec2 block = (floor(pixel / uParameter) + 0.5) * uParameter;
                gl_FragColor = texture2D(uTexture, clamp(block / uSize, 0.0, 1.0));
            }
        """
        // Upstream FourThreeEffect: black bars of 1/8 width on both sides.
        VideoEffect.FOUR_THREE ->
            """
            void main() {
                vec2 p = uv();
                gl_FragColor = (p.x < 0.125 || p.x > 0.875) ? vec4(0.0, 0.0, 0.0, 1.0) : texture2D(uTexture, p);
            }
        """
        // Upstream WhirlpoolEffect (twirl): angle uParameter at the centre, radius min(w, h) / 1.9.
        VideoEffect.WHIRLPOOL ->
            """
            void main() {
                vec2 center = uSize / 2.0;
                vec2 offset = gl_FragCoord.xy - center;
                float radius = min(uSize.x, uSize.y) / 1.9;
                float distance = length(offset);
                if (distance < radius) {
                    float angle = uParameter * (1.0 - distance / radius) * (1.0 - distance / radius);
                    float s = sin(angle);
                    float c = cos(angle);
                    offset = vec2(c * offset.x - s * offset.y, s * offset.x + c * offset.y);
                }
                gl_FragColor = texture2D(uTexture, (center + offset) / uSize);
            }
        """
        // Upstream PinchEffect: scale uParameter, radius min(w, h) / 2.
        VideoEffect.PINCH ->
            """
            void main() {
                vec2 center = uSize / 2.0;
                vec2 offset = gl_FragCoord.xy - center;
                float radius = min(uSize.x, uSize.y) / 2.0;
                float distance = length(offset);
                if (distance < radius && distance > 0.0) {
                    float percent = distance / radius;
                    offset *= pow(percent, uParameter) / percent;
                }
                gl_FragColor = texture2D(uTexture, (center + offset) / uSize);
            }
        """
        // Upstream CrtEffect: 4:3 crop, scanlines, barrel distortion, colours and a vignette.
        VideoEffect.CRT ->
            """
            void main() {
                vec2 pixel = gl_FragCoord.xy;
                float left = uSize.x / 8.0;
                float width = uSize.x * 0.75;
                if (pixel.x < left || pixel.x > left + width) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                // Upstream crtBarrelDistortion: strength 0.1 around the frame centre.
                vec2 center = uSize / 2.0;
                float scale = min(center.x, center.y);
                vec2 coord = (pixel - center) / scale;
                vec2 distorted = coord * (1.0 + 0.1 * dot(coord, coord)) * scale + center;
                if (distorted.x < left || distorted.x > left + width || distorted.y < 0.0 || distorted.y > uSize.y) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec3 c = texture2D(uTexture, distorted / uSize).rgb;
                float line = max(1.0, uSize.y / 240.0);
                if (mod(distorted.y, 2.0 * line) < line) {
                    c *= 0.75;
                }
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = mix(vec3(luma), c, 0.7);
                c = (c - 0.5) * 1.05 + 0.5 - 0.02;
                float vignette = 1.0 - 0.35 * dot(coord, coord);
                gl_FragColor = vec4(clamp(c * vignette, 0.0, 1.0), 1.0);
            }
        """
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            void main() {
                gl_Position = aPosition;
            }
        """
        const val PRELUDE = """
            precision highp float;
            uniform sampler2D uTexture;
            uniform vec2 uSize;
            uniform float uParameter;
            vec2 uv() {
                return gl_FragCoord.xy / uSize;
            }
        """
    }
}
