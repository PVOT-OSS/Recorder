/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.content.Context
import java.io.Closeable
import org.json.JSONObject
import org.prauga.recorder.data.Segment
import org.prauga.recorder.data.Word
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

/** One utterance as Vosk finalised it, with its speaker embedding when a speaker model is used. */
data class Utterance(val segment: Segment, val speakerVector: FloatArray?)

/**
 * Streaming recognition over 16 kHz mono PCM. Times are relative to the first sample fed, which for
 * live transcription is the recording's own timeline (paused audio is never captured).
 */
class SpeechSession(model: Model, speakerModel: SpeakerModel?) : Closeable {
    private val recognizer =
        (if (speakerModel != null) Recognizer(model, SAMPLE_RATE.toFloat(), speakerModel)
            else Recognizer(model, SAMPLE_RATE.toFloat()))
            .apply { setWords(true) }

    val utterances = mutableListOf<Utterance>()

    /** Feeds audio; returns true when an utterance was finalised. */
    fun feed(buffer: ShortArray, count: Int): Boolean {
        if (count <= 0) return false
        if (recognizer.acceptWaveForm(buffer, count)) {
            parse(recognizer.result)?.let { utterances.add(it) }
            return true
        }
        return false
    }

    fun partial(): String = JSONObject(recognizer.partialResult).optString("partial")

    fun finish(): List<Utterance> {
        parse(recognizer.finalResult)?.let { utterances.add(it) }
        return utterances
    }

    override fun close() = recognizer.close()

    private fun parse(json: String): Utterance? {
        val obj = JSONObject(json)
        val text = obj.optString("text").trim()
        val result = obj.optJSONArray("result") ?: return null
        if (text.isEmpty() || result.length() == 0) return null
        val words =
            (0 until result.length()).map {
                val w = result.getJSONObject(it)
                Word(
                    (w.getDouble("start") * 1000).toLong(),
                    (w.getDouble("end") * 1000).toLong(),
                    w.getString("word"),
                )
            }
        val vector =
            obj.optJSONArray("spk")?.let { a ->
                FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
        return Utterance(
            Segment(
                startMs = words.first().startMs,
                endMs = words.last().endMs,
                text = text,
                words = words,
            ),
            vector,
        )
    }

    companion object {
        const val SAMPLE_RATE = 16000

        private var cachedModel: Pair<String, Model>? = null
        private var cachedSpeaker: Pair<String, SpeakerModel>? = null

        /** Opens a session with the chosen models, or null if no speech model is installed. */
        @Synchronized
        fun open(context: Context, withSpeakers: Boolean = true): SpeechSession? {
            val speech = ModelManager.speechModel(context) ?: return null
            val dir = ModelManager.dir(context, speech).absolutePath
            val model =
                cachedModel?.takeIf { it.first == dir }?.second
                    ?: Model(dir).also {
                        cachedModel?.second?.close()
                        cachedModel = dir to it
                    }
            val speaker =
                if (withSpeakers) {
                    ModelManager.speakerModel(context)?.let { m ->
                        val spkDir = ModelManager.dir(context, m).absolutePath
                        cachedSpeaker?.takeIf { it.first == spkDir }?.second
                            ?: SpeakerModel(spkDir).also { cachedSpeaker = spkDir to it }
                    }
                } else null
            return SpeechSession(model, speaker)
        }
    }
}
