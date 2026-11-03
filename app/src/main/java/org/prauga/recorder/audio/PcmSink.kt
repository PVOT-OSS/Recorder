/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

data class PcmFormat(val sampleRate: Int, val channels: Int)

/** Receives interleaved 16-bit PCM from [AudioCapture], always on the capture thread. */
interface PcmSink {
    fun onStart(format: PcmFormat) {}

    fun onPcm(buffer: ShortArray, count: Int)

    fun onStop() {}
}
