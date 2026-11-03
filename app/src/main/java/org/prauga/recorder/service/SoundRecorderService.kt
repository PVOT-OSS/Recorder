/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.service

import android.Manifest.permission
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import android.text.format.DateUtils
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.launch
import org.prauga.recorder.ListActivity
import org.prauga.recorder.PlayerActivity
import org.prauga.recorder.R
import org.prauga.recorder.RecorderActivity
import org.prauga.recorder.audio.EngineRecorder
import org.prauga.recorder.models.UiStatus
import org.prauga.recorder.repository.RecordingsRepository
import org.prauga.recorder.transcription.LiveTranscriptionSink
import org.prauga.recorder.transcription.ModelManager
import org.prauga.recorder.transcription.SpeechSession
import org.prauga.recorder.transcription.Transcriber
import org.prauga.recorder.transcription.TranscriptionService
import org.prauga.recorder.utils.LocationHelper
import org.prauga.recorder.utils.PreferencesManager
import org.prauga.recorder.utils.RecordIntentHelper
import org.prauga.recorder.widget.RecorderWidget

class SoundRecorderService : LifecycleService() {
    // System services
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private val preferencesManager by lazy { PreferencesManager(this) }

    private val lock = Any()

    @GuardedBy("lock") private val clients = HashMap<IBinder, RecorderClient>()

    private val handler: Handler =
        object : Handler(Looper.myLooper()!!) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_REGISTER_CLIENT ->
                        registerClient(RecorderClient.of(msg.replyTo, msg.replyTo.binder))

                    MSG_UNREGISTER_CLIENT ->
                        synchronized(lock) { unregisterClientLocked(msg.replyTo.binder) }

