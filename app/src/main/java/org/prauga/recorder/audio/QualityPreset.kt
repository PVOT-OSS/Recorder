/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

enum class QualityPreset(
    val key: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val lossless: Boolean,
) {
    STANDARD("standard", 32000, 1, 48_000, false),
    HIGH("high", 48000, 2, 192_000, false),
    LOSSLESS("lossless", 48000, 2, 0, true);

    val fileExtension
        get() = if (lossless) "wav" else "m4a"

    val mimeType
        get() = if (lossless) "audio/wav" else "audio/mp4"

    fun withChannels(channels: Int) = Config(this, channels)

    data class Config(val preset: QualityPreset, val channels: Int)

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}
