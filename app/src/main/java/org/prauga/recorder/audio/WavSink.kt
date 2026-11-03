/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes a WAV file whose header is refreshed every second, so a killed recording stays valid. */
class WavSink(private val file: File) : PcmSink {
    private var out: RandomAccessFile? = null
    private var bytes = ByteArray(0)
    private var format = PcmFormat(0, 0)
    private var dataBytes = 0L
    private var lastHeaderAt = 0L

    override fun onStart(format: PcmFormat) {
        this.format = format
        out =
            RandomAccessFile(file, "rw").apply {
                setLength(0)
                write(header(format, 0))
            }
    }

    override fun onPcm(buffer: ShortArray, count: Int) {
        val out = out ?: return
        if (bytes.size < count * 2) {
            bytes = ByteArray(count * 2)
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, count)
        out.write(bytes, 0, count * 2)
        dataBytes += count * 2
        val byteRate = format.sampleRate * format.channels * 2L
        if (dataBytes - lastHeaderAt >= byteRate) {
            lastHeaderAt = dataBytes
            writeSizes(out, dataBytes)
            out.fd.sync()
        }
    }

    override fun onStop() {
        out?.let {
            writeSizes(it, dataBytes)
            it.close()
        }
        out = null
    }

    companion object {
        const val HEADER_SIZE = 44

        fun header(format: PcmFormat, dataBytes: Long): ByteArray =
            ByteBuffer.allocate(HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt((dataBytes + 36).toInt())
                    put("WAVE".toByteArray())
                    put("fmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(format.channels.toShort())
                    putInt(format.sampleRate)
                    putInt(format.sampleRate * format.channels * 2)
                    putShort((format.channels * 2).toShort())
                    putShort(16)
                    put("data".toByteArray())
                    putInt(dataBytes.toInt())
                }
                .array()

        fun writeSizes(out: RandomAccessFile, dataBytes: Long) {
            val pos = out.filePointer
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            out.seek(4)
            out.write(b.putInt(0, (dataBytes + 36).toInt()).array())
            out.seek(40)
            out.write(b.putInt(0, dataBytes.toInt()).array())
            out.seek(pos)
        }
    }
}
