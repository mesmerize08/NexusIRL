package io.github.mesmerize08.nexusirl.media.pipeline.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws a camera frame (an external OES texture) aspect-filled into the current surface.
 * This is the base of the compositor; scene effects (Phase 4) are added as later passes.
 */
class OesRenderer {
    private val program: Int
    private val positionLocation: Int
    private val textureCoordinateLocation: Int
    private val textureMatrixLocation: Int
    private val scaleLocation: Int
    val textureId: Int
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, s, t
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    init {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
        textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        scaleLocation = GLES20.glGetUniformLocation(program, "uScale")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /**
     * Draws the camera texture into a [targetWidth]x[targetHeight] viewport, filling it while
     * keeping the aspect ratio (upstream "fill frame"); [sourceAspect] is width/height of the image.
     */
    fun draw(textureMatrix: FloatArray, targetWidth: Int, targetHeight: Int, sourceAspect: Float) {
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val targetAspect = targetWidth.toFloat() / targetHeight
        val (scaleX, scaleY) = if (sourceAspect > targetAspect) {
            sourceAspect / targetAspect to 1f
        } else {
            1f to targetAspect / sourceAspect
        }
        GLES20.glUniform2f(scaleLocation, scaleX, scaleY)
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        quad.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(positionLocation)
        quad.position(2)
        GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Draws black (upstream's black frame when no source is available). */
    fun drawBlack(targetWidth: Int, targetHeight: Int) {
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoordinate;
            uniform mat4 uTextureMatrix;
            uniform vec2 uScale;
            varying vec2 vTextureCoordinate;
            void main() {
                gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);
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
