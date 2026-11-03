/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Tracks the live peak for the UI and writes one byte (peak / 128) per [WINDOW_MS] to
 * [waveformFor], so playback can draw the waveform without decoding the audio.
 */
class WaveformSink(private val file: File) : PcmSink {
    @Volatile
    var currentPeak = 0
        private set

    private var out: FileOutputStream? = null
    private var windowSamples = 0
    private var inWindow = 0
    private var windowPeak = 0

    override fun onStart(format: PcmFormat) {
        windowSamples = format.sampleRate * format.channels * WINDOW_MS / 1000
        out = FileOutputStream(waveformFor(file))
    }

    override fun onPcm(buffer: ShortArray, count: Int) {
        var peak = 0
        for (i in 0 until count) {
            val v = abs(buffer[i].toInt())
            if (v > peak) peak = v
            if (v > windowPeak) windowPeak = v
            if (++inWindow >= windowSamples) {
                out?.write(minOf(windowPeak / 128, 255))
                inWindow = 0
                windowPeak = 0
            }
        }
        currentPeak = peak
    }

    override fun onStop() {
        out?.close()
        out = null
        currentPeak = 0
    }

    companion object {
        const val WINDOW_MS = 50

        fun waveformFor(file: File) = File(file.parentFile, file.name + ".waveform")
    }
}
