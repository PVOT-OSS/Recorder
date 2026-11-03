/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

/**
 * Open Vosk models (Apache-2.0) from alphacephei.com, downloaded on demand. [size] is the zip's
 * exact length and doubles as an integrity check, since no checksums are published.
 */
data class SpeechModel(
    val id: String,
    val language: String,
    val size: Long,
    val isSpeaker: Boolean = false,
    val isTagger: Boolean = false,
    private val sourceUrl: String? = null,
) {
    val url
        get() = sourceUrl ?: "https://alphacephei.com/vosk/models/$id.zip"

    /** Vosk models come zipped; the tagger is a single .tflite file. */
    val isArchive
        get() = sourceUrl == null
}

object Models {
    val SPEAKER = SpeechModel("vosk-model-spk-0.4", "Speaker labels", 13_869_103, isSpeaker = true)

    val SPEECH =
        listOf(
            SpeechModel("vosk-model-small-en-us-0.15", "English (US)", 41_205_931),
            SpeechModel("vosk-model-small-en-in-0.4", "English (India)", 37_573_330),
            SpeechModel("vosk-model-small-de-0.15", "Deutsch", 46_499_967),
            SpeechModel("vosk-model-small-fr-0.22", "Français", 42_233_323),
            SpeechModel("vosk-model-small-es-0.42", "Español", 39_817_833),
            SpeechModel("vosk-model-small-it-0.22", "Italiano", 49_665_141),
            SpeechModel("vosk-model-small-pt-0.3", "Português", 32_453_112),
            SpeechModel("vosk-model-small-ru-0.22", "Русский", 46_236_750),
            SpeechModel("vosk-model-small-hi-0.22", "हिन्दी", 44_458_845),
            SpeechModel("vosk-model-small-cn-0.22", "中文", 43_898_754),
            SpeechModel("vosk-model-small-ja-0.22", "日本語", 49_704_573),
        )

    /** YAMNet (Apache-2.0, TensorFlow Hub) with its 521 AudioSet labels embedded. */
    val TAGGER =
        SpeechModel(
            "yamnet-classification-1",
            "Sound labels",
            4_126_810,
            isTagger = true,
            sourceUrl =
                "https://storage.googleapis.com/download.tensorflow.org/models/tflite/" +
                    "task_library/audio_classification/android/" +
                    "lite-model_yamnet_classification_tflite_1.tflite",
        )

    val ALL = SPEECH + SPEAKER + TAGGER

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }
}
