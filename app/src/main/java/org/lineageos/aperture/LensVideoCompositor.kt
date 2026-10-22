/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Blends both live lens feeds into the preview and one continuous encoder surface. */
internal class LensVideoCompositor(
    private val textureView: TextureView,
    initialWidth: Int,
    initialHeight: Int,
    initialFps: Int,
    initialAspectRatio: Float,
    private val onActiveFrame: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private data class Input(
        val id: String,
        val textureId: Int,
        val texture: SurfaceTexture,
        val surface: Surface,
        val encoderRotationSteps: Int,
        val transform: FloatArray = FloatArray(16).apply { Matrix.setIdentityM(this, 0) },
        var frameAvailable: Boolean = false,
        var hasFrame: Boolean = false,
    )

    private data class PendingSwitch(
        val cameraId: String,
        val mainZoom: Float,
        val onComplete: (() -> Unit)?,
    )

    private val tag = "LensCompositor"
    private val thread = HandlerThread("ApertureLensCompositor").apply { start() }
    private val glHandler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val released = AtomicBoolean(false)
    private val profileUpdateId = AtomicInteger(0)

    private var inputWidth = initialWidth
    private var inputHeight = initialHeight
    private var fps = initialFps.coerceAtLeast(1)
    private var contentAspectRatio = initialAspectRatio.coerceAtLeast(0.01f)
    private var frameIntervalMs = max(1L, 1000L / fps)
    private var display: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: android.opengl.EGLConfig? = null
    private var context: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var previewSurface: Surface? = null
    private var previewEglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private val inputs = mutableMapOf<String, Input>()

    private var program = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var mainSamplerLocation = -1
    private var ultraSamplerLocation = -1
    private var mainMatrixLocation = -1
    private var ultraMatrixLocation = -1
    private var blendLocation = -1
    private var mainZoomLocation = -1
    private var ultraZoomLocation = -1
    private var aspectCropLocation = -1
    private var mainPixelRotationLocation = -1
    private var ultraPixelRotationLocation = -1
    private var vertexBuffer: FloatBuffer? = null

    private var activeLensId = LensRecordingEngine.CAMERA_MAIN_ID
    private var transitionFrom: String? = null
    private var transitionTo: String? = null
    private var transitionStartNs = 0L
    private var pendingSwitch: PendingSwitch? = null
    private var transitionCompleteCallback: (() -> Unit)? = null
    private var mainZoomRatio = 1f
    private var activeFrameSent = false
    private var renderScheduled = false
    private var renderFailed = false
    private var lastRenderStartNs = 0L
    private var onEncoderReady: ((Throwable?) -> Unit)? = null
    private var onEncoderDetached: (() -> Unit)? = null

    private val renderTask = object : Runnable {
        override fun run() {
            renderScheduled = false
            if (released.get()) return
            lastRenderStartNs = System.nanoTime()
            try {
                renderFrame()
            } catch (t: Throwable) {
                renderFailed = true
                Log.w(tag, "render failed", t)
                mainHandler.post { onError(t) }
            }
            scheduleNextFrame()
        }
    }

    fun initialize(onReady: (Map<String, Surface>) -> Unit) {
        glHandler.post {
            try {
                initializeEgl()
                compileProgram()
                createPreviewOutput()
                createInput(LensRecordingEngine.CAMERA_MAIN_ID)
                createInput(LensRecordingEngine.CAMERA_ULTRA_WIDE_ID)
                createInput(LensRecordingEngine.CAMERA_FRONT_ID)
                scheduleNextFrame()
                val surfaces = inputs.mapValues { it.value.surface }
                mainHandler.post { onReady(surfaces) }
            } catch (t: Throwable) {
                Log.e(tag, "initialization failed", t)
                mainHandler.post { onError(t) }
            }
        }
    }

    fun updateProfile(
        width: Int,
        height: Int,
        fps: Int,
        aspectRatio: Float,
        callback: (Throwable?) -> Unit,
    ) {
        val updateId = profileUpdateId.incrementAndGet()
        glHandler.post {
            if (updateId != profileUpdateId.get()) return@post
            try {
                inputWidth = width
                inputHeight = height
                this.fps = fps.coerceAtLeast(1)
                contentAspectRatio = aspectRatio.coerceAtLeast(0.01f)
                frameIntervalMs = max(1L, 1000L / this.fps)
                inputs.values.forEach {
                    it.texture.setDefaultBufferSize(inputWidth, inputHeight)
                    it.frameAvailable = false
                    it.hasFrame = false
                }
                mainHandler.post { callback(null) }
            } catch (t: Throwable) {
                mainHandler.post { callback(t) }
            }
        }
    }

    fun updatePreviewTransform() {
        glHandler.post {
            try {
                val texture = textureView.surfaceTexture ?: return@post
                texture.setDefaultBufferSize(
                    textureView.width.coerceAtLeast(1),
                    textureView.height.coerceAtLeast(1),
                )
                if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, previewEglSurface)
                }
                val nativeSurface = previewSurface ?: return@post
                previewEglSurface = createWindowSurface(nativeSurface)
            } catch (t: Throwable) {
                mainHandler.post { onError(t) }
            }
        }
    }

    /** The MediaRecorder surface is an EGL target, not a Camera2 producer surface. */
    fun attachEncoderSurface(surface: Surface, callback: (Throwable?) -> Unit) {
        onEncoderReady = callback
        glHandler.post {
            try {
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, encoderEglSurface)
                }
                encoderEglSurface = createWindowSurface(surface)
                mainHandler.post { onEncoderReady?.invoke(null) }
            } catch (t: Throwable) {
                mainHandler.post { onEncoderReady?.invoke(t) }
            }
        }
    }

    fun detachEncoderSurface(callback: () -> Unit) {
        onEncoderDetached = callback
        glHandler.post {
            try {
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    makeCurrent(previewEglSurface)
                    EGL14.eglDestroySurface(display, encoderEglSurface)
                }
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            } catch (t: Throwable) {
                Log.w(tag, "encoder detach failed", t)
            }
            mainHandler.post { onEncoderDetached?.invoke() }
        }
    }

    fun switchTo(
        cameraId: String,
        mainZoom: Float,
        waitForFrame: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ) {
        if (cameraId != LensRecordingEngine.CAMERA_MAIN_ID &&
            cameraId != LensRecordingEngine.CAMERA_ULTRA_WIDE_ID &&
            cameraId != LensRecordingEngine.CAMERA_FRONT_ID
        ) return
        glHandler.post {
            if (cameraId == activeLensId && transitionTo == null) {
                mainHandler.post { onComplete?.invoke() }
                return@post
            }
            val target = inputs[cameraId]
            if (waitForFrame && target?.hasFrame != true) {
                pendingSwitch = PendingSwitch(cameraId, mainZoom, onComplete)
            } else {
                beginTransition(cameraId, mainZoom, onComplete)
            }
        }
    }

    fun resetInputFrame(cameraId: String) {
        glHandler.post {
            inputs[cameraId]?.apply {
                frameAvailable = false
                hasFrame = false
            }
        }
    }

    fun setMainZoomRatio(zoom: Float) {
        glHandler.post { mainZoomRatio = zoom.coerceAtLeast(1f) }
    }

    fun release(onReleased: (() -> Unit)? = null) {
        if (!released.compareAndSet(false, true)) {
            onReleased?.invoke()
            return
        }
        glHandler.removeCallbacksAndMessages(null)
        glHandler.post {
            try {
                inputs.values.forEach {
                    runCatching { it.surface.release() }
                    runCatching { it.texture.setOnFrameAvailableListener(null) }
                    runCatching { it.texture.release() }
                    GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0)
                }
                inputs.clear()
                cleanupEgl()
                mainHandler.post { onReleased?.invoke() }
            } catch (t: Throwable) {
                Log.w(tag, "release failed", t)
                mainHandler.post { onReleased?.invoke() }
            } finally {
                thread.quitSafely()
            }
        }
    }

    private fun initializeEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "eglChooseConfig failed"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)) {
            "eglMakeCurrent failed"
        }
    }

    private fun createPreviewOutput() {
        val texture = textureView.surfaceTexture ?: error("preview SurfaceTexture unavailable")
        texture.setDefaultBufferSize(textureView.width.coerceAtLeast(1), textureView.height.coerceAtLeast(1))
        previewSurface = Surface(texture)
        previewEglSurface = createWindowSurface(previewSurface!!)
    }

    private fun createInput(id: String) {
        val idArray = IntArray(1)
        GLES20.glGenTextures(1, idArray, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, idArray[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val texture = SurfaceTexture(idArray[0]).apply { setDefaultBufferSize(inputWidth, inputHeight) }
        // Camera 1 is mounted 180 degrees from the rear sensors; MediaRecorder's track hint stays fixed.
        val input = Input(
            id,
            idArray[0],
            texture,
            Surface(texture),
            encoderRotationSteps = if (id == LensRecordingEngine.CAMERA_FRONT_ID) 2 else 0,
        )
        texture.setOnFrameAvailableListener({ input.frameAvailable = true }, glHandler)
        inputs[id] = input
    }

    private fun createWindowSurface(surface: Surface): android.opengl.EGLSurface {
        val result = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(result != null && result != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}"
        }
        return result
    }

    private fun compileProgram() {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        mainSamplerLocation = GLES20.glGetUniformLocation(program, "uMain")
        ultraSamplerLocation = GLES20.glGetUniformLocation(program, "uUltra")
        mainMatrixLocation = GLES20.glGetUniformLocation(program, "uMainMatrix")
        ultraMatrixLocation = GLES20.glGetUniformLocation(program, "uUltraMatrix")
        blendLocation = GLES20.glGetUniformLocation(program, "uBlend")
        mainZoomLocation = GLES20.glGetUniformLocation(program, "uMainZoom")
        ultraZoomLocation = GLES20.glGetUniformLocation(program, "uUltraZoom")
        aspectCropLocation = GLES20.glGetUniformLocation(program, "uAspectCrop")
        mainPixelRotationLocation = GLES20.glGetUniformLocation(program, "uMainPixelRotation")
        ultraPixelRotationLocation = GLES20.glGetUniformLocation(program, "uUltraPixelRotation")
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(VERTICES); position(0) }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun scheduleNextFrame() {
        if (renderScheduled || released.get() || renderFailed) return
        renderScheduled = true
        val elapsed = if (lastRenderStartNs == 0L) 0L else {
            (System.nanoTime() - lastRenderStartNs) / 1_000_000L
        }
        glHandler.postDelayed(renderTask, (frameIntervalMs - elapsed).coerceAtLeast(0L))
    }

    private fun renderFrame() {
        lastRenderStartNs = System.nanoTime()
        inputs.values.forEach { input ->
            if (input.frameAvailable) {
                input.frameAvailable = false
                input.texture.updateTexImage()
                input.texture.getTransformMatrix(input.transform)
                input.hasFrame = true
            }
        }

        pendingSwitch?.let { request ->
            if (inputs[request.cameraId]?.hasFrame == true) {
                pendingSwitch = null
                beginTransition(request.cameraId, request.mainZoom, request.onComplete)
            }
        }

        val active = inputs[activeLensId] ?: return
        if (!active.hasFrame) return

        val now = System.nanoTime()
        var first = active
        var second = active
        var blend = 0f
        var firstZoom = zoomFor(activeLensId)
        var secondZoom = firstZoom
        val from = transitionFrom
        val to = transitionTo
        if (from != null && to != null) {
            val fromInput = inputs[from]
            val toInput = inputs[to]
            if (fromInput?.hasFrame == true && toInput?.hasFrame == true) {
                val t = ((now - transitionStartNs).toFloat() / TRANSITION_NS).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                first = fromInput
                second = toInput
                blend = eased
                firstZoom = zoomFor(from)
                secondZoom = zoomFor(to)
                if (from == LensRecordingEngine.CAMERA_MAIN_ID &&
                    to == LensRecordingEngine.CAMERA_ULTRA_WIDE_ID
                ) {
                    secondZoom = (mainZoomRatio / ULTRA_WIDE_RELATIVE_ZOOM).coerceAtLeast(1f) *
                            (1f - eased) + eased
                } else if (from == LensRecordingEngine.CAMERA_ULTRA_WIDE_ID &&
                    to == LensRecordingEngine.CAMERA_MAIN_ID
                ) {
                    firstZoom = 1f + ((mainZoomRatio / ULTRA_WIDE_RELATIVE_ZOOM).coerceAtLeast(1f) - 1f) *
                            eased
                }
                if (t >= 1f) {
                    activeLensId = to
                    transitionFrom = null
                    transitionTo = null
                    val onComplete = transitionCompleteCallback
                    transitionCompleteCallback = null
                    mainHandler.post {
                        onActiveFrame()
                        onComplete?.invoke()
                    }
                }
            }
        }

        draw(
            previewEglSurface,
            first,
            second,
            blend,
            firstZoom,
            secondZoom,
            null,
            contentAspectRatio,
            applySurfaceTransform = true,
        )
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            draw(
                encoderEglSurface,
                first,
                second,
                blend,
                firstZoom,
                secondZoom,
                now,
                inputWidth.toFloat() / inputHeight.coerceAtLeast(1),
                applySurfaceTransform = false,
            )
        }
        if (!activeFrameSent && inputs[activeLensId]?.hasFrame == true) {
            activeFrameSent = true
            mainHandler.post { onActiveFrame() }
        }
    }

    private fun beginTransition(cameraId: String, mainZoom: Float, onComplete: (() -> Unit)?) {
        if (cameraId == activeLensId && transitionTo == null) {
            mainHandler.post { onComplete?.invoke() }
            return
        }
        transitionFrom = transitionTo ?: activeLensId
        transitionTo = cameraId
        transitionStartNs = System.nanoTime()
        mainZoomRatio = mainZoom.coerceAtLeast(1f)
        transitionCompleteCallback = onComplete
        mainHandler.post { onActiveFrame() }
    }

    private fun zoomFor(cameraId: String): Float = when (cameraId) {
        LensRecordingEngine.CAMERA_MAIN_ID -> mainZoomRatio
        else -> 1f
    }

    private fun draw(
        target: android.opengl.EGLSurface,
        main: Input,
        ultra: Input,
        blend: Float,
        mainZoom: Float,
        ultraZoom: Float,
        presentationNs: Long?,
        targetAspectRatio: Float,
        applySurfaceTransform: Boolean,
    ) {
        if (target == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, target, target, context)) return
        EGL14.eglSwapInterval(display, 0)
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, target, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, target, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val sourceAspect = if (applySurfaceTransform && swapsAxes(main.transform)) {
            1f / targetAspectRatio
        } else {
            targetAspectRatio
        }
        val outputAspect = w[0].toFloat() / h[0].coerceAtLeast(1)
        val viewportW: Int
        val viewportH: Int
        if (outputAspect > sourceAspect) {
            viewportH = h[0]
            viewportW = (viewportH * sourceAspect).roundToInt()
        } else {
            viewportW = w[0]
            viewportH = (viewportW / sourceAspect).roundToInt()
        }
        GLES20.glViewport((w[0] - viewportW) / 2, (h[0] - viewportH) / 2, viewportW, viewportH)
        GLES20.glUseProgram(program)
        val vertices = vertexBuffer ?: return
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        val mainMatrix = if (applySurfaceTransform) main.transform else RAW_TEXTURE_MATRIX
        val ultraMatrix = if (applySurfaceTransform) ultra.transform else RAW_TEXTURE_MATRIX
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, main.textureId)
        GLES20.glUniform1i(mainSamplerLocation, 0)
        GLES20.glUniformMatrix4fv(mainMatrixLocation, 1, false, mainMatrix, 0)
        GLES20.glUniform1f(mainZoomLocation, mainZoom)
        GLES20.glUniform1i(
            mainPixelRotationLocation,
            if (applySurfaceTransform) 0 else main.encoderRotationSteps,
        )
        val inputAspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
        val cropScaleX = if (inputAspect > targetAspectRatio) targetAspectRatio / inputAspect else 1f
        val cropScaleY = if (inputAspect < targetAspectRatio) inputAspect / targetAspectRatio else 1f
        GLES20.glUniform2f(aspectCropLocation, cropScaleX, cropScaleY)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ultra.textureId)
        GLES20.glUniform1i(ultraSamplerLocation, 1)
        GLES20.glUniformMatrix4fv(ultraMatrixLocation, 1, false, ultraMatrix, 0)
        GLES20.glUniform1f(ultraZoomLocation, ultraZoom)
        GLES20.glUniform1i(
            ultraPixelRotationLocation,
            if (applySurfaceTransform) 0 else ultra.encoderRotationSteps,
        )
        GLES20.glUniform1f(blendLocation, blend)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        presentationNs?.let { EGLExt.eglPresentationTimeANDROID(display, target, it) }
        check(EGL14.eglSwapBuffers(display, target)) { "eglSwapBuffers failed 0x${EGL14.eglGetError().toString(16)}" }
    }

    private fun cleanupEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderEglSurface)
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewEglSurface)
        previewSurface?.release()
        if (program != 0) GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun makeCurrent(surface: android.opengl.EGLSurface): Boolean =
        surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)

    private fun swapsAxes(matrix: FloatArray): Boolean =
        abs(matrix[0]) + abs(matrix[5]) < abs(matrix[1]) + abs(matrix[4])

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val TRANSITION_NS = 520_000_000L
        private const val ULTRA_WIDE_RELATIVE_ZOOM = 0.6f
        private const val FLOAT_SIZE_BYTES = 4
        private val VERTICES = floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)

        private val RAW_TEXTURE_MATRIX = FloatArray(16).apply {
            Matrix.setIdentityM(this, 0)
            this[5] = -1f
            this[13] = 1f
        }

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uMain;
            uniform samplerExternalOES uUltra;
            uniform mat4 uMainMatrix;
            uniform mat4 uUltraMatrix;
            uniform float uBlend;
            uniform float uMainZoom;
            uniform float uUltraZoom;
            uniform vec2 uAspectCrop;
            uniform int uMainPixelRotation;
            uniform int uUltraPixelRotation;
            vec2 crop(vec2 p, float z) { return (p - vec2(.5,.5)) * uAspectCrop / z + vec2(.5,.5); }
            vec2 rotateForEncoder(vec2 p, int steps) {
                if (steps == 2) return vec2(1.0-p.x, 1.0-p.y);
                return p;
            }
            void main() {
                vec2 mainUv = (uMainMatrix * vec4(rotateForEncoder(vTexCoord,uMainPixelRotation),0.0,1.0)).xy;
                vec2 ultraUv = (uUltraMatrix * vec4(rotateForEncoder(vTexCoord,uUltraPixelRotation),0.0,1.0)).xy;
                mainUv = crop(mainUv,uMainZoom);
                ultraUv = crop(ultraUv,uUltraZoom);
                if (uBlend <= 0.0001) gl_FragColor = texture2D(uMain,mainUv);
                else if (uBlend >= 0.9999) gl_FragColor = texture2D(uUltra,ultraUv);
                else gl_FragColor = mix(texture2D(uMain,mainUv),texture2D(uUltra,ultraUv),uBlend);
            }
        """
    }
}
