/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.content.Context
import org.prauga.recorder.data.RecordingsDatabase
import org.prauga.recorder.models.Recording

/** Everything computed from a finished recording, in order, sharing one progress bar. */
object Analysis {
    /** [force] re-transcribes even if a transcript (e.g. the live one) exists. */
    suspend fun run(
        context: Context,
        recording: Recording,
        force: Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val db = RecordingsDatabase.get(context)
        val transcribe = force || db.segments(recording.id).isEmpty()
        val tag =
            ModelManager.taggerFile(context) != null && (force || db.tags(recording.id).isEmpty())
        val steps = listOf(transcribe, tag).count { it }.coerceAtLeast(1)
        var done = 0
        if (transcribe) {
            Transcriber.transcribe(
                context,
                recording.id,
                recording.uri,
                recording.title,
                recording.duration,
            ) {
                onProgress((done + it) / steps)
            }
            done++
        }
        if (tag) {
            SoundTagger.tag(
                context,
                recording.id,
                recording.uri,
                recording.title,
                { onProgress((done + it) / steps) },
                recording.duration,
            )
        }
    }
}
