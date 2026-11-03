/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.recorder.data.Waveforms
import org.prauga.recorder.models.Recording
import org.prauga.recorder.repository.RecordingsRepository

/** Lossless cuts: AAC frames are copied as-is, WAV samples byte for byte. Always saves a copy. */
object AudioEditor {
    enum class Mode {
        KEEP,
        REMOVE,
    }

    /** Returns the new recording's content uri, or null on failure. */
    suspend fun cut(
        context: Context,
        recording: Recording,
        startMs: Long,
        endMs: Long,
        mode: Mode,
        newTitle: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val keep =
                when (mode) {
                    Mode.KEEP -> listOf(startMs to endMs)
                    Mode.REMOVE -> listOf(0L to startMs, endMs to Long.MAX_VALUE)
                }
            val isWav = recording.mimeType.contains("wav")
            val dir = File(context.cacheDir, "edits").apply { mkdirs() }
            val out = File(dir, "$newTitle.${if (isWav) "wav" else "m4a"}")
            if (isWav) cutWav(context, recording.uri, out, keep)
            else cutMp4(context, recording.uri, out, keep)

            Waveforms.read(context, recording.id)?.let { waveform ->
                val sliced =
                    keep.flatMap { (a, b) ->
                        val from = (a / WaveformSink.WINDOW_MS).toInt().coerceIn(0, waveform.size)
                        val to =
                            (minOf(b, Long.MAX_VALUE / 2) / WaveformSink.WINDOW_MS)
                                .coerceAtMost(waveform.size.toLong())
                                .toInt()
                        waveform.copyOfRange(from, maxOf(from, to)).toList()
                    }
                WaveformSink.waveformFor(out).writeBytes(sliced.toByteArray())
            }

            RecordingsRepository.addRecordingToContentProvider(
                context,
                out,
                if (isWav) QualityPreset.LOSSLESS.mimeType else QualityPreset.STANDARD.mimeType,
            )
        }

    private fun cutMp4(context: Context, uri: Uri, out: File, keep: List<Pair<Long, Long>>) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track =
            (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")
            }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(format)
        muxer.start()
        val buffer =
            ByteBuffer.allocate(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                else 64 * 1024
            )
        val info = MediaCodec.BufferInfo()
        var removedUs = 0L
        var lastKeptEndUs = 0L
        var rangeIndex = 0
        try {
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val ptsUs = extractor.sampleTime
                val ms = ptsUs / 1000
                while (rangeIndex < keep.size && ms >= keep[rangeIndex].second) {
                    rangeIndex++
                }
                if (rangeIndex >= keep.size) break
                val (a, _) = keep[rangeIndex]
                if (ms >= a) {
                    if (ptsUs - removedUs > lastKeptEndUs + GAP_US) {
                        removedUs = ptsUs - lastKeptEndUs
                    }
                    info.set(0, size, ptsUs - removedUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(outTrack, buffer, info)
                    lastKeptEndUs = ptsUs - removedUs
                }
                extractor.advance()
            }
        } finally {
            muxer.stop()
            muxer.release()
            extractor.release()
        }
    }

    private fun cutWav(context: Context, uri: Uri, out: File, keep: List<Pair<Long, Long>>) {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { input ->
                val header = ByteBuffer.allocate(WavSink.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                input.read(header, 0)
                val format = PcmFormat(header.getInt(24), header.getShort(22).toInt())
                val frameBytes = format.channels * 2L
                val dataBytes = input.size() - WavSink.HEADER_SIZE
                RandomAccessFile(out, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(WavSink.header(format, 0))
                    var written = 0L
                    for ((a, b) in keep) {
                        fun offset(ms: Long) =
                            (minOf(ms, dataBytes) * format.sampleRate / 1000 * frameBytes).coerceIn(
                                0,
                                dataBytes,
                            )
                        val from = offset(a)
                        val to = offset(b).coerceAtLeast(from)
                        input.transferTo(WavSink.HEADER_SIZE + from, to - from, raf.channel)
                        written += to - from
                        raf.seek(WavSink.HEADER_SIZE + written)
                    }
                    WavSink.writeSizes(raf, written)
                }
            }
        }
    }

    /** Samples further apart than this mark a removed range; one AAC frame is ~32 ms. */
    private const val GAP_US = 100_000L
}