                    else -> super.handleMessage(msg)
                }
            }
        }
    private val messenger = Messenger(handler)
    private var recorder: SoundRecording? = null
    private var recordFile: File? = null
    private var liveTranscription: LiveTranscriptionSink? = null
    private var recordLocation: android.location.Location? = null
    private val locationHelper by lazy { LocationHelper(this) }
    private var amplitudeTimer: Timer? = null
    private var elapsedTimeTimer: Timer? = null
    private var isPaused = false
    private var elapsedTime = 0L

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var pausedForCall = false

    /** Pause while a call holds the audio path; resume if we were the ones who paused. */
    private val modeListener =
        AudioManager.OnModeChangedListener { mode ->
            val inCall =
                mode == AudioManager.MODE_IN_CALL ||
                    mode == AudioManager.MODE_IN_COMMUNICATION ||
                    mode == AudioManager.MODE_CALL_SCREENING ||
                    mode == AudioManager.MODE_CALL_REDIRECT ||
                    mode == AudioManager.MODE_COMMUNICATION_REDIRECT
            if (inCall && recorder != null && !isPaused) {
                if (pauseRecording()) pausedForCall = true
            } else if (!inCall && pausedForCall) {
                pausedForCall = false
                resumeRecording()
            }
        }

    private var micSilenced = false

    private val recordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val session = (recorder as? EngineRecorder)?.audioSessionId ?: return
                val silenced =
                    configs.firstOrNull { it.clientAudioSessionId == session }?.isClientSilenced
                        ?: false
                if (silenced != micSilenced) {
                    micSilenced = silenced
                    if (recorder != null) {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            createRecordingNotification(elapsedTime),
                        )
                    }
                }
            }
        }

    private val shutdownReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stopRecording()
            }
        }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)

        return messenger.binder
    }

    override fun onCreate() {
        super.onCreate()

        ContextCompat.registerReceiver(
            this,
            shutdownReceiver,
            IntentFilter(Intent.ACTION_SHUTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        audioManager.addOnModeChangedListener(mainExecutor, modeListener)
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)

        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            createNotificationChannel()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(shutdownReceiver)
        audioManager.removeOnModeChangedListener(modeListener)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)

        stopTimers()

        unregisterClients()

        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return intent?.let {
            when (it.action) {
                ACTION_START ->
                    it.getStringExtra(EXTRA_FILE_NAME)?.let { fileName ->
                        if (startRecording(fileName)) {
                            START_STICKY
                        } else {
                            START_NOT_STICKY
                        }
                    } ?: START_NOT_STICKY

                ACTION_STOP -> if (stopRecording()) START_STICKY else START_NOT_STICKY

                ACTION_PAUSE -> if (pauseRecording()) START_STICKY else START_NOT_STICKY

                ACTION_RESUME -> if (resumeRecording()) START_STICKY else START_NOT_STICKY

                else -> START_NOT_STICKY
            }
        } ?: START_NOT_STICKY
    }

    private fun startRecording(fileName: String): Boolean {
        if (checkSelfPermission(permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing permission to record audio")
            return false
        }

        recorder =
            EngineRecorder(
                preferencesManager.qualityConfig,
                preferredInputDevice(),
                preferencesManager.clearVoice,
            )
        liveTranscription =
            if (preferencesManager.transcribe) {
                SpeechSession.open(this)?.let { session ->
                    LiveTranscriptionSink(session) { text ->
                        handler.post { notifyClients(MSG_TRANSCRIPT, text) }
                    }
                }
            } else null
        liveTranscription?.let { (recorder as EngineRecorder).extraSinks.add(it) }

        return recorder?.let { recorder ->
            val file =
                createNewAudioFile(fileName, recorder.fileExtension)
                    ?: run {
                        Log.e(TAG, "Failed to prepare output file")
                        return@let false
                    }

            this.recordFile = file
            recordLocation = locationHelper.currentLocation

            isPaused = false
            pausedForCall = false
            elapsedTime = 0
            try {
                recorder.startRecording(file)
            } catch (e: IOException) {
                Log.e(TAG, "Error while starting the recorder", e)
                return@let false
            }
            notifyStatus(UiStatus.RECORDING)
            notifyElapsedTime(0)
            startTimers()
            startForeground(NOTIFICATION_ID, createRecordingNotification(0))

            true
        } ?: false
    }

    private fun preferredInputDevice(): AudioDeviceInfo? {
        val id = preferencesManager.inputDeviceId
        if (id == 0) {
            return null
        }
        return getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == id }
    }

    private fun stopRecording(): Boolean {
        val recorder =
            recorder
                ?: run {
                    Log.e(TAG, "Trying to stop null recorder")
                    return false
                }

        if (isPaused) {
            isPaused = false
            recorder.resumeRecording()
        }

        stopTimers()

        val success = recorder.stopRecording()

        return recordFile
            ?.takeIf { success }
            ?.let {
                lifecycleScope.launch {
                    RecordingsRepository.addRecordingToContentProvider(
                            this@SoundRecorderService,
                            it,
                            recorder.mimeType,
                            preferencesManager.qualityPreset.key,
                        )
                        ?.also { uri ->
                            saveLocation(uri, it.name)
                            analyse(uri, it.name)
                            onRecordCompleted(uri)
                        } ?: run { Log.e(TAG, "Failed to save recording") }
                }

                true
            }
            ?: run {
                onRecordFailed()
                false
            }
    }

    /** Why recording must stop now (low storage/battery, overheating), or null. */
    private fun guardReason(): Int? {
        recordFile?.parentFile?.let {
            if (it.usableSpace < MIN_FREE_BYTES) return R.string.guard_storage
        }
        val battery = getSystemService(BatteryManager::class.java)
        if (
            !battery.isCharging &&
                battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in
                    0..MIN_BATTERY_PERCENT
        ) {
            return R.string.guard_battery
        }
        if (
            getSystemService(PowerManager::class.java).currentThermalStatus >=
                PowerManager.THERMAL_STATUS_CRITICAL
        ) {
            return R.string.guard_thermal
        }
        return null
    }

    private fun stopForGuard(reason: Int) {
        if (recorder == null) return
        Log.w(TAG, "Stopping: ${getString(reason)}")
        stopRecording()
        notificationManager.notify(
            GUARD_NOTIFICATION_ID,
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.guard_title))
                .setContentText(getString(reason))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Saves the live transcript, then queues the remaining analysis (or the transcription). */
    private fun analyse(uri: String, title: String) {
        val live = liveTranscription
        liveTranscription = null
        val contentUri = Uri.parse(uri)
        val utterances = live?.utterances.orEmpty()
        if (utterances.isNotEmpty()) {
            org.prauga.recorder.RecorderApplication.appScope.launch {
                Transcriber.save(
                    applicationContext,
                    android.content.ContentUris.parseId(contentUri),
                    title,
                    utterances,
                )
                TranscriptionService.enqueue(applicationContext, contentUri)
            }
        } else if (preferencesManager.transcribe || ModelManager.taggerFile(this) != null) {
            TranscriptionService.enqueue(this, contentUri)
        }
    }

    private fun saveLocation(uri: String, title: String) {
        val location = recordLocation ?: return
        recordLocation = null
        org.prauga.recorder.RecorderApplication.appScope.launch {
            RecordingsRepository.setLocation(
                applicationContext,
                android.content.ContentUris.parseId(Uri.parse(uri)),
                location.latitude,
                location.longitude,
                locationHelper.describe(location),
                title,
            )
        }
    }

    private fun pauseRecording(): Boolean {
        if (isPaused) {
            Log.w(TAG, "Pausing already paused recording")
            return false
        }

        return recorder?.let {
            if (it.pauseRecording()) {
                isPaused = true
                stopTimers()
                notifyCurrentSoundAmplitude(0)
                notifyStatus(UiStatus.PAUSED)
                notificationManager.notify(
                    NOTIFICATION_ID,
                    createRecordingNotification(elapsedTime),
                )

                true
            } else {
                Log.e(TAG, "Failed to pause the recorder")
                false
            }
        }
            ?: run {
                Log.e(TAG, "Pausing null recorder")
                false
            }
    }

    private fun resumeRecording(): Boolean {
        if (!isPaused) {
            Log.w(TAG, "Resuming non-paused recording")
            return false
        }

        return recorder?.let {
            if (it.resumeRecording()) {
                isPaused = false
                startTimers()
                notifyStatus(UiStatus.RECORDING)
                notificationManager.notify(
                    NOTIFICATION_ID,
                    createRecordingNotification(elapsedTime),
                )

                true
            } else {
                Log.e(TAG, "Failed to resume the recorder")
                false
            }
        }
            ?: run {
                Log.e(TAG, "Resuming null recorder")
                false
            }
    }

    private fun onRecordCompleted(uri: String?) {
        notifyStatus(UiStatus.READY)
        stopForeground(STOP_FOREGROUND_REMOVE)

        uri?.let {
            createShareNotification(it)?.let { shareNotification ->
                notificationManager.notify(NOTIFICATION_ID, shareNotification)
            }
        }

        recorder = null
    }

    private fun onRecordFailed() {
        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyStatus(UiStatus.READY)
    }

    private fun createNewAudioFile(fileName: String, extension: String): File? {
        val recordingDir =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
            } else {
                getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.resolve(LEGACY_MUSIC_DIR)
            } ?: throw Exception("Null external files dir")

        val file = recordingDir.resolve(String.format(fileName, extension))

        if (!recordingDir.exists()) {
            try {
                recordingDir.mkdirs()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create parent directories for output")
                return null
            }
        }

        return file
    }

    /* Timers */
    private fun startTimers() {
        startElapsedTimeTimer()
        startAmplitudeTimer()
    }

    private fun startElapsedTimeTimer() {
        elapsedTimeTimer =
            Timer().apply {
                scheduleAtFixedRate(
                    object : TimerTask() {
                        override fun run() {
                            guardReason()?.let { reason ->
                                handler.post { stopForGuard(reason) }
                                return
                            }
                            val newElapsedTime = ++elapsedTime
                            notifyElapsedTime(newElapsedTime)
                            val notification = createRecordingNotification(newElapsedTime)
                            notificationManager.notify(NOTIFICATION_ID, notification)
                        }
                    },
                    1000,
                    1000,
                )
            }
    }

    private fun startAmplitudeTimer() {
        amplitudeTimer =
            Timer().also {
                it.schedule(
                    object : TimerTask() {
                        override fun run() {
                            recorder?.currentAmplitude?.let { currentAmplitude ->
                                notifyCurrentSoundAmplitude(currentAmplitude)
                            }
                        }
                    },
                    0,
                    350,
                )
            }
    }

    private fun stopTimers() {
        elapsedTimeTimer?.let {
            it.cancel()
            it.purge()
        }
        elapsedTimeTimer = null

        amplitudeTimer?.let {
            it.cancel()
            it.purge()
        }
        amplitudeTimer = null
    }

    /* Clients */
    private fun registerClient(client: RecorderClient) {
        synchronized(lock) {
            if (unregisterClientLocked(client.token)) {
                Log.i(TAG, "Client was already registered, override it.")
            }
            clients.put(client.token, client)
        }
        try {
            client.token.linkToDeath(RecorderClientDeathRecipient(this, client), 0)

            // Notify about the current status
            val currentStatus =
                if (recorder == null) {
                    UiStatus.READY
                } else if (isPaused) {
                    UiStatus.PAUSED
                } else {
                    UiStatus.RECORDING
                }
            client.send(handler.obtainMessage(MSG_UI_STATUS, currentStatus))

            // Also notify about elapsed time
            if (currentStatus != UiStatus.READY) {
                client.send(handler.obtainMessage(MSG_TIME_ELAPSED, elapsedTime))
            }
        } catch (ignored: RemoteException) {
            // Already gone
        }
    }

    private fun unregisterClients() {
        synchronized(lock) {
            for (client in clients.values) {
                client.deathRecipient?.let { client.token.unlinkToDeath(it, 0) }
            }
        }
    }

    @GuardedBy("mLock")
    private fun unregisterClientLocked(token: IBinder): Boolean {
        val client = clients.remove(token) ?: return false
        client.deathRecipient?.let { token.unlinkToDeath(it, 0) }

        return true
    }

    private fun notifyStatus(newStatus: UiStatus) {
        val recording = newStatus != UiStatus.READY
        if (recording != isRecording) {
            isRecording = recording
            RecordTileService.refresh(this)
            RecorderWidget.refresh(this)
        }
        notifyClients(MSG_UI_STATUS, newStatus)
    }

    private fun notifyCurrentSoundAmplitude(amplitude: Int) {
        notifyClients(MSG_SOUND_AMPLITUDE, amplitude)
    }

    private fun notifyElapsedTime(seconds: Long) {
        notifyClients(MSG_TIME_ELAPSED, seconds)
    }

    private fun notifyClients(what: Int, obj: Any) {
        val clients = synchronized(lock) { this.clients.values }
        for (client in clients) {
            client.send(handler.obtainMessage(what, obj))
        }
    }

    /* Notifications */
    private fun createNotificationChannel() {
        val name = getString(R.string.sound_channel_title)
        val description = getString(R.string.sound_channel_desc)

        val notificationChannel =
            NotificationChannel(NOTIFICATION_CHANNEL, name, NotificationManager.IMPORTANCE_LOW)
                .apply { this.description = description }

        notificationManager.createNotificationChannel(notificationChannel)
    }

    private fun createRecordingNotification(elapsedTime: Long): Notification {
        val intent = Intent(this, RecorderActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val stopPIntent =
            PendingIntent.getService(
                this,
                0,
                Intent(this, SoundRecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val duration = DateUtils.formatElapsedTime(elapsedTime)
        val nb =
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
                .setOngoing(true)
                .setContentText(
                    if (micSilenced) getString(R.string.mic_silenced)
                    else getString(R.string.sound_notification_message, duration)
                )
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pi)
        if (isPaused) {
            val resumePIntent =
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, SoundRecorderService::class.java).setAction(ACTION_RESUME),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            nb.setContentTitle(getString(R.string.sound_recording_title_paused))
            nb.addAction(R.drawable.ic_play_arrow, getString(R.string.resume), resumePIntent)
        } else {
            val pausePIntent =
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, SoundRecorderService::class.java).setAction(ACTION_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            nb.setContentTitle(getString(R.string.sound_recording_title_working))
            nb.addAction(R.drawable.ic_pause, getString(R.string.pause), pausePIntent)
        }
        nb.addAction(R.drawable.ic_stop, getString(R.string.stop), stopPIntent)
        return nb.build()
    }

    private fun createShareNotification(uri: String): Notification? {
        val fileUri = Uri.parse(uri)

        preferencesManager.lastItemUri = fileUri

        val mimeType =
            recorder?.mimeType
                ?: run {
                    Log.e(TAG, "No recorder found while creating the share notification")
                    return null
                }

        val intent = Intent(this, ListActivity::class.java)

        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val playPIntent =
            PendingIntent.getActivity(
                this,
                0,
                PlayerActivity.intent(this, fileUri),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val sharePIntent =
            PendingIntent.getActivity(
                this,
                0,
                RecordIntentHelper.getShareIntent(fileUri, mimeType),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val deletePIntent =
            PendingIntent.getActivity(
                this,
                0,
                RecordIntentHelper.getDeleteIntent(this),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val duration = DateUtils.formatElapsedTime(elapsedTime)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setWhen(System.currentTimeMillis())
            .setContentTitle(getString(R.string.sound_notification_title))
            .setContentText(getString(R.string.sound_notification_message, duration))
            .setSmallIcon(R.drawable.ic_mic)
            .addAction(R.drawable.ic_play_arrow, getString(R.string.play), playPIntent)
            .addAction(R.drawable.ic_share, getString(R.string.share), sharePIntent)
            .addAction(R.drawable.ic_delete, getString(R.string.delete), deletePIntent)
            .setContentIntent(pi)
            .build()
    }

    class RecorderClient private constructor(private val messenger: Messenger, val token: IBinder) {
        var deathRecipient: DeathRecipient? = null

        fun send(message: Message?) {
            try {
                messenger.send(message)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to send message", e)
            }
        }

        companion object {
            fun of(messenger: Messenger, token: IBinder) = RecorderClient(messenger, token)
        }
    }

    private class RecorderClientDeathRecipient(
        service: SoundRecorderService,
        private val client: RecorderClient,
    ) : DeathRecipient {
        private val serviceRef: WeakReference<SoundRecorderService>

        init {
            serviceRef = WeakReference(service)
            client.deathRecipient = this
        }

        override fun binderDied() {
            val service = serviceRef.get() ?: return
            synchronized(service.lock) { service.unregisterClientLocked(client.token) }
        }
    }

    companion object {
        private const val TAG = "SoundRecorderService"

        @Volatile
        var isRecording = false
            private set

        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"

        const val MSG_REGISTER_CLIENT = 0
        const val MSG_UNREGISTER_CLIENT = 1
        const val MSG_UI_STATUS = 2
        const val MSG_SOUND_AMPLITUDE = 3
        const val MSG_TIME_ELAPSED = 4
        const val MSG_TRANSCRIPT = 5

        const val EXTRA_FILE_NAME = "extra_filename"
        private const val LEGACY_MUSIC_DIR = "Sound records"

        const val NOTIFICATION_ID = 60
        private const val GUARD_NOTIFICATION_ID = 61
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private const val MIN_BATTERY_PERCENT = 3
        private const val NOTIFICATION_CHANNEL = "soundrecorder_notification_channel"
    }
}
