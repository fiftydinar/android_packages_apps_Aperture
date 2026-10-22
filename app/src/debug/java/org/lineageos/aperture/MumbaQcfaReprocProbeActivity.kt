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
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

class MumbaQcfaReprocProbeActivity : Activity() {
    private val tag = "MumbaQcfaReproc"
    private val output = StringBuilder()
    private lateinit var textView: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawImage: Image? = null
    private var rawResult: TotalCaptureResult? = null
    private var reprocessStarted = false
    private val executor: Executor = Executor { it.run() }
    private val rawFormat by lazy { intent.getIntExtra("format", ImageFormat.RAW10) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(textView) })
        append("Mumba QCFA reprocess probe")
        try {
            start()
        } catch (t: Throwable) {
            append("FATAL: ${t.stackTraceToString()}")
        }
    }

    private fun append(line: String) {
        Log.i(tag, line)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            output.append(line).append('\n'); textView.text = output
        } else {
            runOnUiThread { output.append(line).append('\n'); textView.text = output }
        }
    }

    private fun start() {
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull() ?: return
        val chars = manager.getCameraCharacteristics(id)
        append("max stall=${chars.get(CameraCharacteristics.REPROCESS_MAX_CAPTURE_STALL)}")

        rawReader = ImageReader.newInstance(4096, 3072, rawFormat, 2).apply {
            setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage()
                append("RAW image ${image.width}x${image.height} format=${image.format} planes=${image.planes.size}")
                rawImage = image
                tryReprocess()
            }, Handler(Looper.getMainLooper()))
        }
        jpegReader = ImageReader.newInstance(8192, 6144, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage()
                append("JPEG image ${image.width}x${image.height} format=${image.format}")
                val file = File(getExternalFilesDir(null), "mumba-qcfa-reproc.jpg")
                FileOutputStream(file).use { out ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    out.write(bytes)
                }
                image.close()
                append("saved ${file.absolutePath} size=${file.length()}")
            }, Handler(Looper.getMainLooper()))
        }

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                append("camera opened")
                val input = InputConfiguration(4096, 3072, rawFormat)
                val rawConfig = OutputConfiguration(rawReader!!.surface)
                val jpegConfig = OutputConfiguration(jpegReader!!.surface)
                try {
                    device.createReprocessableCaptureSession(
                        input, listOf(rawReader!!.surface, jpegReader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                append("reprocessable session configured")
                                captureRaw(s, device)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) = append("configure FAILED")
                        }, Handler(Looper.getMainLooper())
                    )
                } catch (t: Throwable) {
                    append("createReprocessableCaptureSession failed: ${t.stackTraceToString()}")
                }
            }
            override fun onDisconnected(device: CameraDevice) = append("disconnected")
            override fun onError(device: CameraDevice, error: Int) = append("camera error=$error")
        }, null)
    }

    private fun captureRaw(session: CameraCaptureSession, device: CameraDevice) {
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader!!.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
            setVendorByte(this, "com.lenovo.moto.quadra_cfa.do_remosaic", 1)
            setVendorByte(this, "com.lenovo.moto.quadra_cfa.enable_keep_remosaic_preview", 1)
        }
        session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                append("RAW capture completed")
                rawResult = result
                tryReprocess()
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                append("RAW capture failed reason=${failure.reason}")
            }
        }, null)
        append("RAW capture submitted")
    }

    private fun tryReprocess() {
        if (reprocessStarted || rawImage == null || rawResult == null) return
        reprocessStarted = true
        val device = camera ?: return
        val session = session ?: return
        val image = rawImage!!
        if (image.format != rawFormat) {
            append("unexpected RAW format ${image.format}")
            return
        }
        try {
            val builder = device.createReprocessCaptureRequest(rawResult!!)
            builder.addTarget(jpegReader!!.surface)
            builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
            setVendorByte(builder, "com.lenovo.moto.quadra_cfa.do_remosaic", 1)
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    append("reprocess completed")
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    append("reprocess failed reason=${failure.reason}")
                }
            }, null)
            append("reprocess submitted")
        } catch (t: Throwable) {
            append("reprocess setup failed: ${t.stackTraceToString()}")
        }
    }

    private fun setVendorByte(request: CaptureRequest.Builder, name: String, value: Int) {
        try {
            val ctor = CaptureRequest.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            ctor.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val key = ctor.newInstance(name, java.lang.Byte.TYPE) as CaptureRequest.Key<Byte>
            request.set(key, value.toByte())
            append("set $name=$value")
        } catch (t: Throwable) {
            append("set $name failed: ${t.message}")
        }
    }
}
