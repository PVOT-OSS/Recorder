/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.data

data class RecordingMetadata(
    val id: Long,
    val favourite: Boolean = false,
    val labels: List<String> = emptyList(),
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val quality: String? = null,
)

data class Word(val startMs: Long, val endMs: Long, val text: String) {
    companion object {
        private const val FIELD = '\u001f'
        private const val RECORD = '\u001e'

        fun encode(words: List<Word>) =
            words.joinToString(RECORD.toString()) {
                "${it.startMs}$FIELD${it.endMs}$FIELD${it.text}"
            }

        fun decode(value: String): List<Word> =
            value
                .split(RECORD)
                .filter { it.isNotEmpty() }
                .mapNotNull {
                    val parts = it.split(FIELD)
                    if (parts.size != 3) {
                        return@mapNotNull null
                    }
                    Word(parts[0].toLong(), parts[1].toLong(), parts[2])
                }
    }
}

data class Segment(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),
    val speaker: Int = -1,
)

data class Tag(val startMs: Long, val endMs: Long, val label: String, val score: Float)
