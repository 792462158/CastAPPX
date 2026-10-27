package com.android.castappx.video

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Upload RGB once, then let the GPU/codec perform scaling and RGB → YUV conversion. */
internal class EncoderGl(surface: Surface) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(
                floatArrayOf(
                    -1f,
                    -1f,
                    0f,
                    1f,
                    1f,
                    -1f,
                    1f,
                    1f,
                    -1f,
                    1f,
                    0f,
                    0f,
                    1f,
                    1f,
                    1f,
                    0f
                )
            ); position(0)
        }

    init {
        try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            check(
                EGL14.eglChooseConfig(
                    display, intArrayOf(
                        EGL14.EGL_RED_SIZE,
                        8,
                        EGL14.EGL_GREEN_SIZE,
                        8,
                        EGL14.EGL_BLUE_SIZE,
                        8,
                        EGL14.EGL_ALPHA_SIZE,
                        8,
                        EGL14.EGL_RENDERABLE_TYPE,
                        EGL14.EGL_OPENGL_ES2_BIT,
                        0x3142,
                        1,
                        EGL14.EGL_NONE
                    ), 0, configs, 0, 1, IntArray(1), 0
                )
            )
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            window = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, window, window, context))
            fun shader(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
                val status = IntArray(1); GLES20.glGetShaderiv(
                    id,
                    GLES20.GL_COMPILE_STATUS,
                    status,
                    0
                )
                if (status[0] == 0) {
                    GLES20.glDeleteShader(id); error("Unable to compile video shader")
                }
                return id
            }

            val vertex = shader(
                GLES20.GL_VERTEX_SHADER,
                "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){ gl_Position=vec4(aPosition,0.0,1.0); vUv=aUv; }"
            )
            val fragment = shader(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec2 vUv; uniform sampler2D uTexture; void main(){ gl_FragColor=texture2D(uTexture,vUv); }"
            )
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val status = IntArray(1); GLES20.glGetProgramiv(
                program,
                GLES20.GL_LINK_STATUS,
                status,
                0
            )
            check(status[0] != 0)
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
        } catch (error: Exception) {
            close(); throw error
        }
    }

    fun draw(bitmap: Bitmap, width: Int, height: Int, ptsUs: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (textureWidth == bitmap.width && textureHeight == bitmap.height) GLUtils.texSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            bitmap
        )
        else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureWidth = bitmap.width; textureHeight = bitmap.height
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        vertices.position(0); GLES20.glVertexAttribPointer(
            position,
            2,
            GLES20.GL_FLOAT,
            false,
            16,
            vertices
        )
        vertices.position(2); GLES20.glVertexAttribPointer(
            uv,
            2,
            GLES20.GL_FLOAT,
            false,
            16,
            vertices
        )
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        EGLExt.eglPresentationTimeANDROID(display, window, ptsUs * 1000)
        check(EGL14.eglSwapBuffers(display, window))
    }

    fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT) {
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
    }
}
