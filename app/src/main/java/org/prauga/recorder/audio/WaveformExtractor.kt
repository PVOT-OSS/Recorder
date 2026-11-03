/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.recorder.data.Waveforms

/** Waveform of a recording: the stored one, or decoded and cached for older or imported files. */
object WaveformExtractor {
    suspend fun load(context: Context, id: Long, uri: Uri): ByteArray =
        withContext(Dispatchers.IO) {
            Waveforms.read(context, id)
                ?: extract(context, uri).also {
                    if (it.isNotEmpty()) Waveforms.write(context, id, it)
                }
        }

    suspend fun extract(context: Context, uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        var inWindow = 0
        var peak = 0
        PcmDecoder.decode(context, uri) { buffer, count, format ->
            val windowSamples = format.sampleRate * format.channels * WaveformSink.WINDOW_MS / 1000
            for (i in 0 until count) {
                val v = abs(buffer[i].toInt())
                if (v > peak) peak = v
                if (++inWindow >= windowSamples) {
                    out.write(minOf(peak / 128, 255))
                    inWindow = 0
                    peak = 0
                }
            }
        }
        return out.toByteArray()
    }
}
