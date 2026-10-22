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
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MumbaProbeActivity : Activity() {
    private val tag = "MumbaProbe"
    private val output = StringBuilder()
    private lateinit var textView: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply {
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(textView) })
        append("Mumba camera probe\n")
        try {
            probe()
            printEncoders()
            testEncoderConfigs()
        } catch (t: Throwable) {
            append("FATAL: ${t.stackTraceToString()}\n")
        }
    }

    private fun append(line: String) {
        Log.i(tag, line)
        output.append(line).append('\n')
        textView.text = output
    }

    private fun probe() {
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull() ?: run {
            append("no cameras")
            return
        }
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run {
            append("no stream configuration map")
            return
        }
        append("camera=$id")
        append("sensor pixel array=${chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")
        append("sensor active array=${chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}")
        printVendorValues(chars)
        printSizes("JPEG", map.getOutputSizes(ImageFormat.JPEG))
        printSizes("JPEG high-res", map.getHighResolutionOutputSizes(ImageFormat.JPEG))
        printSizes("PRIVATE", map.getOutputSizes(ImageFormat.PRIVATE))
        printSizes("P010", map.getOutputSizes(ImageFormat.YCBCR_P010))
        printSizes("RAW10", map.getOutputSizes(ImageFormat.RAW10))
        printSizes("RAW_SENSOR", map.getOutputSizes(ImageFormat.RAW_SENSOR))
        printSizes("RAW_PRIVATE", map.getOutputSizes(ImageFormat.RAW_PRIVATE))
        append("reprocess max stall=${chars.get(CameraCharacteristics.REPROCESS_MAX_CAPTURE_STALL)}")
        printSizes("video", map.getOutputSizes(android.media.MediaRecorder::class.java))
        append("high-speed sizes=${map.highSpeedVideoSizes?.joinToString()}")
        for (size in map.highSpeedVideoSizes.orEmpty()) {
            append("  ${size.width}x${size.height} fps=${map.getHighSpeedVideoFpsRangesFor(size)?.joinToString()}")
        }

        chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.let {
            append("video stabilization modes=${it.joinToString()}")
        }
        appendVendorTags(chars)

        val jpegSizes = (map.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().toList() +
                        map.getOutputSizes(ImageFormat.JPEG).orEmpty().toList())
                .distinctBy { it.width to it.height }
                .sortedByDescending { it.width.toLong() * it.height }
        val target = jpegSizes.firstOrNull { it.width >= 2560 }
        append("largest JPEG candidate=$target")
        if (target == null) return

        try {
            reader = ImageReader.newInstance(target.width, target.height, ImageFormat.JPEG, 2)
        } catch (t: Throwable) {
            append("ImageReader failed: ${t.message}")
            return
        }
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            image.use {
                val file = File(getExternalFilesDir(null), "mumba-probe.jpg")
                file.outputStream().use { out ->
                    out.write(it.planes[0].buffer.let { b ->
                        ByteArray(b.remaining()).also { bytes -> b.get(bytes) }
                    })
                }
                append("CAPTURED ${it.width}x${it.height} -> $file")
            }
        }, Handler(Looper.getMainLooper()))

        val opened = AtomicBoolean(false)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                opened.set(true)
                append("camera opened")
                try {
                    val surface = reader!!.surface
                    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            append("session configured ${target.width}x${target.height}")
                            try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    set(CaptureRequest.JPEG_ORIENTATION, 90)
                                    val key = availableKey(chars, "com.lenovo.moto.quadra_cfa.do_remosaic")
                                    if (key != null) {
                                        append("found remosaic key=$key")
                                        @Suppress("UNCHECKED_CAST")
                                        set(key as CaptureRequest.Key<Any>, 1)
                                    }
                                }
                                s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {}, null)
                                append("capture submitted")
                            } catch (t: Throwable) {
                                append("capture error: ${t.stackTraceToString()}")
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            append("session configure FAILED")
                        }
                    }, null)
                } catch (t: Throwable) {
                    append("session error: ${t.stackTraceToString()}")
                }
            }

            override fun onDisconnected(device: CameraDevice) = append("camera disconnected")
            override fun onError(device: CameraDevice, error: Int) = append("camera error=$error")
        }, null)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!opened.get()) append("open timeout")
        }, 5000)
    }


    private fun printEncoders() {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val videoTypes = info.supportedTypes.filter { it.startsWith("video/") }
            if (videoTypes.isEmpty()) continue
            append("encoder ${info.name} types=${videoTypes.joinToString()}")
            for (type in videoTypes) {
                try {
                    val caps = info.getCapabilitiesForType(type)
                    for (pl in caps.profileLevels) {
                        append("  $type profile=${pl.profile} level=${pl.level}")
                    }
                    append("  $type colorFormats=${caps.colorFormats.joinToString()}")
                } catch (t: Throwable) {
                    append("  $type capabilities failed: ${t.message}")
                }
            }
        }
    }

    private fun testEncoderConfigs() {
        val codecs = listOf(
            "c2.qti.hevc.encoder",
            "c2.qti.hevc.encoder.cq",
            "c2.android.hevc.encoder",
            "c2.android.av1.encoder",
        )
        val colorFormats = listOf(
            54 to "P010",
            2135033992 to "flexible",
            2130708361 to "surface",
        )
        val profiles = listOf(0, 1, 2, 4096, 8192)
        for (name in codecs) {
            for ((color, colorName) in colorFormats) {
                for (profile in profiles) {
                    var codec: android.media.MediaCodec? = null
                    try {
                        codec = android.media.MediaCodec.createByCodecName(name)
                        val format = android.media.MediaFormat.createVideoFormat(
                            if (name.contains("av1")) "video/av01" else "video/hevc",
                            1920, 1080
                        ).apply {
                            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, color)
                            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 16_000_000)
                            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                            if (profile > 0) {
                                setInteger(android.media.MediaFormat.KEY_PROFILE, profile)
                                setInteger(android.media.MediaFormat.KEY_LEVEL, 65536)
                            }
                        }
                        codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                        append("CFG OK $name color=$colorName profile=$profile")
                    } catch (t: Throwable) {
                        append("CFG FAIL $name color=$colorName profile=$profile ${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        try { codec?.release() } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    private fun printSizes(label: String, sizes: Array<android.util.Size>?) {
        append("$label: ${sizes?.joinToString { "${it.width}x${it.height}" }}")
    }

    private fun printVendorValues(chars: CameraCharacteristics) {
        val interesting = chars.keys.filter {
            val n = it.name.lowercase()
            n.contains("quadra") || n.contains("remosaic") || n.contains("high_resolution") ||
                n.contains("sensor_mode") || n.contains("mfnr") || n.contains("super_res")
        }
        for (key in interesting) {
            try {
                @Suppress("UNCHECKED_CAST")
                val value = chars.get(key as CameraCharacteristics.Key<Any>)
                append("char ${key.name}=${value?.toString() ?: "null"}")
            } catch (t: Throwable) {
                append("char ${key.name}=ERR ${t.message}")
            }
        }
    }

    private fun appendVendorTags(chars: CameraCharacteristics) {
        val keys = (chars.availableSessionKeys.orEmpty() + chars.availableCaptureRequestKeys.orEmpty())
            .distinct()
        val interesting = keys.filter {
            val n = it.name.lowercase()
            n.contains("quadra") || n.contains("remosaic") || n.contains("hdr") || n.contains("video")
        }
        for (key in interesting) {
            append("key ${key.name}")
        }
    }

    private fun availableKey(chars: CameraCharacteristics, name: String): CaptureRequest.Key<*>? =
        (chars.availableSessionKeys.orEmpty() + chars.availableCaptureRequestKeys.orEmpty())
            .firstOrNull { it.name == name }
}
