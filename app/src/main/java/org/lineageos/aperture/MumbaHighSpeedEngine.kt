/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MumbaHighSpeedEngine(private val context: Context) {
    interface Listener {
        fun onRecordingStarted(uri: Uri?)
        fun onRecordingStopped(uri: Uri?)
        fun onRecordingDuration(durationNanos: Long)
        fun onError(message: String)
    }

    private val tag = "MumbaHighSpeed"
    private val handler = Handler(Looper.getMainLooper())
    private val cameraManager by lazy {
        context.getSystemService(CameraManager::class.java)
    }
    private var textureView: TextureView? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recorderUri: Uri? = null
    private var recorderPfd: ParcelFileDescriptor? = null
    private var cameraId = "0"
    private var codecMime = "video/avc"
    private var audioEnabled = true
    private var listener: Listener? = null
    private var recording = false
    private var startedAt = 0L
    private var generation = 0
    private var warmUpNeeded = false
    private var warmingUp = false
    private var warmRecorder: MediaRecorder? = null
    private var warmFile: File? = null
    private var warmSession: CameraCaptureSession? = null
    private var warmStableFrames = 0
    private val warmStableFramesNeeded = 12
    private val warmMaxDurationMs = 1500L
    private val warmFinish = Runnable { finishWarmUp() }

    private val warmProbe = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (!warmingUp) return
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            val awb = result.get(CaptureResult.CONTROL_AWB_STATE)
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            val converged = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED &&
                (af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
            warmStableFrames = if (converged) warmStableFrames + 1 else 0
            if (warmStableFrames == warmStableFramesNeeded) handler.post(warmFinish)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            listener?.onRecordingDuration((System.currentTimeMillis() - startedAt) * 1_000_000)
            handler.postDelayed(this, 500)
        }
    }

    val isRecording: Boolean get() = recording

    fun attach(textureView: TextureView, cameraId: String, codecMime: String, audioEnabled: Boolean, listener: Listener) {
        release()
        this.textureView = textureView
        this.cameraId = cameraId
        this.codecMime = codecMime
        this.audioEnabled = audioEnabled
        this.listener = listener
        warmUpNeeded = true
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(1280, 720)
                previewSurface = Surface(st)
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        if (textureView.isAvailable) {
            ensurePreviewSurface()
            openCamera()
        }
    }

    private fun openCamera(retry: Int = 0) {
        if (camera != null) return
        val gen = ++generation
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (gen != generation) {
                        device.close()
                        return
                    }
                    camera = device
                    startPreview()
                }

                override fun onDisconnected(device: CameraDevice) {
                    if (gen != generation) return
                    camera = null
                    if (retry < 6) {
                        handler.postDelayed({ openCamera(retry + 1) }, 300)
                    }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    if (gen != generation) return
                    camera = null
                    if (
                        error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                        error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE
                    ) {
                        if (retry < 6) {
                            handler.postDelayed({ openCamera(retry + 1) }, 300)
                            return
                        }
                    }
                    listener?.onError("camera error=$error")
                }
            }, handler)
        } catch (t: Throwable) {
            if (retry < 6) {
                handler.postDelayed({ openCamera(retry + 1) }, 300)
            } else {
                listener?.onError("open failed: ${t.message}")
            }
        }
    }

    private fun ensurePreviewSurface(): Surface? {
        val texture = textureView?.surfaceTexture ?: return null
        texture.setDefaultBufferSize(1280, 720)
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = Surface(texture)
        return previewSurface
    }

    private fun startPreview() {
        val device = camera ?: return
        if (warmUpNeeded) {
            warmUp()
            return
        }
        val gen = generation
        try { session?.close() } catch (_: Throwable) {}
        val surface = ensurePreviewSurface() ?: return
        try {
            device.createConstrainedHighSpeedCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) return
                        session = s
                        try {
                            val hs = s as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                applyCommonOptions(this)
                                setFpsRange(device, this)
                            }
                            hs.setRepeatingBurst(hs.createHighSpeedRequestList(request.build()), null, handler)
                        } catch (t: Throwable) {
                            if (gen == generation) listener?.onError("preview failed: ${t.message}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == generation) {
                            camera = null
                            handler.postDelayed({ openCamera() }, 300)
                        }
                    }
                }, handler
            )
        } catch (t: Throwable) {
            if (gen == generation) listener?.onError("preview session failed: ${t.message}")
        }
    }

    private fun applyCommonOptions(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
    }

    private fun setFpsRange(device: CameraDevice, builder: CaptureRequest.Builder) {
        val ranges = device.cameraCharacteristics()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getHighSpeedVideoFpsRangesFor(Size(1280, 720))
        ranges?.firstOrNull { it.upper == 120 && it.lower == 120 }
            ?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
    }

    private fun warmUp() {
        val device = camera ?: return
        if (warmingUp || recording) return
        warmingUp = true
        warmUpNeeded = false
        warmStableFrames = 0
        val gen = generation
        val file = File(context.cacheDir, "mumba-warmup.mp4").apply { delete() }
        val rec = try {
            MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(
                    if (codecMime == "video/hevc") MediaRecorder.VideoEncoder.HEVC
                    else MediaRecorder.VideoEncoder.H264
                )
                setVideoSize(1280, 720)
                setVideoFrameRate(120)
                setVideoEncodingBitRate(55_000_000)
                setOrientationHint(90)
                setOutputFile(file.absolutePath)
                prepare()
            }
        } catch (t: Throwable) {
            Log.w(tag, "warmup prepare failed", t)
            file.delete()
            warmingUp = false
            startPreview()
            return
        }
        warmRecorder = rec
        warmFile = file
        val preview = ensurePreviewSurface()
        try {
            device.createConstrainedHighSpeedCaptureSession(
                listOfNotNull(preview, rec.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation || !warmingUp) {
                            try { s.close() } catch (_: Throwable) {}
                            return
                        }
                        warmSession = s
                        try {
                            val hs = s as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(rec.surface)
                                preview?.let { addTarget(it) }
                                applyCommonOptions(this)
                                setFpsRange(device, this)
                            }
                            hs.setRepeatingBurst(hs.createHighSpeedRequestList(request.build()), warmProbe, handler)
                            rec.start()
                            handler.postDelayed(warmFinish, warmMaxDurationMs)
                        } catch (t: Throwable) {
                            Log.w(tag, "warmup start failed", t)
                            finishWarmUp()
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.w(tag, "warmup session failed")
                        finishWarmUp()
                    }
                },
                handler,
            )
        } catch (t: Throwable) {
            Log.w(tag, "warmup session creation failed", t)
            finishWarmUp()
        }
    }

    private fun finishWarmUp() {
        if (!warmingUp) return
        warmingUp = false
        handler.removeCallbacks(warmFinish)
        try { warmSession?.stopRepeating() } catch (_: Throwable) {}
        try { warmRecorder?.stop() } catch (_: Throwable) {}
        try { warmRecorder?.release() } catch (_: Throwable) {}
        warmRecorder = null
        try { warmSession?.close() } catch (_: Throwable) {}
        warmSession = null
        warmFile?.delete()
        warmFile = null
        warmStableFrames = 0
        startPreview()
    }

    fun startRecording() {
        val device = camera ?: return
        if (recording || warmingUp) return
        val output = createOutput() ?: run {
            listener?.onError("cannot create output")
            return
        }
        val rec = try {
            MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                if (audioEnabled) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(
                    if (codecMime == "video/hevc") MediaRecorder.VideoEncoder.HEVC
                    else MediaRecorder.VideoEncoder.H264
                )
                setVideoSize(1280, 720)
                setVideoFrameRate(120)
                setVideoEncodingBitRate(80_000_000)
                if (audioEnabled) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(48_000)
                    setAudioChannels(2)
                    setAudioEncodingBitRate(256_000)
                }
                setOrientationHint(90)
                if (output.pfd != null) setOutputFile(output.pfd.fileDescriptor)
                else setOutputFile(output.file!!.absolutePath)
                prepare()
            }
        } catch (t: Throwable) {
            Log.w(tag, "record prepare failed", t)
            discardOutput(output)
            listener?.onError("recorder prepare failed: ${t.message}")
            return
        }
        recorder = rec
        recorderUri = output.uri
        recorderPfd = output.pfd

        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        session = null
        ensurePreviewSurface()

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                try {
                    val hs = s as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(rec.surface)
                        previewSurface?.let { addTarget(it) }
                        applyCommonOptions(this)
                        setFpsRange(device, this)
                    }
                    hs.setRepeatingBurst(hs.createHighSpeedRequestList(request.build()), null, handler)
                    rec.start()
                    recording = true
                    startedAt = System.currentTimeMillis()
                    listener?.onRecordingStarted(recorderUri)
                    handler.post(ticker)
                } catch (t: Throwable) {
                    Log.w(tag, "record start failed", t)
                    releaseRecorder()
                    discardOutput(output)
                    listener?.onError("record start failed: ${t.message}")
                    startPreview()
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                releaseRecorder()
                discardOutput(output)
                listener?.onError("record configure failed")
                startPreview()
            }
        }

        try {
            val surfaces = listOfNotNull(previewSurface, rec.surface)
            device.createConstrainedHighSpeedCaptureSession(surfaces, callback, handler)
        } catch (t: Throwable) {
            releaseRecorder()
            discardOutput(output)
            listener?.onError("high-speed session failed: ${t.message}")
            startPreview()
        }
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(ticker)
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { recorder?.stop() } catch (t: Throwable) {
            listener?.onError("stop failed: ${t.message}")
        }
        releaseRecorder()
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { recorderPfd?.close() } catch (_: Throwable) {}
        recorderPfd = null
        val uri = recorderUri
        if (uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
        recorderUri = null
        listener?.onRecordingDuration(0)
        listener?.onRecordingStopped(uri)
        handler.postDelayed({ startPreview() }, 300)
    }

    fun release() {
        generation++
        warmUpNeeded = false
        warmingUp = false
        handler.removeCallbacks(warmFinish)
        warmStableFrames = 0
        try { warmSession?.close() } catch (_: Throwable) {}
        warmSession = null
        try { warmRecorder?.release() } catch (_: Throwable) {}
        warmRecorder = null
        warmFile?.delete()
        warmFile = null
        if (recording) {
            recording = false
            handler.removeCallbacks(ticker)
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorderPfd?.close() } catch (_: Throwable) {}
            val uri = recorderUri
            if (uri != null) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            }
            recorderUri = null
            recorderPfd = null
        }
        releaseRecorder()
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { camera?.close() } catch (_: Throwable) {}
        camera = null
        textureView?.surfaceTextureListener = null
        textureView = null
        previewSurface?.release()
        previewSurface = null
    }

    private fun releaseRecorder() {
        try { recorder?.reset() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
    }

    private fun CameraDevice.cameraCharacteristics(): CameraCharacteristics? = try {
        cameraManager.getCameraCharacteristics(id)
    } catch (_: Throwable) {
        null
    }

    private data class OutputInfo(
        val uri: Uri?,
        val file: File?,
        val pfd: ParcelFileDescriptor?,
    )

    private fun createOutput(): OutputInfo? {
        val name = SimpleDateFormat("'Aperture_'yyyyMMdd_HHmmss'.mp4'", Locale.US).format(Date())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                if (pfd != null) return OutputInfo(uri, null, pfd)
                context.contentResolver.delete(uri, null, null)
            }
        }
        val file = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), name)
            .apply { parentFile?.mkdirs() }
        return OutputInfo(null, file, null)
    }

    private fun discardOutput(output: OutputInfo) {
        try { output.pfd?.close() } catch (_: Throwable) {}
        output.uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        output.file?.delete()
    }
}
