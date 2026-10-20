package io.github.mesmerize08.nexusirl.media.pipeline.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws 2D textures: widget bitmaps onto the canvas (upstream: compositing an effect image at its
 * layout), and the composited canvas onto the encoder and preview surfaces.
 */
internal class TextureRenderer {
    private val program = GlPrograms.create(VERTEX_SHADER, FRAGMENT_SHADER)
    private val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
    private val textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
    private val alphaLocation = GLES20.glGetUniformLocation(program, "uAlpha")
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * Draws [textureId] into the pixel rectangle ([x], [y], [width], [height]) of a
     * [targetWidth]x[targetHeight] target, top-left origin. Bitmap textures have their first row
     * at the top ([flipY] true); framebuffer textures have it at the bottom. Blends premultiplied
     * alpha, as Android bitmaps are.
     */
    fun drawRect(
        textureId: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        targetWidth: Int,
        targetHeight: Int,
        flipY: Boolean,
        alpha: Float = 1f,
    ) {
        val left = x / targetWidth * 2 - 1
        val right = (x + width) / targetWidth * 2 - 1
        val top = 1 - y / targetHeight * 2
        val bottom = 1 - (y + height) / targetHeight * 2
        val (tBottom, tTop) = if (flipY) 1f to 0f else 0f to 1f
        vertices.clear()
        vertices.put(floatArrayOf(left, bottom, 0f, tBottom, right, bottom, 1f, tBottom, left, top, 0f, tTop, right, top, 1f, tTop))
        draw(textureId, alpha, blend = true)
    }

    /**
     * Draws a framebuffer texture of aspect [contentAspect] filling a [targetWidth]x[targetHeight]
     * viewport (cropping to keep the aspect ratio), as the preview and encoders need.
     */
    fun drawFill(textureId: Int, targetWidth: Int, targetHeight: Int, contentAspect: Float) {
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val targetAspect = targetWidth.toFloat() / targetHeight
        val (scaleX, scaleY) = if (contentAspect > targetAspect) contentAspect / targetAspect to 1f else 1f to targetAspect / contentAspect
        vertices.clear()
        vertices.put(floatArrayOf(-scaleX, -scaleY, 0f, 0f, scaleX, -scaleY, 1f, 0f, -scaleX, scaleY, 0f, 1f, scaleX, scaleY, 1f, 1f))
        draw(textureId, 1f, blend = false)
    }

    private fun draw(textureId: Int, alpha: Float, blend: Boolean) {
        GLES20.glUseProgram(program)
        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        GLES20.glUniform1f(alphaLocation, alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(positionLocation)
        vertices.position(2)
        GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoordinate;
            varying vec2 vTextureCoordinate;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTextureCoordinate = aTextureCoordinate;
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoordinate;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoordinate) * uAlpha;
            }
        """
    }
}
