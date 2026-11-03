/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.data

import android.content.Context
import java.io.File

/** Stored waveforms: one byte per [org.prauga.recorder.audio.WaveformSink.WINDOW_MS] of audio. */
object Waveforms {
    private fun dir(context: Context) = File(context.filesDir, "waveforms").apply { mkdirs() }

    fun fileFor(context: Context, id: Long) = File(dir(context), "$id.bin")

    fun adopt(context: Context, source: File, id: Long) {
        if (source.exists()) {
            val target = fileFor(context, id)
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
    }

    fun read(context: Context, id: Long): ByteArray? =
        fileFor(context, id).takeIf { it.exists() }?.readBytes()

    fun write(context: Context, id: Long, data: ByteArray) = fileFor(context, id).writeBytes(data)

    fun delete(context: Context, id: Long) = fileFor(context, id).delete()
}
