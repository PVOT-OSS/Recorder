/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Decodes the first audio track of [uri] to interleaved 16-bit PCM. */
object PcmDecoder {
    private const val TIMEOUT_US = 10_000L

    fun interface Consumer {
        fun onPcm(buffer: ShortArray, count: Int, format: PcmFormat)
    }

    suspend fun decode(context: Context, uri: Uri, consumer: Consumer) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor
                        .getTrackFormat(it)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val codec =
                MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            var format =
                PcmFormat(
                    inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                )
            val info = MediaCodec.BufferInfo()
            var shorts = ShortArray(0)
            var inputDone = false
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        format =
                            PcmFormat(
                                f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            )
                    } else if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        val count = info.size / 2
                        if (count > 0) {
                            if (shorts.size < count) shorts = ShortArray(count)
                            buffer.position(info.offset)
                            buffer
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(shorts, 0, count)
                            consumer.onPcm(shorts, count, format)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Decodes [uri] to mono PCM at [sampleRate] (linear resampling), delivered in chunks. */
    suspend fun decodeMono(
        context: Context,
        uri: Uri,
        sampleRate: Int,
        consumer: (ShortArray, Int) -> Unit,
    ) {
        val resampler = MonoResampler(sampleRate)
        decode(context, uri) { buffer, count, format ->
            resampler.process(buffer, count, format, consumer)
        }
    }

    /** Linear-interpolating downmix + resample; keeps the last frame across chunks. */
    class MonoResampler(private val outRate: Int) {
        private var position = 0.0
        private var previous = 0f
        private var out = ShortArray(0)

        fun process(
            buffer: ShortArray,
            count: Int,
            format: PcmFormat,
            consumer: (ShortArray, Int) -> Unit,
        ) {
            val channels = format.channels
            val frames = count / channels
            if (frames == 0) return
            val step = format.sampleRate.toDouble() / outRate
            val maxOut = (frames / step).toInt() + 2
            if (out.size < maxOut) out = ShortArray(maxOut)

            fun frame(i: Int): Float {
                if (i < 0) return previous
                var sum = 0f
                for (c in 0 until channels) sum += buffer[i * channels + c]
                return sum / channels
            }

            var n = 0
            while (position < frames - 1) {
                val i = kotlin.math.floor(position).toInt()
                val frac = (position - i).toFloat()
                val a = frame(i)
                val b = frame(i + 1)
                out[n++] = (a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
                position += step
            }
            position -= frames
            previous = frame(frames - 1)
            consumer(out, n)
        }
    }
}
