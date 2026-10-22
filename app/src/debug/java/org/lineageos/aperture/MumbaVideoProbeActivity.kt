/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MumbaVideoProbeActivity : Activity() {
    private val tag = "MumbaVideoProbe"
    private val output = StringBuilder()
    private lateinit var textView: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    private val width by lazy { intent.getIntExtra("width", 2560) }
    private val height by lazy { intent.getIntExtra("height", 1440) }
    private val fps by lazy { intent.getIntExtra("fps", 30) }
    private val seconds by lazy { intent.getIntExtra("seconds", 5) }
    private val useHevc by lazy { intent.getStringExtra("codec") != "avc" }
    private val useEis by lazy { intent.getBooleanExtra("eis", true) }
    private val hdrMode by lazy { intent.getIntExtra("hdr", 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(textView) })
        append("Mumba video probe ${width}x$height@$fps codec=${if (useHevc) "hevc" else "avc"} eis=$useEis hdr=$hdrMode")
        try {
            start()
        } catch (t: Throwable) {
            append("FATAL: ${t.stackTraceToString()}")
        }
    }

    private fun append(line: String) {
        Log.i(tag, line)
        output.append(line).append('\n')
        textView.text = output
    }

    private fun start() {
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull() ?: return
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        append("JPEG sizes=${map.getOutputSizes(ImageFormat.JPEG)?.joinToString()}")
        append("video sizes=${map.getOutputSizes(android.media.MediaRecorder::class.java)?.joinToString()}")
        append("stabilization=${chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString()}")

        recorder = MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(if (useHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(if (width >= 2560) 24_000_000 else 16_000_000)
            setOrientationHint(90)
            file = File(getExternalFilesDir(null), "mumba-${width}x${height}-${fps}-${if (useHevc) "hevc" else "avc"}.mp4").apply {
                delete()
                setOutputFile(absolutePath)
            }
            prepare()
        }
        append("recorder prepared -> ${file?.absolutePath}")

        val opened = arrayOfNulls<Boolean>(1)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                opened[0] = true
                append("camera opened")
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(recorder!!.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                val ranges = if (fps >= 120) {
                                    map.getHighSpeedVideoFpsRangesFor(android.util.Size(width, height))
                                } else {
                                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                }
                                val range = ranges?.firstOrNull { it.lower == fps && it.upper == fps }
                                    ?: ranges?.firstOrNull { it.upper == fps }
                                if (range != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                                val stab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                                if (useEis && fps <= 60 && stab?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                                }
                                if (hdrMode > 0) {
                                    val key = availableKey(chars, "org.codeaurora.qcamera3.sessionParameters.HDRVideoMode")
                                    if (key != null) {
                                        append("setting HDR key=$key value=$hdrMode")
                                        try {
                                            @Suppress("UNCHECKED_CAST")
                                            set(key as CaptureRequest.Key<IntArray>, intArrayOf(hdrMode))
                                        } catch (t: Throwable) {
                                            append("HDR int[] set failed: ${t.message}")
                                            try {
                                                @Suppress("UNCHECKED_CAST")
                                                set(key as CaptureRequest.Key<ByteArray>, byteArrayOf(hdrMode.toByte()))
                                            } catch (t2: Throwable) {
                                                append("HDR byte[] set failed: ${t2.message}")
                                            }
                                        }
                                    } else {
                                        append("HDR key not found")
                                    }
                                }
                            }
                            if (fps >= 120) {
                                val hs = s as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                                val burst = hs.createHighSpeedRequestList(request.build())
                                hs.setRepeatingBurst(burst, null, null)
                            } else {
                                s.setRepeatingRequest(request.build(), null, null)
                            }
                            recorder!!.start()
                            append("recording for ${seconds}s")
                            Handler(Looper.getMainLooper()).postDelayed({ stop() }, seconds * 1000L)
                        } catch (t: Throwable) {
                            append("session/capture error: ${t.stackTraceToString()}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = append("configure FAILED")
                }
                if (fps >= 120) {
                    device.createConstrainedHighSpeedCaptureSession(listOf(recorder!!.surface), callback, null)
                } else {
                    device.createCaptureSession(listOf(recorder!!.surface), callback, null)
                }
            }

            override fun onDisconnected(device: CameraDevice) = append("disconnected")
            override fun onError(device: CameraDevice, error: Int) = append("camera error=$error")
        }, null)
        Handler(Looper.getMainLooper()).postDelayed({
            if (opened[0] != true) append("open timeout")
        }, 5000)
    }


    private fun availableKey(
        chars: CameraCharacteristics,
        name: String,
    ): CaptureRequest.Key<*>? = (chars.availableSessionKeys.orEmpty() +
            chars.availableCaptureRequestKeys.orEmpty()).firstOrNull { it.name == name }

    private fun stop() {
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { recorder?.stop() } catch (t: Throwable) { append("stop error: ${t.message}") }
        try { recorder?.reset(); recorder?.release() } catch (_: Throwable) {}
        recorder = null
        try { session?.close() } catch (_: Throwable) {}
        try { camera?.close() } catch (_: Throwable) {}
        val f = file
        append("stopped file=${f?.absolutePath} size=${f?.length()}")
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
    }
}
