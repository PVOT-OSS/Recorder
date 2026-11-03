/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.prauga.recorder.R
import org.prauga.recorder.repository.RecordingsRepository

/** Runs queued post-recording analysis (transcription now, more later) in the foreground. */
class TranscriptionService : LifecycleService() {
    private val queue = Channel<Uri>(Channel.UNLIMITED)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var pending = 0

    override fun onCreate() {
        super.onCreate()
        if (notificationManager.getNotificationChannel(CHANNEL) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.transcription_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(null, 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        lifecycleScope.launch {
            for (uri in queue) {
                process(uri)
                if (--pending == 0) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val uri = intent?.data
        if (uri != null) {
            pending++
            queue.trySend(uri)
        } else if (pending == 0) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun process(uri: Uri) {
        val recording = RecordingsRepository.recording(this, uri) ?: return
        val title = recording.title.substringBeforeLast('.')
        _progress.update { it + (recording.id to 0f) }
        try {
            Analysis.run(this, recording) { fraction ->
                _progress.update { it + (recording.id to fraction) }
                notificationManager.notify(NOTIFICATION_ID, notification(title, fraction))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis of $uri failed", e)
        } finally {
            _progress.update { it - recording.id }
        }
    }

    private fun notification(title: String?, fraction: Float): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notes)
            .setContentTitle(getString(R.string.transcription_running))
            .setContentText(title)
            .setProgress(100, (fraction * 100).toInt(), title == null)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, org.prauga.recorder.ListActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    companion object {
        private const val TAG = "TranscriptionService"
        private const val CHANNEL = "transcription"
        private const val NOTIFICATION_ID = 70

        private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())

        /** Recording id → progress of the analysis currently running or queued. */
        val progress = _progress.asStateFlow()

        fun enqueue(context: Context, uri: Uri) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscriptionService::class.java).setData(uri),
            )
        }
    }
}
