/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.clip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.prauga.recorder.audio.PcmDecoder
import org.prauga.recorder.audio.WaveformSink
import org.prauga.recorder.data.Segment
import org.prauga.recorder.models.Recording

/** Renders a range of a recording as an MP4: waveform, progress and captions over the audio. */
object ClipExporter {
    enum class Shape(val width: Int, val height: Int) {
        PORTRAIT(720, 1280),
        LANDSCAPE(1280, 720),
        SQUARE(1080, 1080),
    }

    const val MAX_LENGTH_MS = 60_000L
    private const val FPS = 15
    private const val VIDEO_BITRATE = 2_000_000
    private const val AUDIO_BITRATE = 96_000
    private const val AUDIO_RATE = 44100
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IN_FLIGHT = 1
    private const val MAX_WAITS = 50

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    suspend fun export(
        context: Context,
        recording: Recording,
        waveform: ByteArray,
        startMs: Long,
        endMs: Long,
        segments: List<Segment>,
        speakerName: (Int) -> String?,
        shape: Shape,
        dark: Boolean,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.Default) {
            val end = minOf(endMs, startMs + MAX_LENGTH_MS)
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val out = File(dir, "${recording.title.substringBeforeLast('.').replace('/', '_')}.mp4")

            val (audioFormat, audio) = encodeAudio(context, recording, startMs, end)
            onProgress(0.2f)

            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val frames = ((end - startMs) * FPS / 1000).toInt().coerceAtLeast(1)
                val renderer =
                    Renderer(shape, dark, recording, waveform, startMs, end, segments, speakerName)
                encodeVideo(shape, frames, renderer, muxer, audioFormat, audio) {
                    onProgress(0.2f + 0.8f * it)
                }
            } finally {
                muxer.release()
            }
            out
        }

    /** Decodes the range, resamples to 44.1 kHz mono and encodes AAC into memory. */
    private suspend fun encodeAudio(
        context: Context,
        recording: Recording,
        startMs: Long,
        endMs: Long,
    ): Pair<MediaFormat, List<Sample>> {
        val pcm = ShortArrayBuilder()
        val resampler = PcmDecoder.MonoResampler(AUDIO_RATE)
        val skip = startMs * AUDIO_RATE / 1000
        val take = (endMs - startMs) * AUDIO_RATE / 1000
        var seen = 0L
        PcmDecoder.decode(context, recording.uri) { buffer, count, format ->
            if (seen >= skip + take) return@decode
            resampler.process(buffer, count, format) { out, n ->
                for (i in 0 until n) {
                    if (seen in skip until skip + take) pcm.add(out[i])
                    seen++
                }
            }
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
            },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        codec.start()
        val samples = mutableListOf<Sample>()
        var format: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        val data = pcm.build()
        var offset = 0
        var inputDone = false
        try {
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val n = minOf(data.size - offset, buffer.remaining() / 2)
                        val pts = offset * 1_000_000L / AUDIO_RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                pts,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            buffer
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .put(data, offset, n)
                            codec.queueInputBuffer(index, 0, n * 2, pts, 0)
                            offset += n
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    format = codec.outputFormat
                } else if (index >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        codec.getOutputBuffer(index)!!.apply { position(info.offset) }.get(bytes)
                        samples.add(Sample(bytes, info.presentationTimeUs, info.flags))
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return format!! to samples
    }

    private suspend fun encodeVideo(
        shape: Shape,
        frames: Int,
        renderer: Renderer,
        muxer: MediaMuxer,
        audioFormat: MediaFormat,
        audio: List<Sample>,
        onProgress: (Float) -> Unit,
    ) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, shape.width, shape.height)
                .apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        val surface: Surface = codec.createInputSurface()
        codec.start()
        val info = MediaCodec.BufferInfo()
        var videoTrack = -1
        var written = 0L
        var rendered = 0

        fun drain(endOfStream: Boolean, timeoutUs: Long = 0) {
            while (true) {
                val index =
                    codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else timeoutUs)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        val audioTrack = muxer.addTrack(audioFormat)
                        muxer.start()
                        val audioInfo = MediaCodec.BufferInfo()
                        for (s in audio) {
                            audioInfo.set(0, s.data.size, s.ptsUs, s.flags)
                            muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), audioInfo)
                        }
                    }
                    index >= 0 -> {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && info.size > 0 && videoTrack >= 0) {
                            // Canvas frames carry wall-clock times; restamp them at the clip rate
                            info.presentationTimeUs = written++ * 1_000_000L / FPS
                            muxer.writeSampleData(videoTrack, codec.getOutputBuffer(index)!!, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!endOfStream) return
                }
            }
        }

        try {
            while (rendered < frames) {
                coroutineContext.ensureActive()
                val canvas = surface.lockHardwareCanvas()
                renderer.draw(canvas, rendered.toFloat() / frames)
                surface.unlockCanvasAndPost(canvas)
                rendered++
                // The input surface drops frames that arrive faster than the encoder takes them,
                // so wait for this one to come out before drawing the next
                var waits = 0
                while (written < rendered - MAX_IN_FLIGHT && waits++ < MAX_WAITS) {
                    drain(false, TIMEOUT_US)
                }
                onProgress(rendered.toFloat() / frames)
            }
            codec.signalEndOfInputStream()
            drain(true)
            muxer.stop()
        } finally {
            codec.stop()
            codec.release()
            surface.release()
        }
    }

    private class Renderer(
        shape: Shape,
        dark: Boolean,
        recording: Recording,
        private val waveform: ByteArray,
        private val startMs: Long,
        private val endMs: Long,
        private val segments: List<Segment>,
        private val speakerName: (Int) -> String?,
    ) {
        private val width = shape.width.toFloat()
        private val height = shape.height.toFloat()
        private val unit = minOf(width, height) / 20f
        private val background =
            if (dark) Color.rgb(0x12, 0x14, 0x12) else Color.rgb(0xFB, 0xFD, 0xF6)
        private val foreground = if (dark) Color.WHITE else Color.rgb(0x1A, 0x1C, 0x19)
        private val accent = if (dark) Color.rgb(0x9C, 0xD6, 0x9C) else Color.rgb(0x3B, 0x69, 0x3D)
        private val muted =
            Color.argb(0x66, Color.red(foreground), Color.green(foreground), Color.blue(foreground))
        private val title = recording.title.substringBeforeLast('.')
        private val titlePaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground
                textSize = unit * 1.1f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        private val captionPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground
                textSize = unit * 1.2f
            }
        private val speakerPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = unit * 0.8f
            }
        private val barPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                strokeWidth = unit * 0.25f
            }

        fun draw(canvas: Canvas, fraction: Float) {
            canvas.drawColor(background)
            val margin = unit * 1.5f
            val titleText =
                TextUtils.ellipsize(title, titlePaint, width - 2 * margin, TextUtils.TruncateAt.END)
            canvas.drawText(titleText, 0, titleText.length, margin, margin + unit, titlePaint)

            val waveTop = height * 0.18f
            val waveBottom = height * 0.45f
            val mid = (waveTop + waveBottom) / 2
            val half = (waveBottom - waveTop) / 2
            val from = (startMs / WaveformSink.WINDOW_MS).toInt().coerceIn(0, waveform.size)
            val to = (endMs / WaveformSink.WINDOW_MS).toInt().coerceIn(from, waveform.size)
            val bars = ((width - 2 * margin) / (unit * 0.45f)).toInt().coerceAtLeast(1)
            for (b in 0 until bars) {
                val a = from + (to - from) * b / bars
                val z = max(a + 1, from + (to - from) * (b + 1) / bars)
                var peak = 0
                for (i in a until minOf(z, waveform.size)) peak =
                    max(peak, waveform[i].toInt() and 0xFF)
                val h = max(barPaint.strokeWidth / 2, half * sqrt(peak / 255f))
                val x = margin + (width - 2 * margin) * (b + 0.5f) / bars
                barPaint.color = if (b.toFloat() / bars <= fraction) accent else muted
                canvas.drawLine(x, mid - h, x, mid + h, barPaint)
            }

            val nowMs = startMs + ((endMs - startMs) * fraction).toLong()
            val segment = segments.lastOrNull { it.startMs <= nowMs && nowMs <= it.endMs + 1500 }
            if (segment != null) {
                var y = height * 0.52f
                speakerName(segment.speaker)?.let {
                    canvas.drawText(it, margin, y + speakerPaint.textSize, speakerPaint)
                    y += speakerPaint.textSize * 1.6f
                }
                val layout =
                    StaticLayout.Builder.obtain(
                            segment.text,
                            0,
                            segment.text.length,
                            captionPaint,
                            (width - 2 * margin).toInt(),
                        )
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(
                            ((height * 0.9f - y) / (captionPaint.textSize * 1.3f))
                                .toInt()
                                .coerceAtLeast(1)
                        )
                        .setEllipsize(TextUtils.TruncateAt.START)
                        .build()
                canvas.save()
                canvas.translate(margin, y)
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }

    private class ShortArrayBuilder {
        private var data = ShortArray(AUDIO_RATE)
        private var size = 0

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun build() = data.copyOf(size)
    }
}
