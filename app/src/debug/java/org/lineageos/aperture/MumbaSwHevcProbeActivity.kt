/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MumbaSwHevcProbeActivity : Activity() {
    private val tag = "MumbaSwHevc"
    private val output = StringBuilder()
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this).apply { textSize = 11f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(textView) })
        append("Mumba software HEVC Main10 probe")
        try {
            runProbe()
        } catch (t: Throwable) {
            append("FATAL: ${t.stackTraceToString()}")
        }
    }

    private fun append(line: String) {
        Log.i(tag, line)
        output.append(line).append('\n')
        textView.text = output
    }

    private fun runProbe() {
        val width = 1920
        val height = 1080
        val fps = 30
        val frames = 30
        val format = MediaFormat.createVideoFormat("video/hevc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, 54)
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        val codec = MediaCodec.createByCodecName("c2.android.hevc.encoder")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        append("started profile=${format.getInteger(MediaFormat.KEY_PROFILE)} color=54")

        val file = File(getExternalFilesDir(null), "mumba-sw-hevc-1080p30.mp4").apply { delete() }
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val started = SystemClock.elapsedRealtime()

        fun drain(timeout: Long) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, timeout)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        append("output format ${codec.outputFormat}")
                    }
                    index >= 0 -> {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null && info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
            }
        }

        for (frame in 0 until frames) {
            val index = codec.dequeueInputBuffer(100_000)
            if (index < 0) {
                append("input timeout at $frame")
                break
            }
            val buf = codec.getInputBuffer(index)!!
            buf.clear()
            val size = minOf(buf.capacity(), width * height * 3)
            for (i in 0 until size) buf.put(0)
            codec.queueInputBuffer(index, 0, size, frame * 1_000_000L / fps, 0)
            drain(0)
        }
        codec.queueInputBuffer(codec.dequeueInputBuffer(100_000), 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(10_000)
        val elapsed = SystemClock.elapsedRealtime() - started
        append("encoded $frames frames in ${elapsed}ms (${frames * 1000L / elapsed} fps)")
        try { codec.stop(); codec.release() } catch (_: Throwable) {}
        try { if (muxerStarted) muxer.stop(); muxer.release() } catch (_: Throwable) {}
        append("file=${file.absolutePath} size=${file.length()}")
    }
}
