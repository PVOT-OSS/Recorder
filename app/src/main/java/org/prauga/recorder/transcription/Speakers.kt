/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import kotlin.math.sqrt
import org.prauga.recorder.data.Segment

/**
 * Groups utterances by voice: online clustering of Vosk's speaker embeddings by cosine similarity.
 * Short utterances join the nearest voice but never start a new one; a recording with a single
 * voice gets no labels at all.
 */
object Speakers {
    private const val SAME_SPEAKER = 0.45f
    private const val MIN_NEW_SPEAKER_MS = 1500L
    private const val MAX_SPEAKERS = 8

    private class Cluster(vector: FloatArray) {
        val sum = vector.copyOf()
        var count = 1

        fun centroid() = normalize(FloatArray(sum.size) { sum[it] / count })

        fun add(vector: FloatArray) {
            for (i in sum.indices) sum[i] += vector[i]
            count++
        }
    }

    fun label(utterances: List<Utterance>): List<Segment> {
        val clusters = mutableListOf<Cluster>()
        val assigned =
            utterances.map { u ->
                val vector = u.speakerVector?.let(::normalize) ?: return@map -1
                val best =
                    clusters
                        .mapIndexed { i, c -> i to dot(c.centroid(), vector) }
                        .maxByOrNull { it.second }
                val long = u.segment.endMs - u.segment.startMs >= MIN_NEW_SPEAKER_MS
                when {
                    best != null &&
                        (best.second >= SAME_SPEAKER || !long || clusters.size >= MAX_SPEAKERS) -> {
                        clusters[best.first].add(vector)
                        best.first
                    }
                    else -> {
                        clusters.add(Cluster(vector))
                        clusters.lastIndex
                    }
                }
            }
        val labelled = clusters.size > 1
        return utterances.mapIndexed { i, u ->
            u.segment.copy(speaker = if (labelled) assigned[i] else -1)
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(v.fold(0f) { acc, x -> acc + x * x }).takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }
}
