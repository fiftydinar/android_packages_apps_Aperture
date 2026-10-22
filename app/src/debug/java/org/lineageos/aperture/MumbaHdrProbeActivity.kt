/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.app.Activity
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer

class MumbaHdrProbeActivity : Activity() {
    private val tag = "MumbaHdrProbe"
    private val output = StringBuilder()
    private lateinit var textView: TextView
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    @Volatile private var muxerStarted = false
    @Volatile private var stopRequested = false
    private var drainThread: Thread? = null
    private var file: File? = null
    private var frames = 0

    private val width by lazy { intent.getIntExtra("width", 1920) }
    private val height by lazy { intent.getIntExtra("height", 1080) }
    private val fps by lazy { intent.getIntExtra("fps", 30) }
    private val seconds by lazy { intent.getIntExtra("seconds", 4) }
    private val hdrMode by lazy { intent.getIntExtra("hdr", 1) }
    private val transfer by lazy { intent.getStringExtra("transfer") ?: "hlg" }
    private val profile by lazy { intent.getIntExtra("profile", 2) }
    private val level by lazy { intent.getIntExtra("level", 0) }
    private val color by lazy { intent.getBooleanExtra("color", true) }
    private val codecName by lazy { intent.getStringExtra("codec") ?: "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(textView) })
        append("Mumba HDR probe ${width}x$height@$fps hdr=$hdrMode transfer=$transfer profile=$profile level=$level color=$color codec=$codecName")
        try {
            start()
        } catch (t: Throwable) {
            append("FATAL: ${t.stackTraceToString()}")
        }
    }

    private fun append(line: String) {
        Log.i(tag, line)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            output.append(line).append('\n')
            textView.text = output
        } else {
            runOnUiThread {
                output.append(line).append('\n')
                textView.text = output
            }
        }
    }

    private fun start() {
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull() ?: return
        val chars = manager.getCameraCharacteristics(id)

        val format = MediaFormat.createVideoFormat("video/hevc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (width >= 2560) 24_000_000 else 16_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            try {
                if (profile > 0) setInteger(MediaFormat.KEY_PROFILE, profile)
                if (level > 0) setInteger(MediaFormat.KEY_LEVEL, level)
                if (color) {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(
                        MediaFormat.KEY_COLOR_TRANSFER,
                        if (transfer.equals("pq", true)) MediaFormat.COLOR_TRANSFER_ST2084 else MediaFormat.COLOR_TRANSFER_HLG
                    )
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
            } catch (t: Throwable) {
                append("format extras failed: ${t.message}")
            }
        }
        encoder = if (codecName.isNotEmpty()) MediaCodec.createByCodecName(codecName) else MediaCodec.createEncoderByType("video/hevc")
        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = encoder!!.createInputSurface()
        encoder!!.start()
        append("encoder started profile=${if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else "unset"}")

        file = File(getExternalFilesDir(null), "mumba-hdr-${width}x${height}-${fps}-${transfer}.mp4").apply { delete() }
        muxer = MediaMuxer(file!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        drainThread = Thread({ drain() }, "mumba-hdr-drain").apply { start() }

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                append("camera opened")
                device.createCaptureSession(listOf(input), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(input)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                ranges?.firstOrNull { it.lower == fps && it.upper == fps }?.let {
                                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                                }
                                if (hdrMode > 0) {
                                    val key = availableKey(chars, "org.codeaurora.qcamera3.sessionParameters.HDRVideoMode")
                                    if (key != null) {
                                        @Suppress("UNCHECKED_CAST")
                                        set(key as CaptureRequest.Key<IntArray>, intArrayOf(hdrMode))
                                        append("HDR key set value=$hdrMode")
                                    } else {
                                        append("HDR key not found")
                                    }
                                }
                            }
                            s.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                                    frames++
                                    if (frames <= 3 || frames % 30 == 0) append("frame $frames")
                                }

                                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {}

                                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                    append("capture failed reason=${failure.reason}")
                                }
                            }, null)
                            append("recording for ${seconds}s")
                            Handler(Looper.getMainLooper()).postDelayed({ stop() }, seconds * 1000L)
                        } catch (t: Throwable) {
                            append("session error: ${t.stackTraceToString()}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = append("configure FAILED")
                }, null)
            }

            override fun onDisconnected(device: CameraDevice) = append("disconnected")
            override fun onError(device: CameraDevice, error: Int) = append("camera error=$error")
        }, null)
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                encoder!!.dequeueOutputBuffer(info, 10000)
            } catch (t: Throwable) {
                append("dequeue error: ${t.message}")
                return
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer!!.addTrack(encoder!!.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                    append("muxer started track=$track format=${encoder!!.outputFormat}")
                }
                index >= 0 -> {
                    val buffer = encoder!!.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer!!.writeSampleData(track, buffer, info)
                    }
                    encoder!!.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
            if (stopRequested && index < 0 && !muxerStarted) {
                // keep draining
            }
        }
    }

    private fun stop() {
        stopRequested = true
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.abortCaptures() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        try { camera?.close() } catch (_: Throwable) {}
        Handler(Looper.getMainLooper()).postDelayed({
            try { encoder?.signalEndOfInputStream() } catch (_: Throwable) {}
            try { drainThread?.join(3000) } catch (_: Throwable) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Throwable) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            append("stopped frames=$frames file=${file?.absolutePath} size=${file?.length()}")
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1000)
        }, 800)
    }

    private fun availableKey(
        chars: CameraCharacteristics,
        name: String,
    ): CaptureRequest.Key<*>? = (chars.availableSessionKeys.orEmpty() +
            chars.availableCaptureRequestKeys.orEmpty()).firstOrNull { it.name == name }
}
