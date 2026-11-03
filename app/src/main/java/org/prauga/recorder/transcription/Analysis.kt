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
        val hasTranscript = RecordingsDatabase.get(context).segments(recording.id).isNotEmpty()
        if (hasTranscript && !force) return
        Transcriber.transcribe(
            context,
            recording.id,
            recording.uri,
            recording.title,
            recording.duration,
            onProgress,
        )
    }
}
