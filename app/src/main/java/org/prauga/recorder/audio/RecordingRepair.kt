/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.audio

import android.content.Context
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.prauga.recorder.repository.RecordingsRepository

/**
 * Recordings are written to the app's external files dir and only copied into MediaStore once
 * stopped, so anything left there when the process starts was interrupted. Fix it up and publish.
 */
object RecordingRepair {
    private const val TAG = "RecordingRepair"

    fun recordingsDir(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)

    suspend fun repairAll(context: Context): Int {
        val dir = recordingsDir(context) ?: return 0
        var repaired = 0
        val files =
            dir.listFiles()?.filter { it.extension == "wav" || it.extension == "m4a" } ?: return 0
        for (file in files) {
            try {
                val mimeType =
                    when (file.extension) {
                        "wav" -> repairWav(file).let { QualityPreset.LOSSLESS.mimeType }
                        else -> repairM4a(file).let { QualityPreset.STANDARD.mimeType }
                    }
                if (
                    file.length() > 0 &&
                        RecordingsRepository.addRecordingToContentProvider(
                            context,
                            file,
                            mimeType,
                        ) != null
                ) {
                    repaired++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to repair ${file.name}", e)
            }
        }
        dir.listFiles()
            ?.filter { it.extension == "adts" || it.extension == "waveform" }
            ?.filter { !File(it.parentFile, it.nameWithoutExtension).exists() }
            ?.forEach { it.delete() }
        return repaired
    }

    private fun repairWav(file: File) {
        RandomAccessFile(file, "rw").use {
            val data = it.length() - WavSink.HEADER_SIZE
            if (data > 0) {
                WavSink.writeSizes(it, data)
            }
        }
    }

    private fun repairM4a(file: File) {
        val journal = AacSink.journalFor(file)
        if (!journal.exists()) {
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var frames = 0L
        journal.inputStream().buffered().use { input ->
            for (frame in Adts.frames(input)) {
                if (track < 0) {
                    track = muxer.addTrack(Adts.mediaFormat(frame.sampleRate, frame.channels))
                    muxer.start()
                }
                val ptsUs = frames * Adts.SAMPLES_PER_FRAME * 1_000_000L / frame.sampleRate
                muxer.writeSampleData(
                    track,
                    ByteBuffer.wrap(frame.data),
                    Adts.bufferInfo(frame.data.size, ptsUs),
                )
                frames++
            }
        }
        if (track >= 0) {
            muxer.stop()
        }
        muxer.release()
        journal.delete()
        if (track >= 0) {
            tmp.renameTo(file)
        } else {
            tmp.delete()
            file.delete()
        }
    }
}
