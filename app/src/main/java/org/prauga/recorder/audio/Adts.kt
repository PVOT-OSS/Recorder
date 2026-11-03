/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.InputStream
import java.nio.ByteBuffer

/** ADTS framing for the AAC journal that lets a killed recording be re-muxed. */
object Adts {
    private val SAMPLE_RATES =
        intArrayOf(
            96000,
            88200,
            64000,
            48000,
            44100,
            32000,
            24000,
            22050,
            16000,
            12000,
            11025,
            8000,
        )

    private const val PROFILE_LC = 2

    fun frequencyIndex(sampleRate: Int) =
        SAMPLE_RATES.indexOf(sampleRate).also {
            require(it >= 0) { "Unsupported AAC sample rate $sampleRate" }
        }

    fun header(frameLength: Int, sampleRate: Int, channels: Int): ByteArray {
        val length = frameLength + 7
        val freq = frequencyIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((PROFILE_LC - 1) shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length and 0x7FF) shr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** AudioSpecificConfig for MediaMuxer's csd-0. */
    fun csd(sampleRate: Int, channels: Int): ByteBuffer {
        val freq = frequencyIndex(sampleRate)
        return ByteBuffer.wrap(
            byteArrayOf(
                ((PROFILE_LC shl 3) or (freq shr 1)).toByte(),
                (((freq and 1) shl 7) or (channels shl 3)).toByte(),
            )
        )
    }

    fun mediaFormat(sampleRate: Int, channels: Int): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setByteBuffer("csd-0", csd(sampleRate, channels))
        }

    data class Frame(val data: ByteArray, val sampleRate: Int, val channels: Int)

    /** Reads ADTS frames until EOF or the first truncated frame. */
    fun frames(input: InputStream): Sequence<Frame> = sequence {
        val header = ByteArray(7)
        while (true) {
            if (input.readFully(header) < 7) {
                break
            }
            if (header[0] != 0xFF.toByte() || (header[1].toInt() and 0xF0) != 0xF0) {
                break
            }
            val freq = (header[2].toInt() shr 2) and 0xF
            val channels = ((header[2].toInt() and 1) shl 2) or ((header[3].toInt() shr 6) and 3)
            val length =
                ((header[3].toInt() and 3) shl 11) or
                    ((header[4].toInt() and 0xFF) shl 3) or
                    ((header[5].toInt() and 0xFF) shr 5)
            val protectionAbsent = header[1].toInt() and 1
            val headerSize = if (protectionAbsent == 1) 7 else 9
            if (headerSize == 9 && input.readFully(ByteArray(2)) < 2) {
                break
            }
            val data = ByteArray(length - headerSize)
            if (data.isEmpty() || input.readFully(data) < data.size) {
                break
            }
            yield(Frame(data, SAMPLE_RATES.getOrElse(freq) { 44100 }, channels))
        }
    }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n < 0) {
                break
            }
            read += n
        }
        return read
    }

    const val SAMPLES_PER_FRAME = 1024

    fun bufferInfo(size: Int, ptsUs: Long) =
        MediaCodec.BufferInfo().apply { set(0, size, ptsUs, MediaCodec.BUFFER_FLAG_KEY_FRAME) }
}
