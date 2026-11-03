/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.Manifest.permission
import android.media.AudioDeviceInfo
import androidx.annotation.RequiresPermission
import java.io.File
import org.prauga.recorder.service.SoundRecording

/** [SoundRecording] on top of [AudioCapture]: an encoder sink plus the waveform sink. */
class EngineRecorder(
    private val config: QualityPreset.Config,
    private val preferredDevice: AudioDeviceInfo? = null,
    private val clearVoice: Boolean = false,
) : SoundRecording {
    private var capture: AudioCapture? = null
    private var waveform: WaveformSink? = null
    private var paused = false

    /** Extra sinks (e.g. live transcription) attached before [startRecording]. */
    val extraSinks = mutableListOf<PcmSink>()

    @RequiresPermission(permission.RECORD_AUDIO)
    override fun startRecording(file: File) {
        val format = PcmFormat(config.preset.sampleRate, config.channels)
        val encoder =
            if (config.preset.lossless) WavSink(file) else AacSink(file, config.preset.bitrate)
        val waveform = WaveformSink(file).also { this.waveform = it }
        capture =
            AudioCapture(format, preferredDevice, clearVoice).apply {
                sinks.add(encoder)
                sinks.add(waveform)
                sinks.addAll(extraSinks)
                start()
            }
    }

    override fun stopRecording(): Boolean {
        val capture = capture ?: return false
        capture.stop()
        this.capture = null
        return capture.framesCaptured > 0
    }

    override fun pauseRecording(): Boolean {
        if (paused) return false
        capture?.pause() ?: return false
        paused = true
        return true
    }

    override fun resumeRecording(): Boolean {
        if (!paused) return false
        capture?.resume() ?: return false
        paused = false
        return true
    }

    override val currentAmplitude: Int
        get() = waveform?.currentPeak ?: 0

    val durationMs
        get() = capture?.durationMs ?: 0

    override val mimeType = config.preset.mimeType

    override val fileExtension = config.preset.fileExtension
}
