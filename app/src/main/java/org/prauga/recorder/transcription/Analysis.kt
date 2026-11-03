/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.content.Context
import org.prauga.recorder.models.Recording

/** Everything computed from a finished recording, in order, sharing one progress bar. */
object Analysis {
    suspend fun run(context: Context, recording: Recording, onProgress: (Float) -> Unit) {
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
