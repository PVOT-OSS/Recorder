/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import org.prauga.recorder.data.Segment

/** Frequency-based keywords from a transcript, for title suggestions and keyword chips. */
object Keywords {
    private val STOPWORDS =
        ("a about above after again against all am an and any are as at be because been before " +
                "being below between both but by can could did do does doing down during each few " +
                "for from further gonna got had has have having he her here hers herself him " +
                "himself his how i if in into is it its itself just know like me more most my " +
                "myself no nor not now of off on once only or other our ours ourselves out over " +
                "own really right same she should so some such than that the their theirs them " +
                "themselves then there these they this those through to too um uh under until up " +
                "very was we well were what when where which while who whom why will with would " +
                "yeah yes you your yours yourself yourselves okay ok one two three four five six " +
                "seven eight nine ten zero thing things going want think say said see")
            .split(' ')
            .toSet()

    fun extract(segments: List<Segment>, limit: Int = 5): List<String> {
        val counts = HashMap<String, Int>()
        for (segment in segments) {
            for (raw in segment.text.lowercase().split(Regex("[^\\p{L}\\p{N}']+"))) {
                val word = raw.trim('\'')
                if (word.length < MIN_LENGTH || word in STOPWORDS || word.all { it.isDigit() }) {
                    continue
                }
                counts[word] = (counts[word] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    /** A few title ideas from the keywords (and place, if known). */
    fun suggestTitles(segments: List<Segment>, place: String?): List<String> {
        val keywords = extract(segments, 3).map { it.replaceFirstChar(Char::titlecase) }
        if (keywords.isEmpty()) return emptyList()
        return buildList {
                add(keywords.joinToString(", "))
                add(keywords.first())
                if (keywords.size >= 2) add("${keywords[0]} and ${keywords[1]}")
                place?.let { add("${keywords.first()} · $it") }
            }
            .distinct()
    }

    private const val MIN_LENGTH = 3
}
