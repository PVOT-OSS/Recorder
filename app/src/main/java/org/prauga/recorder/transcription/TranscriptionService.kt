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
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
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
    private val queue = Channel<Pair<Uri, Boolean>>(Channel.UNLIMITED)
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
        // Platform call on purpose: ServiceCompat drops types newer than the core library knows,
        // which turns mediaProcessing into "none" and crashes the service
        startForeground(
            NOTIFICATION_ID,
            notification(null, 0f),
            // On-device processing of a recording: mediaProcessing where it exists (Android 15+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        lifecycleScope.launch {
            for ((uri, force) in queue) {
                process(uri, force)
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
            queue.trySend(uri to intent.getBooleanExtra(EXTRA_FORCE, false))
        } else if (pending == 0) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun process(uri: Uri, force: Boolean) {
        val recording = RecordingsRepository.recording(this, uri) ?: return
        val title = recording.title.substringBeforeLast('.')
        _progress.update { it + (recording.id to 0f) }
        try {
            Analysis.run(this, recording, force) { fraction ->
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
                    Intent(this, org.prauga.recorder.RecorderActivity::class.java),
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

        private const val EXTRA_FORCE = "force"

        fun enqueue(context: Context, uri: Uri, force: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscriptionService::class.java)
                    .setData(uri)
                    .putExtra(EXTRA_FORCE, force),
            )
        }
    }
}
