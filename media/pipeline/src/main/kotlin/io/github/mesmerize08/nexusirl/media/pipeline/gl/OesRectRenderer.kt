package io.github.mesmerize08.nexusirl.media.pipeline.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws an external (OES) texture, such as a browser widget's virtual display, into a pixel
 * rectangle of the canvas with alpha blending. The texture matrix comes from its SurfaceTexture.
 */
internal class OesRectRenderer {
    private val program = GlPrograms.create(VERTEX_SHADER, FRAGMENT_SHADER)
    private val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
    private val textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
    private val textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Creates an OES texture for a SurfaceTexture. */
    fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    fun drawRect(
        textureId: Int,
        textureMatrix: FloatArray,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        val left = x / targetWidth * 2 - 1
        val right = (x + width) / targetWidth * 2 - 1
        val top = 1 - y / targetHeight * 2
        val bottom = 1 - (y + height) / targetHeight * 2
        vertices.clear()
        // SurfaceTexture coordinates have their origin at the bottom left, like GL.
        vertices.put(floatArrayOf(left, bottom, 0f, 0f, right, bottom, 1f, 0f, left, top, 0f, 1f, right, top, 1f, 1f))
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Web content is premultiplied.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
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
            attribute vec4 aTextureCoordinate;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoordinate;
            void main() {
                gl_Position = vec4(aPosition.xy, 0.0, 1.0);
                vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoordinate;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoordinate);
            }
        """
    }
}
