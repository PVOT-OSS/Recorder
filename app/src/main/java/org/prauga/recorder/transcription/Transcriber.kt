/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.recorder.audio.PcmDecoder
import org.prauga.recorder.data.RecordingsDatabase

object Transcriber {
    /** Transcribes [uri] into the database; false when no speech model is installed. */
    suspend fun transcribe(
        context: Context,
        id: Long,
        uri: Uri,
        title: String,
        durationMs: Long,
        onProgress: (Float) -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.Default) {
            val session = SpeechSession.open(context) ?: return@withContext false
            try {
                val total = (durationMs * SpeechSession.SAMPLE_RATE / 1000).coerceAtLeast(1)
                var fed = 0L
                var lastReported = -1
                PcmDecoder.decodeMono(context, uri, SpeechSession.SAMPLE_RATE) { buffer, count ->
                    session.feed(buffer, count)
                    fed += count
                    val percent = (fed * 100 / total).toInt()
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress(percent / 100f)
                    }
                }
                save(context, id, title, session.finish())
                true
            } finally {
                session.close()
            }
        }

    fun save(context: Context, id: Long, title: String, utterances: List<Utterance>) {
        val segments = Speakers.label(utterances)
        RecordingsDatabase.get(context).let {
            it.replaceSegments(id, segments)
            it.reindex(id, title)
        }
    }
}
