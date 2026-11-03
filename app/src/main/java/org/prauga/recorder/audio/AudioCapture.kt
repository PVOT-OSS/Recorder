/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.Manifest.permission
import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.CopyOnWriteArrayList

/** One [AudioRecord] read loop fanning PCM out to [sinks]; paused time is simply not captured. */
class AudioCapture(
    private val format: PcmFormat,
    private val preferredDevice: AudioDeviceInfo? = null,
    private val clearVoice: Boolean = false,
) {
    val sinks = CopyOnWriteArrayList<PcmSink>()

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @Volatile private var running = false
    @Volatile private var paused = false
    private val pauseLock = Object()

    /** Frames captured so far, i.e. the recording's length. */
    @Volatile
    var framesCaptured = 0L
        private set

    val durationMs
        get() = framesCaptured * 1000 / format.sampleRate

    @RequiresPermission(permission.RECORD_AUDIO)
    fun start() {
        val channelMask =
            if (format.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBuffer =
            AudioRecord.getMinBufferSize(
                format.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        val source =
            if (clearVoice) MediaRecorder.AudioSource.VOICE_RECOGNITION
            else MediaRecorder.AudioSource.MIC
        val record =
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, format.sampleRate))
                .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        preferredDevice?.let { record.setPreferredDevice(it) }
        if (clearVoice) {
            enableEffects(record.audioSessionId)
        }
        this.record = record
        sinks.forEach { it.onStart(format) }
        running = true
        record.startRecording()
        thread = Thread(::loop, "AudioCapture").apply { start() }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        synchronized(pauseLock) {
            paused = false
            pauseLock.notifyAll()
        }
    }

    fun stop() {
        running = false
        resume()
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
    }

    val audioSessionId
        get() = record?.audioSessionId ?: 0

    @SuppressLint("MissingPermission")
    private fun loop() {
        val record = record ?: return
        val buffer = ShortArray(format.sampleRate * format.channels / 20)
        try {
            while (running) {
                if (paused) {
                    record.stop()
                    synchronized(pauseLock) {
                        while (paused && running) {
                            pauseLock.wait()
                        }
                    }
                    if (!running) break
                    record.startRecording()
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0 && !paused) {
                    for (sink in sinks) {
                        try {
                            sink.onPcm(buffer, read)
                        } catch (e: Exception) {
                            Log.e(TAG, "Sink ${sink::class.simpleName} failed", e)
                            sinks.remove(sink)
                        }
                    }
                    framesCaptured += read / format.channels
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read returned $read")
                    break
                }
            }
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Stop failed", e)
            }
            effects.forEach { it.release() }
            effects.clear()
            record.release()
            this.record = null
            for (sink in sinks) {
                try {
                    sink.onStop()
                } catch (e: Exception) {
                    Log.e(TAG, "Sink ${sink::class.simpleName} failed to stop", e)
                }
            }
        }
    }

    private fun enableEffects(sessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let {
                it.enabled = true
                effects.add(it)
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.let {
                it.enabled = true
                effects.add(it)
            }
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val STOP_TIMEOUT_MS = 5000L
    }
}
