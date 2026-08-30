package com.depthmaker.app.toon

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL setup around the encoder's input Surface.
 *
 * EGL_RECORDABLE_ANDROID is the load-bearing bit: without it the config the
 * driver hands back may be one the video encoder cannot consume, and
 * eglCreateWindowSurface fails on the encoder Surface on several vendors.
 */
class EglCore(surface: Surface) {

    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "No recordable EGL config" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    /** Timestamps the frame for the encoder. Called before swap, never after. */
    fun setPresentationTime(nanoseconds: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanoseconds)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
