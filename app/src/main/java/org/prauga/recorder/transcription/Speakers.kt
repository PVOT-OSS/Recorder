/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import org.prauga.recorder.data.Segment

/** Turns utterances into segments; speaker labelling is filled in once embeddings are used. */
object Speakers {
    fun label(utterances: List<Utterance>): List<Segment> = utterances.map { it.segment }
}
