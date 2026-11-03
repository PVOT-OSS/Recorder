/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.prauga.recorder.audio.WaveformSink
import org.prauga.recorder.data.RecordingsDatabase
import org.prauga.recorder.data.Waveforms
import org.prauga.recorder.flow.RecordingsFlow
import org.prauga.recorder.models.Recording

object RecordingsRepository {
    private val LOG_TAG = this::class.simpleName!!

    private const val ARTIST = "Recorder"

    private const val ALBUM = "Sound records"

    private const val PATH = "Recordings/${ALBUM}"
    private const val PATH_LEGACY = "Music/${ALBUM}"

    fun recordings(context: Context): Flow<List<Recording>> {
        val db = RecordingsDatabase.get(context)
        return RecordingsFlow(context).flowData().combine(db.changes) { list, _ ->
            sync(db, list)
            val metadata = db.metadata()
            list.map { recording ->
                metadata[recording.id]?.let {
                    recording.copy(
                        favourite = it.favourite,
                        labels = it.labels,
                        locationName = it.locationName,
                    )
                } ?: recording
            }
        }
    }

    private fun sync(db: RecordingsDatabase, list: List<Recording>) {
        db.retainOnly(list.map { it.id }.toSet())
        val indexed = db.indexedIds()
        for (recording in list) {
            if (recording.id !in indexed) {
                db.reindex(recording.id, recording.title)
            }
        }
    }

    suspend fun addRecordingToContentProvider(
        context: Context,
        file: File,
        mimeType: String,
        quality: String? = null,
    ) =
        withContext(Dispatchers.IO) {
            val contentResolver = context.contentResolver

            val uri =
                contentResolver.insert(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    buildCv(file, mimeType),
                )
                    ?: run {
                        Log.e(LOG_TAG, "Failed to insert ${file.absoluteFile}")

                        return@withContext null
                    }

            return@withContext try {
                contentResolver.openFileDescriptor(uri, "w", null)?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { oStream ->
                        file.inputStream().use { iStream -> iStream.copyTo(oStream) }
                    }
                    val values =
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(uri, values, null, null)
                    val id = ContentUris.parseId(uri)
                    Waveforms.adopt(context, WaveformSink.waveformFor(file), id)
                    RecordingsDatabase.get(context).let { db ->
                        db.upsert(db.metadata(id).copy(quality = quality))
                    }
                    try {
                        file.delete()
                    } catch (e: IOException) {
                        Log.w(LOG_TAG, "Failed to delete tmp file")
                    }

                    uri.toString()
                }
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Failed to write into MediaStore", e)

                null
            }
        }

    private fun buildCv(file: File, mimeType: String) =
        ContentValues().apply {
            val name = file.name

            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.ARTIST, ARTIST)
            put(MediaStore.Audio.Media.ALBUM, ALBUM)
            put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PATH
                } else {
                    PATH_LEGACY
                },
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
}
