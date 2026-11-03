/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream

/**
 * Encodes PCM to AAC-LC into an MPEG-4 file. Every frame is also appended to an ADTS journal
 * ([journalFor]) that is deleted after a clean stop; [RecordingRepair] re-muxes it otherwise.
 */
class AacSink(private val file: File, private val bitrate: Int) : PcmSink {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var journal: FileOutputStream? = null
    private var track = -1
    private var format = PcmFormat(0, 0)
    private var samplesQueued = 0L
    private val info = MediaCodec.BufferInfo()

    override fun onStart(format: PcmFormat) {
        this.format = format
        val mediaFormat =
            MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    format.sampleRate,
                    format.channels,
                )
                .apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
        codec =
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        journal = FileOutputStream(journalFor(file))
    }

    override fun onPcm(buffer: ShortArray, count: Int) {
        val codec = codec ?: return
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(false)
                continue
            }
            val input = codec.getInputBuffer(index)!!
            input.clear()
            val n = minOf(count - offset, input.remaining() / 2)
            input.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, offset, n)
            codec.queueInputBuffer(index, 0, n * 2, ptsUs(), 0)
            samplesQueued += n / format.channels
            offset += n
            drain(false)
        }
    }

    override fun onStop() {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(TIMEOUT_US * 10)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)
        codec.stop()
        codec.release()
        this.codec = null
        journal?.close()
        journal = null
        muxer?.let {
            if (track >= 0) {
                it.stop()
            }
            it.release()
        }
        muxer = null
        if (track >= 0) {
            journalFor(file).delete()
        }
    }

    private fun ptsUs() = samplesQueued * 1_000_000L / format.sampleRate

    private fun drain(endOfStream: Boolean) {
        val codec = codec ?: return
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer!!.addTrack(codec.outputFormat)
                    muxer!!.start()
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        muxer!!.writeSampleData(track, output, info)
                        val frame = ByteArray(info.size)
                        output.position(info.offset)
                        output.get(frame)
                        journal?.write(Adts.header(frame.size, format.sampleRate, format.channels))
                        journal?.write(frame)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return
                    }
                }
                else -> if (!endOfStream || ++idle > MAX_IDLE_DRAINS) return
            }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_IDLE_DRAINS = 100

        fun journalFor(file: File) = File(file.parentFile, file.name + ".adts")
    }
}
