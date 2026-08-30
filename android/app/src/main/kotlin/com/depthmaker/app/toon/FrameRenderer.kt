package com.depthmaker.app.toon

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Two GL passes per frame.
 *
 * Pass 1 samples the decoder's external-OES texture through the SurfaceTexture
 * transform (plus the source's rotation) into an ordinary RGBA texture at the
 * export size. Pass 2 runs the filter over that texture into the encoder's
 * surface.
 *
 * The split exists so the filters can offset their sample coordinates freely:
 * on the OES texture those offsets would be skewed by the transform matrix, and
 * every neighbour-tap filter (Sobel, the smoothing kernel, the halftone grid)
 * would be subtly wrong on any device whose decoder hands back a rotated or
 * cropped buffer.
 */
class FrameRenderer(
    private val width: Int,
    private val height: Int,
    private val filter: ToonFilter,
    private val strength: Float,
    rotationDegrees: Int
) {
    private var oesTexture = 0
    private var intermediateTexture = 0
    private var framebuffer = 0
    private var copyProgram = 0
    private var filterProgram = 0

    private val texMatrix = FloatArray(16)
    private val rotMatrix = FloatArray(16)

    private val frameLock = java.lang.Object()
    private var frameAvailable = false

    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var inputSurface: Surface
        private set

    init {
        // Output pixels are already in the corrected orientation, so the source
        // is sampled through the inverse rotation.
        Matrix.setIdentityM(rotMatrix, 0)
        Matrix.translateM(rotMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rotMatrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rotMatrix, 0, -0.5f, -0.5f, 0f)
    }

    /** Must run on the thread holding the EGL context. */
    fun setup() {
        copyProgram = GlUtil.compileProgram(COPY_VERTEX, COPY_FRAGMENT)
        filterProgram = GlUtil.compileProgram(FILTER_VERTEX, filter.fragmentShader)
        oesTexture = GlUtil.createOesTexture()
        intermediateTexture = GlUtil.createTexture(width, height)
        framebuffer = GlUtil.createFramebuffer(intermediateTexture)
        surfaceTexture = SurfaceTexture(oesTexture)
        surfaceTexture.setDefaultBufferSize(width, height)
        // Explicit main-looper handler: the transcode thread has no Looper of
        // its own, and it is about to block waiting for this callback.
        surfaceTexture.setOnFrameAvailableListener(
            {
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            },
            Handler(Looper.getMainLooper())
        )
        inputSurface = Surface(surfaceTexture)
    }

    /**
     * Blocks until the decoder's released frame has actually landed on the
     * SurfaceTexture. releaseOutputBuffer(render = true) only queues it —
     * calling updateTexImage without this wait re-encodes the previous frame on
     * whichever devices happen to lose the race.
     */
    fun awaitFrame(timeoutMs: Long = 2_500L) {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                check(remaining > 0) { "timed out waiting for a decoded frame" }
                frameLock.wait(remaining)
            }
            frameAvailable = false
        }
    }

    /** Consumes the pending decoder frame and leaves the filtered result in the
     *  EGL window surface, ready to swap. */
    fun drawFrame() {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(copyProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "uRotMatrix"), 1, false, rotMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uTex"), 0)
        drawQuad(copyProgram)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(filterProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(filterProgram, "uTex"), 0)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(filterProgram, "uTexel"),
            1f / width.toFloat(),
            1f / height.toFloat()
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(filterProgram, "uStrength"), strength)
        drawQuad(filterProgram)

        GlUtil.checkError("drawFrame")
    }

    private fun drawQuad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")

        GlUtil.QUAD.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, GlUtil.STRIDE, GlUtil.QUAD)
        GLES20.glEnableVertexAttribArray(position)

        GlUtil.QUAD.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, GlUtil.STRIDE, GlUtil.QUAD)
        GLES20.glEnableVertexAttribArray(texCoord)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
        GlUtil.QUAD.position(0)
    }

    fun release() {
        if (::inputSurface.isInitialized) inputSurface.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (intermediateTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(intermediateTexture), 0)
        if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
        if (copyProgram != 0) GLES20.glDeleteProgram(copyProgram)
        if (filterProgram != 0) GLES20.glDeleteProgram(filterProgram)
    }

    private companion object {
        const val COPY_VERTEX = """
uniform mat4 uTexMatrix;
uniform mat4 uRotMatrix;
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = (uTexMatrix * uRotMatrix * aTexCoord).xy;
}
"""

        const val COPY_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES uTex;
void main() { gl_FragColor = texture2D(uTex, vTex); }
"""

        const val FILTER_VERTEX = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTex;
void main() {
    gl_Position = aPosition;
    vTex = aTexCoord.xy;
}
"""
    }
}
