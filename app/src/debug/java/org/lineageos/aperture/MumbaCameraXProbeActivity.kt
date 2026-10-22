/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.ScrollView
import android.widget.TextView
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File

class MumbaCameraXProbeActivity : ComponentActivity() {
    private val tag = "MumbaCameraX"
    private val output = StringBuilder()
    private lateinit var textView: TextView
    private var recording: Recording? = null

    private val width by lazy { intent.getIntExtra("width", 2560) }
    private val height by lazy { intent.getIntExtra("height", 1440) }
    private val fps by lazy { intent.getIntExtra("fps", 30) }
    private val seconds by lazy { intent.getIntExtra("seconds", 4) }
    private val videoBitrate by lazy { intent.getIntExtra("vbitrate", 0) }
    private val audioBitrate by lazy { intent.getIntExtra("abitrate", 0) }
    private val mime by lazy { intent.getStringExtra("mime") ?: "video/hevc" }
    private val qualityName by lazy { intent.getStringExtra("quality") ?: "" }
    private val hdr by lazy { intent.getBooleanExtra("hdr", false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(textView) })
        append("Mumba CameraX probe ${width}x$height@$fps vbr=$videoBitrate abr=$audioBitrate mime=$mime")
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
        val provider = ProcessCameraProvider.getInstance(this).get()
        val quality = when (qualityName) {
            "qhd" -> Quality.QHD
            "fhd" -> Quality.FHD
            "hd" -> Quality.HD
            else -> if (height <= 720) Quality.HD else Quality.FHD
        }
        val selector = QualitySelector.from(quality)
        val recorderBuilder = Recorder.Builder()
            .setQualitySelector(selector)
            .setVideoMimeType(mime)
        if (videoBitrate > 0) recorderBuilder.setTargetVideoEncodingBitRate(videoBitrate)
        if (audioBitrate > 0) recorderBuilder.setTargetAudioEncodingBitRate(audioBitrate)
        val recorder = recorderBuilder.build()
        val captureBuilder = VideoCapture.Builder<Recorder>(recorder)
            .setTargetFrameRate(Range(fps, fps))
            .setDynamicRange(DynamicRange.SDR)
        if (hdr) {
            try {
                val ctor = CaptureRequest.Key::class.java.getDeclaredConstructor(
                    String::class.java, Class::class.java
                )
                ctor.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val key = ctor.newInstance(
                    "org.codeaurora.qcamera3.sessionParameters.HDRVideoMode",
                    IntArray::class.java
                ) as CaptureRequest.Key<IntArray>
                Camera2Interop.Extender(captureBuilder).setCaptureRequestOption(key, intArrayOf(1))
                append("HDR key set")
            } catch (t: Throwable) {
                append("HDR key failed: ${t.message}")
            }
        }
        val videoCapture = captureBuilder.build()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)
        append("bound")

        val file = File(getExternalFilesDir(null), "camerax-${width}x${height}-${fps}-${mime.substringAfter('/')}.mp4").apply { delete() }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                recording = videoCapture.output
                    .prepareRecording(this, FileOutputOptions.Builder(file).build())
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> append("recording started")
                            is VideoRecordEvent.Status -> {}
                            is VideoRecordEvent.Finalize -> {
                                append("finalize error=${event.error} cause=${event.cause}")
                                append("file=${file.absolutePath} size=${file.length()}")
                                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
                            }
                        }
                    }
                append("start requested")
                Handler(Looper.getMainLooper()).postDelayed({
                    recording?.stop()
                    append("stop requested")
                }, seconds * 1000L)
            } catch (t: Throwable) {
                append("start failed: ${t.stackTraceToString()}")
            }
        }, 1000)
    }
}
