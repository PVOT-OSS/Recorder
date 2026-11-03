/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.prauga.recorder.audio.PcmDecoder
import org.prauga.recorder.audio.PcmFormat
import org.prauga.recorder.audio.PcmSink

/**
 * Recognises speech while recording. Resampling happens on the capture thread, recognition on its
 * own thread so a slow model never stalls capture; [onText] gets finalised text plus the partial.
 */
class LiveTranscriptionSink(
    private val session: SpeechSession,
    private val onText: (String) -> Unit,
) : PcmSink {
    private val queue = LinkedBlockingQueue<ShortArray>()
    private var resampler = PcmDecoder.MonoResampler(SpeechSession.SAMPLE_RATE)
    private var format = PcmFormat(0, 0)
    private var worker: Thread? = null

    @Volatile private var running = false

    @Volatile
    var utterances: List<Utterance> = emptyList()
        private set

    override fun onStart(format: PcmFormat) {
        this.format = format
        resampler = PcmDecoder.MonoResampler(SpeechSession.SAMPLE_RATE)
        running = true
        worker = Thread(::loop, "LiveTranscription").apply { start() }
    }

    override fun onPcm(buffer: ShortArray, count: Int) {
        resampler.process(buffer, count, format) { out, n -> queue.put(out.copyOf(n)) }
    }

    override fun onStop() {
        running = false
        worker?.join(STOP_TIMEOUT_MS)
        worker = null
    }

    private fun loop() {
        try {
            var lastText = ""
            while (running || queue.isNotEmpty()) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                session.feed(chunk, chunk.size)
                val text =
                    (session.utterances.map { it.segment.text } + session.partial())
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                if (text != lastText) {
                    lastText = text
                    onText(text)
                }
            }
            utterances = session.finish().toList()
        } catch (e: Exception) {
            Log.e(TAG, "Live transcription failed", e)
        } finally {
            session.close()
        }
    }

    companion object {
        private const val TAG = "LiveTranscription"
        private const val STOP_TIMEOUT_MS = 30_000L
    }
}
