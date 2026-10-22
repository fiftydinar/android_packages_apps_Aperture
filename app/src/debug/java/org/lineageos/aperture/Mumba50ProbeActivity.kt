/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.app.Activity
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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

class Mumba50ProbeActivity : Activity() {
    private val tag = "Mumba50Probe"
    private val output = StringBuilder()
    private lateinit var textView: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var imageCount = 0
    private val keep by lazy { intent.getBooleanExtra("keep", false) }
    private val sessionMode by lazy { intent.getIntExtra("sessionmode", -1) }
    private val snapMode by lazy { intent.getIntExtra("snapmode", -1) }
    private val fmt by lazy { intent.getStringExtra("format") ?: "jpeg" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(textView) })
        append("Mumba 50MP probe")
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
        append("pixel array=${chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")

        val size = Size(8192, 6144)
        val imageFormat = when (fmt) {
            "yuv" -> android.graphics.ImageFormat.YUV_420_888
            "private" -> android.graphics.ImageFormat.PRIVATE
            else -> android.graphics.ImageFormat.JPEG
        }
        reader = try {
            if (imageFormat == android.graphics.ImageFormat.PRIVATE) {
                ImageReader.newInstance(
                    size.width, size.height, imageFormat, 3,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                )
            } else {
                ImageReader.newInstance(size.width, size.height, imageFormat, 3)
            }
        } catch (t: Throwable) {
            append("ImageReader failed: ${t.stackTraceToString()}")
            return
        }
        append("ImageReader ${size.width}x${size.height} ok")
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage()
            append("IMAGE ${image.width}x${image.height} format=${image.format} planes=${image.planes.size}")
            if (fmt == "private") {
                try {
                    val hb = image.hardwareBuffer
                    if (hb == null) {
                        append("hardwareBuffer null")
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    append("hardwareBuffer format=${hb.format} usage=${hb.usage} size=${hb.width}x${hb.height}")
                    val bitmap = Bitmap.wrapHardwareBuffer(hb, null)
                    if (bitmap == null) {
                        append("wrapHardwareBuffer null")
                    } else {
                        val file = File(getExternalFilesDir(null), "mumba-50mp-private.jpg")
                        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                        append("wrapped bitmap ${bitmap.width}x${bitmap.height} saved=${file.length()}")
                        bitmap.recycle()
                    }
                    hb.close()
                } catch (t: Throwable) {
                    append("private conversion failed: ${t.stackTraceToString()}")
                }
                image.close()
                return@setOnImageAvailableListener
            }
            if (fmt != "jpeg") {
                image.close()
                append("non-jpeg image closed")
                return@setOnImageAvailableListener
            }
            imageCount++
            val file = File(getExternalFilesDir(null), "mumba-50mp-$imageCount.jpg")
            FileOutputStream(file).use { out ->
                val buffer = image.planes[0].buffer
                append("buffer remaining=${buffer.remaining()}")
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
            }
            image.close()
            append("saved ${file.absolutePath} size=${file.length()} count=$imageCount")
        }, Handler(Looper.getMainLooper()))

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                append("camera opened")
                val surfaces = listOf(reader!!.surface)
                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        append("session configured")
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(reader!!.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                                setVendorByte(this, "com.lenovo.moto.quadra_cfa.do_remosaic", 1)
                                setVendorByte(this, "com.lenovo.moto.quadra_cfa.do_sw_remosaic", 1)
                                setVendorByte(this, "org.quic.camera.QCFASnapshot", 1)
                                if (keep) setVendorByte(this, "com.lenovo.moto.quadra_cfa.enable_keep_remosaic_preview", 1)
                                if (sessionMode >= 0) setVendorByte(this, "com.lenovo.moto.control.session_mode", sessionMode)
                                if (snapMode >= 0) setVendorByte(this, "com.lenovo.moto.control.snap_mode", snapMode)
                            }
                            s.capture(request.build(), null, null)
                            append("capture submitted")
                        } catch (t: Throwable) {
                            append("capture failed: ${t.stackTraceToString()}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = append("configure FAILED")
                }
                try {
                    device.createCaptureSession(surfaces, callback, null)
                } catch (t: Throwable) {
                    append("createCaptureSession failed: ${t.stackTraceToString()}")
                }
            }

            override fun onDisconnected(device: CameraDevice) = append("disconnected")
            override fun onError(device: CameraDevice, error: Int) = append("camera error=$error")
        }, null)
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
