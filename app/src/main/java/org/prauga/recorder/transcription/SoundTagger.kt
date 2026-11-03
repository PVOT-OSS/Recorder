/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.recorder.audio.PcmDecoder
import org.prauga.recorder.data.RecordingsDatabase
import org.prauga.recorder.data.Tag
import org.tensorflow.lite.Interpreter

/** Labels what's heard over time (music, applause, laughter…) with YAMNet. */
object SoundTagger {
    private const val SAMPLE_RATE = 16000
    private const val WINDOW = 15600
    private const val WINDOW_MS = WINDOW * 1000L / SAMPLE_RATE
    private const val MIN_SCORE = 0.3f

    /** Coarse categories stored as tag labels; also the search terms for them. */
    const val SPEECH = "speech"
    const val MUSIC = "music"
    const val SINGING = "singing"
    const val APPLAUSE = "applause"
    const val LAUGHTER = "laughter"
    const val ANIMAL = "animal"
    const val SILENCE = "silence"

    /** Categories worth surfacing; speech and silence are too common to be interesting. */
    val NOTABLE = listOf(MUSIC, SINGING, APPLAUSE, LAUGHTER, ANIMAL)

    private fun category(index: Int, label: String): String? {
        val l = label.lowercase()
        return when {
            listOf("laugh", "giggle", "chuckle", "chortle", "snicker").any { it in l } -> LAUGHTER
            listOf("applause", "clapping", "cheering").any { it in l } -> APPLAUSE
            listOf("singing", "choir", "chant", "rapping", "humming", "yodel").any { it in l } ->
                SINGING
            index in 132..276 -> MUSIC
            listOf("dog", "cat", "bird", "animal", "bark", "meow", "chirp", "crow", "rooster").any {
                it in l
            } -> ANIMAL
            index == 494 -> SILENCE
            index in 0..5 || listOf("speech", "conversation", "narration").any { it in l } -> SPEECH
            else -> null
        }
    }

    /** Tags [uri]; false when the model isn't installed. */
    suspend fun tag(
        context: Context,
        id: Long,
        uri: Uri,
        title: String,
        onProgress: (Float) -> Unit,
        durationMs: Long,
    ): Boolean =
        withContext(Dispatchers.Default) {
            val file = ModelManager.taggerFile(context) ?: return@withContext false
            val labels = labels(file)
            val interpreter = Interpreter(file)
            try {
                val input = FloatArray(WINDOW)
                val output = arrayOf(FloatArray(labels.size))
                var filled = 0
                var window = 0
                val categories = mutableListOf<String?>()
                val totalWindows = (durationMs / WINDOW_MS).coerceAtLeast(1)
                PcmDecoder.decodeMono(context, uri, SAMPLE_RATE) { buffer, count ->
                    var offset = 0
                    while (offset < count) {
                        val n = minOf(count - offset, WINDOW - filled)
                        for (i in 0 until n) input[filled + i] = buffer[offset + i] / 32768f
                        filled += n
                        offset += n
                        if (filled == WINDOW) {
                            interpreter.run(input, output)
                            val scores = output[0]
                            var best = 0
                            for (i in scores.indices) if (scores[i] > scores[best]) best = i
                            categories.add(
                                if (scores[best] >= MIN_SCORE) category(best, labels[best])
                                else null
                            )
                            filled = 0
                            onProgress((++window).toFloat() / totalWindows)
                        }
                    }
                }
                val tags = merge(categories)
                RecordingsDatabase.get(context).let {
                    it.replaceTags(id, tags)
                    it.reindex(id, title)
                }
                true
            } finally {
                interpreter.close()
            }
        }

    /** Joins runs of the same category; lone windows only count for short events. */
    private fun merge(categories: List<String?>): List<Tag> {
        val tags = mutableListOf<Tag>()
        var i = 0
        while (i < categories.size) {
            val c = categories[i]
            var j = i
            while (j + 1 < categories.size && categories[j + 1] == c) j++
            val windows = j - i + 1
            if (c != null && (windows >= 2 || c == APPLAUSE || c == LAUGHTER)) {
                tags.add(Tag(i * WINDOW_MS, (j + 1) * WINDOW_MS, c, windows.toFloat()))
            }
            i = j + 1
        }
        return tags
    }

    private fun labels(file: File): List<String> =
        ZipFile(file).use { zip ->
            zip.getInputStream(zip.entries().nextElement()).bufferedReader().readLines()
        }
}
