/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.prauga.recorder.utils.PreferencesManager

/** Downloads (via DownloadManager), verifies, unpacks and deletes speech models. */
object ModelManager {
    private const val TAG = "ModelManager"
    private const val PREFS = "models"

    sealed interface State {
        data object NotInstalled : State

        data class Downloading(val progress: Float) : State

        data object Installing : State

        data object Installed : State

        data object Failed : State
    }

    private val _changes = MutableStateFlow(0)
    val changes = _changes.asStateFlow()

    private fun root(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    fun dir(context: Context, model: SpeechModel) = File(root(context), model.id)

    fun isInstalled(context: Context, model: SpeechModel) =
        File(dir(context, model), ".complete").exists()

    fun installed(context: Context) = Models.ALL.filter { isInstalled(context, it) }

    /** The model to transcribe with: the chosen one if installed, else any installed one. */
    fun speechModel(context: Context): SpeechModel? {
        val chosen = Models.byId(PreferencesManager(context).speechModel)
        return chosen?.takeIf { isInstalled(context, it) }
            ?: Models.SPEECH.firstOrNull { isInstalled(context, it) }
    }

    fun speakerModel(context: Context) = Models.SPEAKER.takeIf { isInstalled(context, it) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context, model: SpeechModel): State {
        if (isInstalled(context, model)) return State.Installed
        val downloadId = prefs(context).getLong(model.id, -1)
        if (downloadId < 0) return State.NotInstalled
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
            if (!c.moveToFirst()) return State.NotInstalled
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> State.Installing
                DownloadManager.STATUS_FAILED -> State.Failed
                else -> {
                    val done =
                        c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                    State.Downloading(done.toFloat() / model.size)
                }
            }
        }
        return State.NotInstalled
    }

    fun download(context: Context, model: SpeechModel, wifiOnly: Boolean) {
        val target = File(context.getExternalFilesDir(null), "${model.id}.zip").apply { delete() }
        val request =
            DownloadManager.Request(Uri.parse(model.url))
                .setTitle(model.language)
                .setDestinationUri(Uri.fromFile(target))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        prefs(context).edit().putLong(model.id, id).apply()
        _changes.update { it + 1 }
    }

    fun cancel(context: Context, model: SpeechModel) {
        val id = prefs(context).getLong(model.id, -1)
        if (id >= 0) context.getSystemService(DownloadManager::class.java).remove(id)
        prefs(context).edit().remove(model.id).apply()
        _changes.update { it + 1 }
    }

    fun delete(context: Context, model: SpeechModel) {
        cancel(context, model)
        dir(context, model).deleteRecursively()
        _changes.update { it + 1 }
    }

    /** Called when DownloadManager finishes [downloadId]; unpacks it if it's one of ours. */
    suspend fun onDownloadComplete(context: Context, downloadId: Long) =
        withContext(Dispatchers.IO) {
            val model =
                Models.ALL.firstOrNull { prefs(context).getLong(it.id, -1) == downloadId }
                    ?: return@withContext
            val zip = File(context.getExternalFilesDir(null), "${model.id}.zip")
            try {
                if (state(context, model) != State.Installing || zip.length() != model.size) {
                    Log.e(TAG, "Download of ${model.id} failed or has the wrong size")
                    return@withContext
                }
                unzip(zip, dir(context, model))
                File(dir(context, model), ".complete").createNewFile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install ${model.id}", e)
                dir(context, model).deleteRecursively()
            } finally {
                zip.delete()
                context.getSystemService(DownloadManager::class.java).remove(downloadId)
                prefs(context).edit().remove(model.id).apply()
                _changes.update { it + 1 }
            }
        }

    /** Unpacks [zip] into [target], dropping the archive's top-level directory. */
    private fun unzip(zip: File, target: File) {
        target.deleteRecursively()
        target.mkdirs()
        val canonical = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val out = File(target, name)
                require(out.canonicalPath.startsWith(canonical)) { "Bad zip entry ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}
