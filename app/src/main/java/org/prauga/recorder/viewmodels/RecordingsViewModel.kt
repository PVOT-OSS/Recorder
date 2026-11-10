/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.viewmodels

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.prauga.recorder.data.RecordingsDatabase
import org.prauga.recorder.ext.applicationContext
import org.prauga.recorder.models.Recording
import org.prauga.recorder.repository.RecordingsRepository
import org.prauga.recorder.utils.Utils

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {
    private val contentResolver = applicationContext.contentResolver

    sealed interface Filter {
        data object All : Filter

        data object Favourites : Filter

        data class Label(val name: String) : Filter
    }

    val query = MutableStateFlow("")
    val filter = MutableStateFlow<Filter>(Filter.All)

    private val allRecordings: StateFlow<List<Recording>?> =
        RecordingsRepository.recordings(applicationContext)
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val labels: StateFlow<List<String>> =
        allRecordings
            .map { list -> list.orEmpty().flatMap { it.labels }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), listOf())

    val recordings: StateFlow<List<Recording>> =
        combine(allRecordings.filterNotNull(), query, filter) { list, query, filter ->
                val matches =
                    query
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { RecordingsDatabase.get(applicationContext).search(it) }
                val term = query.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                val db = RecordingsDatabase.get(applicationContext)
                list
                    .filter { recording ->
                        (matches == null ||
                            recording.id in matches ||
                            recording.title.contains(query.trim(), ignoreCase = true)) &&
                            when (filter) {
                                Filter.All -> true
                                Filter.Favourites -> recording.favourite
                                is Filter.Label -> filter.name in recording.labels
                            }
                    }
                    .map { recording ->
                        if (matches == null || term.isEmpty()) recording
                        else recording.copy(snippet = db.snippet(recording.id, term))
                    }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(
                viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = listOf(),
            )

    val inSelectionMode = MutableLiveData(false)

    /** Moves to the trash; MediaStore deletes trashed items for good after 30 days. */
    suspend fun deleteRecordings(vararg uris: Uri) = setTrashed(true, *uris)

    suspend fun restoreRecordings(vararg uris: Uri) = setTrashed(false, *uris)

    private suspend fun setTrashed(trashed: Boolean, vararg uris: Uri) {
        withContext(Dispatchers.IO) {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
                }
            for (uri in uris) {
                try {
                    contentResolver.update(uri, values, null, null)
                } catch (e: SecurityException) {
                    Log.e(LOG_TAG, "Failed to (un)trash recording", e)
                }
            }

            Utils.cancelShareNotification(applicationContext)
        }
    }

    suspend fun deleteForever(vararg uris: Uri) {
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (e: SecurityException) {
                    Log.e(LOG_TAG, "Failed to delete recording", e)
                }
            }
        }
    }

    val trashed: StateFlow<List<Recording>> =
        RecordingsRepository.trashed(applicationContext)
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), listOf())

    suspend fun deleteRecordings(vararg recordings: Recording) =
        deleteRecordings(*recordings.map { it.uri }.toTypedArray())

    suspend fun renameRecording(recording: Recording, newName: String) {
        withContext(Dispatchers.IO) {
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
                    put(MediaStore.Audio.Media.TITLE, newName)
                }

            contentResolver.update(recording.uri, contentValues, null, null)
            RecordingsDatabase.get(applicationContext).reindex(recording.id, newName)
        }
    }

    companion object {
        private val LOG_TAG = RecordingsViewModel::class.simpleName!!
    }
}
