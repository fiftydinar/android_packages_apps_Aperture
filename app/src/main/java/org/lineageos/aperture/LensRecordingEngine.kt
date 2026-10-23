/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
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
import org.lineageos.aperture.MumbaHighSpeedEngine.Listener
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Camera2 dual-lens engine; one compositor and one MediaRecorder serve both rear sensors. */
class LensRecordingEngine(private val context: Context) {
    data class RecordingProfile(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val videoStabilization: Boolean,
        val contentAspectRatio: Float = width.toFloat() / height,
    )

    private data class VideoOutput(val uri: Uri?, val file: File?, val pfd: ParcelFileDescriptor?)
    private data class PhotoOutput(val uri: Uri?, val file: File?)

    private val tag = "LensRecording"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager by lazy { context.getSystemService(CameraManager::class.java) }
    private val rearCameraIds = listOf(CAMERA_MAIN_ID, CAMERA_ULTRA_WIDE_ID)
    private var activeCameraIds = rearCameraIds
    private val cameras = mutableMapOf<String, CameraDevice>()
    private val sessions = mutableMapOf<String, CameraCaptureSession>()
    private val closingSessions = mutableSetOf<CameraCaptureSession>()
    private val sessionsClosedCallbacks = mutableListOf<() -> Unit>()
    private val openingIds = mutableSetOf<String>()
    private val cameraCloseCallbacks = mutableMapOf<CameraDevice, () -> Unit>()
    private val closingCameraIds = mutableSetOf<String>()
    private val pendingOpenIds = mutableListOf<String>()
    private val inputSurfaces = mutableMapOf<String, Surface>()
    private val configuringSessionIds = mutableSetOf<String>()

    private var textureView: TextureView? = null
    private var compositor: LensVideoCompositor? = null
    private var listener: Listener? = null
    var onPhotoCaptured: ((Uri?) -> Unit)? = null
    var onCameraSwitchStateChanged: (() -> Unit)? = null
    private var firstFrameCallback: (() -> Unit)? = null
    private var profile: RecordingProfile? = null
    private var codecMime = "video/avc"
    private var audioEnabled = true
    private var lensCameraId = CAMERA_MAIN_ID
    /** Last rear lens chosen by the user; kept while a front/selfie lens is active. */
    private var rearLensId = CAMERA_MAIN_ID
    private var zoomRatio = 1f
    private var zoomTarget = 1f
    private var zoomAnimating = false
    private var exposureCompensation = 0
    private var torchEnabled = false
    private var focusPoint: Pair<Float, Float>? = null
    private var generation = 0
    private var profileChangeGeneration = 0
    private var cameraSwitchInProgress = false
    private var pendingCameraReadyId: String? = null
    private var openRequested = false
    private var closing = false
    private var pendingAttach: (() -> Unit)? = null
    private var pendingCloseCallback: (() -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var recorderUri: Uri? = null
    private var recorderFile: File? = null
    private var recorderPfd: ParcelFileDescriptor? = null
    private var recording = false
    private var recorderStarted = false
    private var recordingPending = false
    private var recordingPaused = false
    private var pausedAtMs = 0L
    private var startedAt = 0L

    private var photoReader: ImageReader? = null
    private var photoOutput: PhotoOutput? = null
    private var photoCaptureInProgress = false

    val isAttached get() = textureView != null
    val isRecording get() = recording && recorderStarted
    val isRecordingPaused get() = recordingPaused
    val isPhotoCaptureInProgress get() = photoCaptureInProgress
    val currentLensId get() = lensCameraId
    val isUltraWide get() = lensCameraId == CAMERA_ULTRA_WIDE_ID
    val isFrontCamera get() = lensCameraId == CAMERA_FRONT_ID
    val isRearUltraWideSelected get() = rearLensId == CAMERA_ULTRA_WIDE_ID
    /** True only when the ultra-wide camera is open and streaming. */
    val isRearUltraWideReady: Boolean
        get() = cameras[CAMERA_ULTRA_WIDE_ID] != null && sessions[CAMERA_ULTRA_WIDE_ID] != null
    val isCameraSwitching get() = cameraSwitchInProgress
    val canSwitchCameraDuringRecording: Boolean
        get() = isRecording && !cameraSwitchInProgress && activeStreamsReady() &&
                (isFrontCamera || canSwitchToFront(profile))
    val currentZoomRatio get() = zoomRatio
    val minimumZoomRatio: Float get() = MIN_ZOOM_RATIO
    val maximumZoomRatio: Float
        get() = characteristics(CAMERA_MAIN_ID)
            ?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
            ?.coerceAtLeast(1f) ?: 1f
    val hasFlashUnit get() = !isUltraWide && characteristics(lensCameraId)
        ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val isTorchEnabled get() = torchEnabled

    private val zoomAnimator = object : Runnable {
        override fun run() {
            if (!stepZoomAnimation()) return
            mainHandler.postDelayed(this, ZOOM_STEP_INTERVAL_MS)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            listener?.onRecordingDuration((System.currentTimeMillis() - startedAt) * 1_000_000)
            mainHandler.postDelayed(this, DURATION_UPDATE_INTERVAL_MS)
        }
    }

    fun attach(
        textureView: TextureView,
        initialLensId: String,
        codecMime: String,
        audioEnabled: Boolean,
        profile: RecordingProfile,
        listener: Listener,
        onFirstFrame: () -> Unit,
    ) {
        val start = {
            attachNow(textureView, initialLensId, codecMime, audioEnabled, profile, listener, onFirstFrame)
        }
        if (closing) {
            pendingAttach = start
        } else if (isAttached || cameras.isNotEmpty() || openingIds.isNotEmpty()) {
            release(start)
        } else {
            start()
        }
    }

    private fun attachNow(
        view: TextureView,
        initialLensId: String,
        codecMime: String,
        audioEnabled: Boolean,
        profile: RecordingProfile,
        listener: Listener,
        onFirstFrame: () -> Unit,
    ) {
        generation++
        textureView = view
        lensCameraId = initialLensId.takeIf(::isSupportedLensId) ?: CAMERA_MAIN_ID
        if (lensCameraId != CAMERA_FRONT_ID) rearLensId = lensCameraId
        activeCameraIds = rearCameraIds
        cameraSwitchInProgress = false
        pendingCameraReadyId = null
        this.codecMime = codecMime
        this.audioEnabled = audioEnabled
        this.profile = profile
        this.listener = listener
        firstFrameCallback = onFirstFrame
        zoomRatio = 1f
        zoomTarget = 1f
        zoomAnimating = false
        mainHandler.removeCallbacks(zoomAnimator)
        exposureCompensation = 0
        torchEnabled = false
        focusPoint = null
        openRequested = false
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                initializeCompositor()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                compositor?.updatePreviewTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                if (isAttached) mainHandler.post { release() }
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (view.isAvailable) initializeCompositor()
    }

    private fun initializeCompositor() {
        if (compositor != null || !isAttached) return
        val activeProfile = profile ?: return
        val gen = generation
        val view = textureView ?: return
        val gl = LensVideoCompositor(
            textureView = view,
            initialWidth = activeProfile.width,
            initialHeight = activeProfile.height,
            initialFps = activeProfile.fps,
            initialAspectRatio = activeProfile.contentAspectRatio,
            onActiveFrame = { firstFrameCallback?.invoke() },
            onError = { listener?.onError("video compositor failed: ${it.message}") },
        )
        compositor = gl
        gl.initialize { surfaces ->
            if (gen != generation || !isAttached || compositor !== gl) {
                if (compositor === gl) compositor = null
                gl.release()
                return@initialize
            }
            if (!view.isAvailable && textureView === view && compositor === gl) {
                // The preview surface went away between the calls that start initialization.
                compositor = null
                gl.release()
                return@initialize
            }
            inputSurfaces.clear()
            inputSurfaces.putAll(surfaces)
            // A new compositor always starts on the main lens; adopt the selected rear lens.
            gl.setActiveLens(lensCameraId)
            openRequested = true
            activeCameraIds.forEach { openCamera(it) }
        }
    }

    fun canSwitchToFront(recordingProfile: RecordingProfile? = profile): Boolean {
        val candidate = recordingProfile ?: return false
        if (candidate.fps > MAX_FRONT_CAMERA_FPS) return false
        val characteristics = characteristics(CAMERA_FRONT_ID) ?: return false
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        val sizeSupported = streamMap.getOutputSizes(SurfaceTexture::class.java).any {
            it.width == candidate.width && it.height == candidate.height
        }
        val fpsSupported = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.any { candidate.fps in it.lower..it.upper } == true
        val concurrencySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                runCatching {
                    cameraManager.concurrentCameraIds.any { ids ->
                        CAMERA_MAIN_ID in ids && CAMERA_FRONT_ID in ids
                    }
                }.getOrDefault(false)
        return sizeSupported && fpsSupported && concurrencySupported
    }

    fun switchCameraDuringRecording(): Boolean {
        if (!canSwitchCameraDuringRecording) return false
        if (isFrontCamera) switchToRearCamera() else switchToFrontCamera()
        return true
    }

    /** Returns to the rear lens even while idle; used when the UI camera state is stale. */
    fun switchToRearLens(preferUltraWide: Boolean = rearLensId == CAMERA_ULTRA_WIDE_ID): Boolean {
        if (!isFrontCamera && !cameraSwitchInProgress) return false
        if (isRecording) {
            switchToRearCamera()
            return true
        }
        cameraSwitchInProgress = false
        pendingCameraReadyId = null
        val rearLens = if (preferUltraWide) CAMERA_ULTRA_WIDE_ID else CAMERA_MAIN_ID
        rearLensId = rearLens
        lensCameraId = rearLens
        val view = textureView ?: return false
        val activeProfile = profile ?: return false
        release {
            attachNow(
                view,
                rearLens,
                codecMime,
                audioEnabled,
                activeProfile,
                listener ?: return@release,
                firstFrameCallback ?: return@release,
            )
        }
        return true
    }

    /**
     * Keeps the session alive with the rear main lens when the ultra-wide sensor fails, so the
     * preview does not stay frozen on the last ultra-wide frame.
     */
    private fun fallbackToMainLens(gen: Int) {
        if (gen != generation) return
        closeSession(CAMERA_ULTRA_WIDE_ID)
        releaseDevice(CAMERA_ULTRA_WIDE_ID)
        activeCameraIds = if (isRecording) listOf(CAMERA_MAIN_ID) else listOf(CAMERA_MAIN_ID)
        cameraSwitchInProgress = false
        pendingCameraReadyId = null
        notifyCameraSwitchStateChanged()
    }

    private fun activeStreamsReady(): Boolean =
        activeCameraIds.all { cameras[it] != null && sessions[it] != null }

    private fun startCameraSwitchWatchdog(gen: Int) {
        mainHandler.postDelayed({
            if (gen == generation && cameraSwitchInProgress) {
                Log.w(tag, "camera switch timed out; restoring controls")
                cameraSwitchInProgress = false
                pendingCameraReadyId = null
                notifyCameraSwitchStateChanged()
            }
        }, CAMERA_SWITCH_TIMEOUT_MS)
    }

    private fun switchToFrontCamera() {
        val gen = generation
        cameraSwitchInProgress = true
        startCameraSwitchWatchdog(gen)
        pendingCameraReadyId = null
        activeCameraIds = listOf(CAMERA_MAIN_ID, CAMERA_FRONT_ID)
        lensCameraId = CAMERA_FRONT_ID
        torchEnabled = false
        focusPoint = null
        compositor?.resetInputFrame(CAMERA_FRONT_ID)
        notifyCameraSwitchStateChanged()
        updateRepeatingRequests(CAMERA_MAIN_ID)
        closeSession(CAMERA_ULTRA_WIDE_ID) {
            closeCamera(CAMERA_ULTRA_WIDE_ID) {
                if (gen != generation || !isRecording) {
                    cameraSwitchInProgress = false
                    notifyCameraSwitchStateChanged()
                    return@closeCamera
                }
                compositor?.switchTo(
                    CAMERA_FRONT_ID,
                    zoomRatio,
                    waitForFrame = true,
                ) {
                    if (gen == generation) {
                        cameraSwitchInProgress = false
                        notifyCameraSwitchStateChanged()
                    }
                }
                openCamera(CAMERA_FRONT_ID)
            }
        }
    }

    private fun switchToRearCamera() {
        val gen = generation
        cameraSwitchInProgress = true
        startCameraSwitchWatchdog(gen)
        activeCameraIds = rearCameraIds
        lensCameraId = CAMERA_MAIN_ID
        torchEnabled = false
        focusPoint = null
        pendingCameraReadyId = null
        notifyCameraSwitchStateChanged()
        compositor?.switchTo(CAMERA_MAIN_ID, zoomRatio) {
            if (gen != generation || !isRecording) {
                cameraSwitchInProgress = false
                notifyCameraSwitchStateChanged()
                return@switchTo
            }
            closeSession(CAMERA_FRONT_ID) {
                if (gen != generation || !isRecording) {
                    cameraSwitchInProgress = false
                    notifyCameraSwitchStateChanged()
                    return@closeSession
                }
                // Re-establish the rear main lens before releasing the front camera, so the
                // ultra-wide sensor does not have to open while another device is closing.
                createSession(CAMERA_MAIN_ID, gen)
                closeCamera(CAMERA_FRONT_ID) {
                    if (gen != generation || !isRecording) {
                        cameraSwitchInProgress = false
                        notifyCameraSwitchStateChanged()
                        return@closeCamera
                    }
                    compositor?.resetInputFrame(CAMERA_ULTRA_WIDE_ID)
                    pendingCameraReadyId = CAMERA_ULTRA_WIDE_ID
                    openCamera(CAMERA_ULTRA_WIDE_ID)
                }
            }
        }
        updateRepeatingRequests(CAMERA_MAIN_ID)
    }

    private fun notifyCameraSwitchStateChanged() {
        mainHandler.post { onCameraSwitchStateChanged?.invoke() }
    }

    /**
     * Unblocks the camera-switch UI. The GL transition callback is not guaranteed to run
     * (for example when recording stops mid-switch), so every terminal path clears the state.
     */
    private fun resetCameraSwitchState() {
        if (!cameraSwitchInProgress && pendingCameraReadyId == null) return
        cameraSwitchInProgress = false
        pendingCameraReadyId = null
        Log.d(tag, "camera switch state reset")
        notifyCameraSwitchStateChanged()
    }

    /** Starts a crossfade/zoom blend in both the preview and the active video output. */
    /**
     * Switches the rear lens. Returns false when the target camera is not ready yet, so the UI
     * does not show the new lens as active before the viewfinder actually changes.
     */
    fun switchLens(cameraId: String): Boolean {
        if (cameraId !in rearCameraIds || cameraSwitchInProgress ||
            cameraId == lensCameraId || !isAttached || cameras[cameraId] == null
        ) return false
        val previousLens = lensCameraId
        lensCameraId = cameraId
        if (cameraId != CAMERA_FRONT_ID) rearLensId = cameraId
        torchEnabled = false
        focusPoint = null
        val gl = compositor
        if (gl == null) {
            lensCameraId = previousLens
            return false
        }
        gl.switchTo(cameraId, zoomRatio)
        updateRepeatingRequests()
        return true
    }

    fun setRecordingProfile(newProfile: RecordingProfile) {
        if (newProfile == profile || recording) return
        profile = newProfile
        val gen = generation
        val profileGen = ++profileChangeGeneration
        reconfigureAllSessions(gen, profileGen, newProfile)
    }

    /**
     * Continuous zoom across both rear sensors. Below 1x the ultra-wide camera provides the
     * frame; at 1x and above the rear main camera does. Front-camera sessions keep 1x.
     */
    fun setZoomRatio(value: Float) {
        if (isFrontCamera) return
        val max = maximumZoomRatio.coerceAtLeast(1f)
        val requested = value.coerceIn(MIN_ZOOM_RATIO, max)
        val target = if (requested < 1f) CAMERA_ULTRA_WIDE_ID else CAMERA_MAIN_ID
        val lensChanged = target != lensCameraId
        val previousLens = lensCameraId
        if (lensChanged) lensCameraId = target
        if (lensChanged && cameras[target] == null) {
            // The lens is not streaming yet, so the viewfinder cannot switch to it.
            lensCameraId = previousLens
            return
        }
        zoomRatio = requested
        rearLensId = target
        compositor?.setZoomRatio(zoomRatio)
        if (lensChanged) {
            compositor?.switchTo(target, zoomRatio)
            notifyCameraSwitchStateChanged()
        }
        updateRepeatingRequests()
    }

    /** Widest ultra-wide zoom, i.e. the native ultra-wide sensor frame. */
    fun zoomToWidest() {
        animateZoomTo(MIN_ZOOM_RATIO)
    }

    /** Animated zoom, so pinch and slider changes read as a continuous zoom. */
    fun animateZoomTo(value: Float) {
        if (isFrontCamera) return
        val max = maximumZoomRatio.coerceAtLeast(1f)
        zoomTarget = value.coerceIn(MIN_ZOOM_RATIO, max)
        if (zoomAnimating) return
        zoomAnimating = true
        mainHandler.post(zoomAnimator)
    }

    private fun stepZoomAnimation(): Boolean {
        if (!zoomAnimating) return false
        val max = maximumZoomRatio.coerceAtLeast(1f)
        val target = zoomTarget.coerceIn(MIN_ZOOM_RATIO, max)
        val remaining = target - zoomRatio
        if (abs(remaining) <= ZOOM_STEP_EPSILON) {
            setZoomRatio(target)
            zoomAnimating = false
            return false
        }
        // Exponential approach: fast at first, settling smoothly on the target.
        val step = remaining * ZOOM_STEP_FACTOR
        val next = (zoomRatio + step).coerceIn(MIN_ZOOM_RATIO, max)
        setZoomRatio(if (remaining > 0f) next.coerceAtMost(target) else next.coerceAtLeast(target))
        return true
    }

    fun setExposureCompensationIndex(value: Int) {
        exposureCompensation = value
        updateRepeatingRequests()
    }

    fun toggleTorch(): Boolean {
        if (!hasFlashUnit) return false
        torchEnabled = !torchEnabled
        updateRepeatingRequests(CAMERA_MAIN_ID)
        return torchEnabled
    }

    fun focusAt(normalizedX: Float, normalizedY: Float) {
        val id = lensCameraId
        val device = cameras[id] ?: return
        val session = sessions[id] ?: return
        focusPoint = normalizedX.coerceIn(0f, 1f) to normalizedY.coerceIn(0f, 1f)
        try {
            session.capture(
                createCaptureRequest(device, id, CaptureRequest.CONTROL_AF_TRIGGER_START).build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult,
                    ) = updateRepeatingRequests(id)
                },
                mainHandler,
            )
        } catch (t: Throwable) {
            Log.w(tag, "tap focus failed", t)
        }
    }

    fun startRecording(recordingProfile: RecordingProfile) {
        if (recording || !activeStreamsReady()) {
            if (!recording) {
                listener?.onError(
                    if (cameraSwitchInProgress) "camera switch is still in progress"
                    else "camera streams are not ready",
                )
            }
            return
        }
        profile = recordingProfile
        val output = createVideoOutput() ?: run {
            listener?.onError("cannot create video output")
            return
        }
        val mediaRecorder = try {
            MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                if (audioEnabled) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(if (codecMime == "video/hevc") MediaRecorder.VideoEncoder.HEVC
                    else MediaRecorder.VideoEncoder.H264)
                setVideoSize(recordingProfile.width, recordingProfile.height)
                setVideoFrameRate(recordingProfile.fps)
                setVideoEncodingBitRate(recordingProfile.bitrate)
                if (audioEnabled) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                    setAudioChannels(AUDIO_CHANNEL_COUNT)
                    setAudioEncodingBitRate(AUDIO_BITRATE)
                }
                // All sources are normalized to the rear-main sensor orientation in the compositor.
                setOrientationHint(relativeRotation(CAMERA_MAIN_ID))
                if (output.pfd != null) setOutputFile(output.pfd.fileDescriptor)
                else setOutputFile(output.file!!.absolutePath)
                prepare()
            }
        } catch (t: Throwable) {
            Log.w(tag, "recorder prepare failed", t)
            runCatching { output.pfd?.close() }
            discardVideoOutput(output)
            listener?.onError("recorder prepare failed: ${t.message}")
            return
        }

        recorder = mediaRecorder
        recorderUri = output.uri
        recorderFile = output.file
        recorderPfd = output.pfd
        recording = true
        recordingPending = true
        recorderStarted = false
        recordingPaused = false
        pausedAtMs = 0L
        try {
            mediaRecorder.start()
        } catch (t: Throwable) {
            Log.w(tag, "recorder start failed", t)
            finishRecorder(notify = false)
            discardVideoOutput(output)
            listener?.onError("recorder start failed: ${t.message}")
            return
        }
        compositor?.attachEncoderSurface(mediaRecorder.surface) { error ->
            if (error != null) {
                finishRecorder(notify = false)
                discardVideoOutput(output)
                listener?.onError("encoder surface failed: ${error.message}")
                return@attachEncoderSurface
            }
            if (!recordingPending) return@attachEncoderSurface
            recordingPending = false
            recorderStarted = true
            startedAt = System.currentTimeMillis()
            listener?.onRecordingStarted(recorderUri)
            mainHandler.post(ticker)
        }
    }

    fun stopRecording() {
        if (!recording) return
        recordingPending = false
        if (recordingPaused) {
            // Some recorder implementations fail to finalize while paused.
            runCatching { recorder?.resume() }
            recordingPaused = false
            pausedAtMs = 0L
        }
        compositor?.detachEncoderSurface { finishRecorder(notify = true) }
            ?: finishRecorder(notify = true)
    }

    fun pauseRecording(): Boolean {
        if (!isRecording || recordingPaused || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val activeRecorder = recorder ?: return false
        return try {
            activeRecorder.pause()
            recordingPaused = true
            pausedAtMs = System.currentTimeMillis()
            mainHandler.removeCallbacks(ticker)
            true
        } catch (t: Throwable) {
            Log.w(tag, "recorder pause failed", t)
            listener?.onError("recorder pause failed: ${t.message}")
            false
        }
    }

    fun resumeRecording(): Boolean {
        if (!isRecording || !recordingPaused || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val activeRecorder = recorder ?: return false
        return try {
            activeRecorder.resume()
            startedAt += (System.currentTimeMillis() - pausedAtMs).coerceAtLeast(0L)
            pausedAtMs = 0L
            recordingPaused = false
            mainHandler.post(ticker)
            true
        } catch (t: Throwable) {
            Log.w(tag, "recorder resume failed", t)
            false
        }
    }

    /** Captures a JPEG from the currently selected physical lens. */
    fun capturePhoto(targetAspectRatio: Float, highResolution: Boolean) {
        if (photoCaptureInProgress || recording) return
        val id = lensCameraId
        val device = cameras[id] ?: return photoError("selected camera is not ready")
        val size = selectJpegSize(id, targetAspectRatio, highResolution)
            ?: return photoError("JPEG output size unavailable")
        val output = createPhotoOutput() ?: return photoError("cannot create photo output")
        val reader = try {
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        } catch (t: Throwable) {
            discardPhotoOutput(output)
            return photoError("cannot create JPEG reader: ${t.message}")
        }
        photoCaptureInProgress = true
        photoReader = reader
        photoOutput = output
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            val bytes = try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also(buffer::get)
            } finally {
                image.close()
            }
            val uri = writePhoto(output, bytes)
            finishPhotoCapture(id, uri)
        }, mainHandler)

        val previewInput = inputSurfaces[id] ?: return finishPhotoCapture(id, null)
        val gen = generation
        closeSessions {
            if (gen != generation || !photoCaptureInProgress) return@closeSessions
            if (!configuringSessionIds.add(id)) {
                photoError("still session is already being configured")
                finishPhotoCapture(id, null)
                return@closeSessions
            }
            try {
                device.createCaptureSession(
                    listOf(previewInput, reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(stillSession: CameraCaptureSession) {
                            configuringSessionIds.remove(id)
                            if (gen != generation || !photoCaptureInProgress) {
                                closeSessionObject(stillSession)
                                drainSessionCallbacks()
                                return
                            }
                            sessions[id] = stillSession
                            try {
                                stillSession.setRepeatingRequest(
                                    createCaptureRequest(device, id, CaptureRequest.CONTROL_AF_TRIGGER_IDLE).build(),
                                    null,
                                    mainHandler,
                                )
                                val still = createStillCaptureRequest(device, id, reader.surface)
                                mainHandler.postDelayed({
                                    if (gen == generation && photoCaptureInProgress) {
                                        stillSession.capture(still, object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: CaptureFailure,
                                            ) {
                                                photoError("still capture failed: ${failure.reason}")
                                                finishPhotoCapture(id, null)
                                            }
                                        }, mainHandler)
                                    }
                                }, PHOTO_CAPTURE_SETTLE_MS)
                            } catch (t: Throwable) {
                                photoError("still capture failed: ${t.message}")
                                finishPhotoCapture(id, null)
                            }
                            drainSessionCallbacks()
                        }

                        override fun onConfigureFailed(stillSession: CameraCaptureSession) {
                            configuringSessionIds.remove(id)
                            closeSessionObject(stillSession)
                            photoError("still session configuration failed")
                            finishPhotoCapture(id, null)
                            drainSessionCallbacks()
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            onSessionClosed(session)
                        }
                    },
                    mainHandler,
                )
            } catch (t: Throwable) {
                configuringSessionIds.remove(id)
                photoError("still session failed: ${t.message}")
                finishPhotoCapture(id, null)
                drainSessionCallbacks()
            }
        }
        mainHandler.postDelayed({
            if (gen == generation && photoCaptureInProgress) {
                photoError("photo capture timed out")
                finishPhotoCapture(id, null)
            }
        }, PHOTO_CAPTURE_TIMEOUT_MS)
    }

    fun release(onClosed: (() -> Unit)? = null) {
        if (recording) {
            recordingPending = false
            compositor?.detachEncoderSurface {
                finishRecorder(notify = true)
                releaseResources(onClosed)
            } ?: run {
                finishRecorder(notify = true)
                releaseResources(onClosed)
            }
        } else {
            releaseResources(onClosed)
        }
    }

    private fun releaseResources(onClosed: (() -> Unit)? = null) {
        if (closing) {
            if (onClosed != null) {
                val old = pendingCloseCallback
                pendingCloseCallback = if (old == null) onClosed else { { old(); onClosed() } }
            }
            return
        }
        generation++
        closing = true
        openRequested = false
        zoomAnimating = false
        mainHandler.removeCallbacks(zoomAnimator)
        resetCameraSwitchState()
        pendingCloseCallback = onClosed
        mainHandler.removeCallbacks(ticker)
        if (photoCaptureInProgress) {
            photoCaptureInProgress = false
            photoReader?.setOnImageAvailableListener(null, null)
            photoReader?.close()
            photoReader = null
            photoOutput?.let(::discardPhotoOutput)
            photoOutput = null
            onPhotoCaptured?.invoke(null)
        }
        closeSessions()
        val oldCompositor = compositor
        compositor = null
        oldCompositor?.release {
            inputSurfaces.clear()
            maybeCompleteRelease()
        }
        if (oldCompositor == null) inputSurfaces.clear()
        textureView?.surfaceTextureListener = null
        textureView = null
        cameras.keys.toList().forEach { releaseDevice(it) }
        maybeCompleteRelease()
    }

    private fun openCamera(id: String, retry: Int = 0) {
        if (!openRequested || !isAttached || id !in activeCameraIds || id in cameras || id in openingIds) return
        // The HAL here refuses to open the ultra-wide sensor while another camera device is
        // still closing, which shows up as ERROR_CAMERA_DEVICE and freezes the preview.
        if (closingCameraIds.any { it != id }) {
            if (id !in pendingOpenIds) pendingOpenIds.add(id)
            return
        }
        val gen = generation
        openingIds.add(id)
        var retryAfterClose = false
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    openingIds.remove(id)
                    if (gen != generation || !openRequested || !isAttached) {
                        device.close()
                        maybeCompleteRelease()
                        return
                    }
                    cameras[id] = device
                    createSession(id, gen)
                }

                override fun onDisconnected(device: CameraDevice) {
                    retryAfterClose = true
                    device.close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    // Treat the documented recoverable camera errors as retryable. The device
                    // error here is usually raised while another camera device is still closing.
                    val canRetry = error in RECOVERABLE_CAMERA_ERRORS
                    Log.w(tag, "camera $id error=$error retry=$retry")
                    // Camera2 already closed the device after onError.
                    if (cameras[id] === device) cameras.remove(id)
                    openingIds.remove(id)
                    if (gen != generation || !openRequested) return

                    if (canRetry && closingCameraIds.any { it != id }) {
                        // Another device is still closing; wait for it instead of spending a
                        // retry attempt, otherwise every retry hits the same HAL conflict.
                        if (id !in pendingOpenIds) pendingOpenIds.add(id)
                        return
                    }

                    if (canRetry && retry < MAX_OPEN_RETRIES) {
                        // Give cameraserver and the HAL time to release the previous device;
                        // a fixed short delay keeps failing with ERROR_CAMERA_DEVICE here.
                        val delay = OPEN_RETRY_DELAY_MS * (retry + 1)
                        mainHandler.postDelayed({
                            if (gen == generation && openRequested && id in activeCameraIds) {
                                openCamera(id, retry + 1)
                            }
                        }, delay)
                        return
                    }

                    if (id == CAMERA_ULTRA_WIDE_ID) {
                        // Keep the session usable with the rear main lens instead of leaving
                        // the preview frozen on the last frame.
                        listener?.onError("ultra-wide camera unavailable (error=$error)")
                        fallbackToMainLens(gen)
                        return
                    }

                    listener?.onError("camera $id error=$error")
                }

                override fun onClosed(device: CameraDevice) {
                    if (cameras[id] === device) cameras.remove(id)
                    openingIds.remove(id)
                    closingCameraIds.remove(id)
                    cameraCloseCallbacks.remove(device)?.let { callback ->
                        callback()
                        maybeCompleteRelease()
                        return
                    }
                    if (closing) maybeCompleteRelease()
                    else if (!openRequested) Unit // Released deliberately; do not reconnect.
                    else if (gen != generation) openCamera(id)
                    else if (retryAfterClose && retry < MAX_OPEN_RETRIES) {
                        mainHandler.postDelayed({ if (gen == generation) openCamera(id, retry + 1) }, OPEN_RETRY_DELAY_MS)
                    } else if (retryAfterClose) listener?.onError("camera $id unavailable")
                    runPendingOpens()
                }
            }, mainHandler)
        } catch (t: Throwable) {
            openingIds.remove(id)
            if (retry < MAX_OPEN_RETRIES) {
                mainHandler.postDelayed({ if (gen == generation) openCamera(id, retry + 1) }, OPEN_RETRY_DELAY_MS)
            } else {
                listener?.onError("camera $id unavailable: ${t.message}")
            }
            maybeCompleteRelease()
        }
    }

    private fun createSession(
        id: String,
        gen: Int,
        profileGen: Int = profileChangeGeneration,
    ) {
        if (id !in activeCameraIds) return
        val device = cameras[id]
        if (device == null || !configuringSessionIds.add(id)) {
            drainSessionCallbacks()
            return
        }
        val input = inputSurfaces[id] ?: run {
            configuringSessionIds.remove(id)
            drainSessionCallbacks()
            return
        }
        try {
            device.createCaptureSession(listOf(input), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    configuringSessionIds.remove(id)
                    if (gen != generation || profileGen != profileChangeGeneration ||
                        id !in activeCameraIds || cameras[id] !== device
                    ) {
                        closeSessionObject(session)
                        drainSessionCallbacks()
                        return
                    }
                    sessions[id] = session
                    try {
                        session.setRepeatingRequest(
                            createCaptureRequest(device, id, CaptureRequest.CONTROL_AF_TRIGGER_IDLE).build(),
                            null,
                            mainHandler,
                        )
                    } catch (t: Throwable) {
                        listener?.onError("camera $id request failed: ${t.message}")
                    }
                    if (id == pendingCameraReadyId) {
                        pendingCameraReadyId = null
                        cameraSwitchInProgress = false
                    }
                    // A lens that finished opening can now be selected in the UI.
                    notifyCameraSwitchStateChanged()
                    drainSessionCallbacks()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    configuringSessionIds.remove(id)
                    closeSessionObject(session)
                    if (gen == generation && profileGen == profileChangeGeneration) {
                        listener?.onError("camera $id session configuration failed")
                    }
                    if (id == CAMERA_FRONT_ID && lensCameraId == CAMERA_FRONT_ID &&
                        isRecording && profileGen == profileChangeGeneration
                    ) {
                        switchToRearCamera()
                    }
                    drainSessionCallbacks()
                }

                override fun onClosed(session: CameraCaptureSession) {
                    onSessionClosed(session)
                }
            }, mainHandler)
        } catch (t: Throwable) {
            configuringSessionIds.remove(id)
            if (gen == generation && profileGen == profileChangeGeneration) {
                listener?.onError("camera $id session failed: ${t.message}")
            }
            drainSessionCallbacks()
        }
    }

    private fun reconfigureAllSessions(gen: Int, profileGen: Int, newProfile: RecordingProfile) {
        closeSessions {
            if (gen == generation && profileGen == profileChangeGeneration && openRequested) {
                val gl = compositor ?: return@closeSessions
                gl.updateProfile(
                    newProfile.width,
                    newProfile.height,
                    newProfile.fps,
                    newProfile.contentAspectRatio,
                ) { error ->
                    if (gen != generation || profileGen != profileChangeGeneration || !isAttached) {
                        return@updateProfile
                    }
                    if (error != null) {
                        listener?.onError("preview profile update failed: ${error.message}")
                    } else {
                        activeCameraIds.forEach { id -> createSession(id, gen, profileGen) }
                    }
                }
            }
        }
    }

    private fun createCaptureRequest(device: CameraDevice, id: String, afTrigger: Int): CaptureRequest.Builder {
        val request = device.createCaptureRequest(if (recording) CameraDevice.TEMPLATE_RECORD
            else CameraDevice.TEMPLATE_PREVIEW)
        inputSurfaces[id]?.let(request::addTarget)
        val chars = characteristics(id)
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        request.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        request.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        request.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        val currentProfile = profile
        val stabAvailable = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (currentProfile?.videoStabilization == true && stabAvailable)
                CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val fps = currentProfile?.fps ?: PREVIEW_FPS
        chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let { ranges ->
            val range = ranges.firstOrNull { it.lower == fps && it.upper == fps }
                ?: ranges.filter { fps in it.lower..it.upper }.minByOrNull { it.upper - it.lower }
            if (range != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }
        chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation.coerceIn(range.lower, range.upper))
        }
        if (id != CAMERA_ULTRA_WIDE_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(range.lower, range.upper))
            }
        }
        if (id == CAMERA_MAIN_ID && chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            request.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }
        focusPoint?.let { (nx, ny) ->
            val active = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxAf = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            val maxAe = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            if (active != null && (maxAf > 0 || maxAe > 0)) {
                val x = (active.left + nx * active.width()).roundToInt()
                val y = (active.top + ny * active.height()).roundToInt()
                val hw = (active.width() * FOCUS_REGION_FRACTION / 2f).roundToInt()
                val hh = (active.height() * FOCUS_REGION_FRACTION / 2f).roundToInt()
                val region = MeteringRectangle(Rect(
                    (x-hw).coerceAtLeast(active.left), (y-hh).coerceAtLeast(active.top),
                    (x+hw).coerceAtMost(active.right), (y+hh).coerceAtMost(active.bottom),
                ), MeteringRectangle.METERING_WEIGHT_MAX)
                if (maxAf > 0) request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                if (maxAe > 0) request.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
        }
        return request
    }

    private fun createStillCaptureRequest(device: CameraDevice, id: String, output: Surface): CaptureRequest {
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(output)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.JPEG_ORIENTATION, relativeRotation(id))
            set(CaptureRequest.JPEG_QUALITY, 100.toByte())
        }
        val chars = characteristics(id)
        chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
            request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation.coerceIn(range.lower, range.upper))
        }
        if (id != CAMERA_ULTRA_WIDE_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->
                request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(range.lower, range.upper))
            }
        }
        if (id == CAMERA_MAIN_ID && chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            request.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }
        return request.build()
    }

    private fun selectJpegSize(id: String, ratio: Float, highResolution: Boolean): Size? {
        val sizes = characteristics(id)?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return null
        val matching = sizes.filter { abs(it.width.toFloat() / it.height - ratio) < 0.04f }.ifEmpty { sizes }
        val available = if (highResolution) matching else matching.filter {
            it.width.toLong() * it.height <= STANDARD_PHOTO_PIXEL_LIMIT
        }.ifEmpty { matching }
        return available.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun createPhotoOutput(): PhotoOutput? {
        val name = SimpleDateFormat("'Aperture_'yyyyMMdd_HHmmss'.jpg'", Locale.US).format(Date())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?.let { return PhotoOutput(it, null) }
        }
        val file = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), name)
            .apply { parentFile?.mkdirs() }
        return PhotoOutput(null, file)
    }

    private fun writePhoto(output: PhotoOutput, bytes: ByteArray): Uri? = try {
        if (output.uri != null) {
            context.contentResolver.openOutputStream(output.uri, "w")?.use { it.write(bytes) }
                ?: error("could not open image output")
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(output.uri, values, null, null)
            output.uri
        } else {
            FileOutputStream(output.file!!).use { it.write(bytes) }
            Uri.fromFile(output.file)
        }
    } catch (t: Throwable) {
        Log.w(tag, "JPEG save failed", t)
        discardPhotoOutput(output)
        null
    }

    private fun finishPhotoCapture(id: String, uri: Uri?) {
        if (!photoCaptureInProgress) return
        photoCaptureInProgress = false
        if (uri == null) photoOutput?.let(::discardPhotoOutput)
        photoOutput = null
        runCatching { photoReader?.setOnImageAvailableListener(null, null) }
        runCatching { photoReader?.close() }
        photoReader = null
        val gen = generation
        closeSession(id) {
            if (gen == generation && openRequested) activeCameraIds.forEach { camId ->
                if (cameras[camId] != null) createSession(camId, gen)
            }
        }
        onPhotoCaptured?.invoke(uri)
    }

    private fun photoError(message: String) {
        Log.w(tag, message)
        listener?.onError(message)
    }

    private fun closeSession(id: String, afterClosed: (() -> Unit)? = null) {
        sessions.remove(id)?.let(::closeSessionObject)
        afterClosed?.let(::runAfterSessionsIdle)
    }

    private fun closeCamera(id: String, afterClosed: () -> Unit) {
        val device = cameras.remove(id) ?: run {
            afterClosed()
            return
        }
        closingCameraIds.add(id)
        cameraCloseCallbacks[device] = afterClosed
        runCatching { device.close() }.onFailure {
            cameraCloseCallbacks.remove(device)
            closingCameraIds.remove(id)
            afterClosed()
        }
    }

    private fun releaseDevice(id: String) {
        val device = cameras.remove(id) ?: return
        closingCameraIds.add(id)
        runCatching { device.close() }.onFailure { closingCameraIds.remove(id) }
    }

    private fun runPendingOpens() {
        if (closingCameraIds.isNotEmpty() || pendingOpenIds.isEmpty()) return
        val ids = pendingOpenIds.toList()
        pendingOpenIds.clear()
        ids.forEach { id -> openCamera(id) }
    }

    private fun closeSessionObject(session: CameraCaptureSession) {
        if (!closingSessions.add(session)) return
        runCatching { session.stopRepeating() }
        runCatching { session.abortCaptures() }
        runCatching { session.close() }
            .onFailure { onSessionClosed(session) }
    }

    private fun onSessionClosed(session: CameraCaptureSession) {
        if (!closingSessions.remove(session)) return
        drainSessionCallbacks()
    }

    private fun drainSessionCallbacks() {
        if (closingSessions.isNotEmpty() || configuringSessionIds.isNotEmpty()) return
        val callbacks = sessionsClosedCallbacks.toList()
        sessionsClosedCallbacks.clear()
        callbacks.forEach { callback -> runCatching(callback) }
        maybeCompleteRelease()
    }

    private fun runAfterSessionsIdle(callback: () -> Unit) {
        if (closingSessions.isEmpty() && configuringSessionIds.isEmpty()) {
            callback()
        } else {
            sessionsClosedCallbacks.add(callback)
        }
    }

    private fun discardPhotoOutput(output: PhotoOutput) {
        output.uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        output.file?.delete()
    }

    private fun discardVideoOutput(output: VideoOutput) {
        runCatching { output.pfd?.close() }
        output.uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        output.file?.delete()
    }

    private fun updateRepeatingRequests(cameraId: String? = null) {
        val ids = cameraId?.let(::listOf) ?: activeCameraIds
        ids.forEach { id ->
            val device = cameras[id] ?: return@forEach
            val session = sessions[id] ?: return@forEach
            runCatching {
                session.setRepeatingRequest(
                    createCaptureRequest(device, id, CaptureRequest.CONTROL_AF_TRIGGER_IDLE).build(),
                    null,
                    mainHandler,
                )
            }.onFailure { Log.w(tag, "request update failed id=$id", it) }
        }
    }

    private fun closeSessions(afterClosed: (() -> Unit)? = null) {
        sessions.keys.toList().forEach { id -> closeSession(id) }
        afterClosed?.let(::runAfterSessionsIdle)
    }

    private fun finishRecorder(notify: Boolean) {
        val hadRecorder = recorder != null
        if (recordingPaused) runCatching { recorder?.resume() }
        recordingPaused = false
        pausedAtMs = 0L
        recording = false
        resetCameraSwitchState()
        recordingPending = false
        recorderStarted = false
        mainHandler.removeCallbacks(ticker)
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            Log.w(tag, "recorder stop failed", t)
        }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recorderPfd?.close() }
        recorderPfd = null
        val uri = recorderUri
        recorderUri = null
        recorderFile = null
        if (uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
        if (notify && hadRecorder) {
            listener?.onRecordingDuration(0)
            listener?.onRecordingStopped(uri)
        }
    }

    private fun maybeCompleteRelease() {
        if (!closing || cameras.isNotEmpty() || openingIds.isNotEmpty() ||
            closingCameraIds.isNotEmpty() || closingSessions.isNotEmpty() ||
            configuringSessionIds.isNotEmpty() || compositor != null
        ) return
        closing = false
        val callback = pendingCloseCallback
        pendingCloseCallback = null
        callback?.invoke()
        val attach = pendingAttach
        pendingAttach = null
        attach?.invoke()
    }

    private fun createVideoOutput(): VideoOutput? {
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
                if (pfd != null) return VideoOutput(uri, null, pfd)
                context.contentResolver.delete(uri, null, null)
            }
        }
        val file = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), name)
            .apply { parentFile?.mkdirs() }
        return VideoOutput(null, file, null)
    }

    private fun characteristics(id: String): CameraCharacteristics? = runCatching {
        cameraManager.getCameraCharacteristics(id)
    }.getOrNull()

    private fun relativeRotation(id: String): Int {
        val sensor = characteristics(id)?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayDegrees = when (textureView?.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = characteristics(id)?.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + displayDegrees) % 360
        } else {
            (sensor - displayDegrees + 360) % 360
        }
    }

    private fun isSupportedLensId(id: String) =
        id == CAMERA_MAIN_ID || id == CAMERA_ULTRA_WIDE_ID || id == CAMERA_FRONT_ID

    companion object {
        const val CAMERA_MAIN_ID = "0"
        const val CAMERA_ULTRA_WIDE_ID = "2"
        const val CAMERA_FRONT_ID = "1"

        private const val PREVIEW_FPS = 30
        private const val FOCUS_REGION_FRACTION = 0.10f
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val AUDIO_BITRATE = 256_000
        private const val DURATION_UPDATE_INTERVAL_MS = 500L
        private const val OPEN_RETRY_DELAY_MS = 300L
        private const val MAX_OPEN_RETRIES = 6
        private const val PHOTO_CAPTURE_SETTLE_MS = 100L
        private const val PHOTO_CAPTURE_TIMEOUT_MS = 5000L
        private const val MAX_FRONT_CAMERA_FPS = 30
        private const val CAMERA_SWITCH_TIMEOUT_MS = 5000L
        /**
         * Widest rear coverage, derived from the sensor focal lengths on this device:
         * ultra-wide 1.64mm / rear main 4.81mm ~= 0.34 (advertised as 0.3x).
         */
        private const val MIN_ZOOM_RATIO = 0.3f
        private const val ZOOM_STEP_FACTOR = 0.28f
        private const val ZOOM_STEP_EPSILON = 0.004f
        private const val ZOOM_STEP_INTERVAL_MS = 16L
        private val RECOVERABLE_CAMERA_ERRORS = setOf(
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE,
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE,
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED,
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE,
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE,
        )
        private const val ASPECT_RATIO_TOLERANCE = 0.04f
        private const val STANDARD_PHOTO_PIXEL_LIMIT = 2_073_600L

        fun isUltraWideAvailable(context: Context): Boolean = runCatching {
            context.getSystemService(CameraManager::class.java)
                .getCameraCharacteristics(CAMERA_ULTRA_WIDE_ID)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
    }
}
