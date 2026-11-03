/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.reflect.safeCast
import kotlinx.coroutines.launch
import org.prauga.recorder.models.UiStatus
import org.prauga.recorder.service.SoundRecorderService
import org.prauga.recorder.ui.WaveFormView
import org.prauga.recorder.utils.LocationHelper
import org.prauga.recorder.utils.OnBoardingHelper
import org.prauga.recorder.utils.PermissionManager
import org.prauga.recorder.utils.PreferencesManager
import org.prauga.recorder.utils.Utils
import org.prauga.recorder.viewmodels.RecordingsViewModel

class RecorderActivity : AppCompatActivity(R.layout.activity_main) {
    // View models
    private val model: RecordingsViewModel by viewModels()

    // Views
    private val contentView by lazy { findViewById<View>(android.R.id.content) }
    private val elapsedTimeText by lazy { findViewById<TextView>(R.id.elapsedTimeTextView) }
    private val floatingActionButton by lazy {
        findViewById<FloatingActionButton>(R.id.floatingActionButton)
    }
    private val openSoundListImageView by lazy {
        findViewById<ImageView>(R.id.openSoundListImageView)
    }
    private val pauseResumeImageView by lazy { findViewById<ImageView>(R.id.pauseResumeImageView) }
    private val recordingWaveFormView by lazy {
        findViewById<WaveFormView>(R.id.recordingWaveFormView)
    }
    private val settingsImageView by lazy { findViewById<ImageView>(R.id.settingsImageView) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.titleTextView) }
    private val liveTranscriptTextView by lazy {
        findViewById<TextView>(R.id.liveTranscriptTextView)
    }

    private val locationHelper by lazy { LocationHelper(this) }
    private val permissionManager by lazy { PermissionManager(this) }
    private val preferencesManager by lazy { PreferencesManager(this) }

    private var returnAudio = false
    private var hasRecordedAudio = false

    private var uiStatus = UiStatus.READY

    private val messenger =
        Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        SoundRecorderService.MSG_UI_STATUS -> setStatus(msg.obj as UiStatus)
                        SoundRecorderService.MSG_SOUND_AMPLITUDE ->
                            setVisualizerAmplitude(msg.obj as Int)
                        SoundRecorderService.MSG_TIME_ELAPSED -> setElapsedTime(msg.obj as Long)
                        SoundRecorderService.MSG_TRANSCRIPT -> setLiveTranscript(msg.obj as String)
                        else -> super.handleMessage(msg)
                    }
                }
            }
        )

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val messengerService = Messenger(service)
                this@RecorderActivity.service = messengerService
                try {
                    val msg = Message.obtain(null, SoundRecorderService.MSG_REGISTER_CLIENT)
                    msg.replyTo = messenger
                    messengerService.send(msg)
                } catch (e: RemoteException) {
                    // In this case the service has crashed before we could even
                    // do anything with it; we can count on soon being
                    // disconnected (and then reconnected if it can be restarted)
                    // so there is no need to do anything here.
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
    private var service: Messenger? = null
    private var isServiceBound = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        floatingActionButton.setOnClickListener { toggleSoundRecorder() }
        pauseResumeImageView.setOnClickListener { togglePause() }
        openSoundListImageView.setOnClickListener { openList() }
        settingsImageView.setOnClickListener { openSettings() }

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            contentView.updatePadding(bottom = insets.bottom)

            windowInsets
        }

        if (MediaStore.Audio.Media.RECORD_SOUND_ACTION == intent.action) {
            returnAudio = true
            openSoundListImageView.isVisible = false
            settingsImageView.isVisible = false
        }

        pendingAction = intent.action

        doBindService()

        OnBoardingHelper.onBoardList(this, openSoundListImageView)
        OnBoardingHelper.onBoardSettings(this, settingsImageView)
    }

    public override fun onDestroy() {
        doUnbindService()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PermissionManager.REQUEST_CODE) {
            if (permissionManager.hasEssentialPermissions()) {
                toggleAfterPermissionRequest()
            } else {
                permissionManager.onEssentialPermissionsDenied()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingAction = intent.action
        if (service != null) applyPendingAction()
    }

    /** START/STOP from the tile, widget, shortcut or automation apps, applied once connected. */
    private fun applyPendingAction() {
        val action = pendingAction ?: return
        pendingAction = null
        when {
            action == ACTION_START_RECORDING && uiStatus == UiStatus.READY -> toggleSoundRecorder()
            action == ACTION_STOP_RECORDING && uiStatus != UiStatus.READY -> toggleSoundRecorder()
            action == ACTION_TOGGLE_RECORDING -> toggleSoundRecorder()
        }
    }

    private var pendingAction: String? = null

    private fun setStatus(status: UiStatus) {
        applyUiStatus(status)
        applyPendingAction()

        if (returnAudio && hasRecordedAudio) {
            Utils.cancelShareNotification(this)
            promptUser()
        }
    }

    private fun setVisualizerAmplitude(amplitude: Int) {
        recordingWaveFormView.post { recordingWaveFormView.setAmplitude(amplitude) }
    }

    private fun setLiveTranscript(text: String) {
        liveTranscriptTextView.isVisible = text.isNotBlank() && uiStatus != UiStatus.READY
        // Keep the tail: the view shows the most recent words at the bottom
        liveTranscriptTextView.text = text.takeLast(LIVE_TRANSCRIPT_CHARS)
    }

    private fun setElapsedTime(seconds: Long) {
        elapsedTimeText.post { elapsedTimeText.text = DateUtils.formatElapsedTime(seconds) }
    }

    private fun toggleAfterPermissionRequest() {
        Handler(Looper.getMainLooper()).postDelayed({ toggleSoundRecorder() }, 500)
    }

    private fun toggleSoundRecorder() {
        if (permissionManager.requestEssentialPermissions()) {
            return
        }

        if (uiStatus == UiStatus.READY) {
            // Start
            startService(
                Intent(this, SoundRecorderService::class.java)
                    .setAction(SoundRecorderService.ACTION_START)
                    .putExtra(SoundRecorderService.EXTRA_FILE_NAME, newRecordFileName)
            )
        } else {
            // Stop
            startService(
                Intent(this, SoundRecorderService::class.java)
                    .setAction(SoundRecorderService.ACTION_STOP)
            )
            hasRecordedAudio = true
        }
    }

    private fun togglePause() {
        when (uiStatus) {
            UiStatus.RECORDING ->
                startService(
                    Intent(this, SoundRecorderService::class.java)
                        .setAction(SoundRecorderService.ACTION_PAUSE)
                )

            UiStatus.PAUSED ->
                startService(
                    Intent(this, SoundRecorderService::class.java)
                        .setAction(SoundRecorderService.ACTION_RESUME)
                )

            UiStatus.READY -> {}
        }
    }

    private fun applyUiStatus(status: UiStatus) {
        uiStatus = status
        if (UiStatus.READY == status) {
            liveTranscriptTextView.isVisible = false
            liveTranscriptTextView.text = null
            titleTextView.text = getString(R.string.main_sound_action)
            floatingActionButton.setImageResource(R.drawable.ic_mic)
            floatingActionButton.contentDescription = getString(R.string.main_sound_action)
            elapsedTimeText.isVisible = false
            recordingWaveFormView.isVisible = false
            pauseResumeImageView.isVisible = false
        } else {
            floatingActionButton.setImageResource(R.drawable.ic_stop)
            floatingActionButton.contentDescription = getString(R.string.stop)
            elapsedTimeText.isVisible = true
            recordingWaveFormView.isVisible = true
            recordingWaveFormView.setAmplitude(0)
            pauseResumeImageView.isVisible = true
            val prDrawable: Drawable?
            if (UiStatus.PAUSED == status) {
                titleTextView.text = getString(R.string.sound_recording_title_paused)
                pauseResumeImageView.contentDescription = getString(R.string.resume)
                prDrawable = ContextCompat.getDrawable(this, R.drawable.avd_play_to_pause)
            } else {
                titleTextView.text = getString(R.string.sound_recording_title_working)
                pauseResumeImageView.contentDescription = getString(R.string.pause)
                prDrawable = ContextCompat.getDrawable(this, R.drawable.avd_pause_to_play)
            }
            pauseResumeImageView.tooltipText = pauseResumeImageView.contentDescription
            pauseResumeImageView.setImageDrawable(prDrawable)
            AnimatedVectorDrawable::class.safeCast(prDrawable)?.start()
        }
    }

    private fun doBindService() {
        bindService(
            Intent(this, SoundRecorderService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE,
        )
        isServiceBound = true
    }

    private fun doUnbindService() {
        service?.let {
            try {
                val msg = Message.obtain(null, SoundRecorderService.MSG_UNREGISTER_CLIENT)
                msg.replyTo = messenger
                it.send(msg)
            } catch (e: RemoteException) {
                // There is nothing special we need to do if the service
                // has crashed.
            }
        }

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun openSettings() {
        val intent =
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_IS_RECORDING, uiStatus != UiStatus.READY)
        startActivity(intent)
    }

    private fun openList() {
        startActivity(Intent(this, ListActivity::class.java))
    }

    private fun confirmLastResult() {
        val resultIntent =
            Intent()
                .setData(preferencesManager.lastItemUri)
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun discardLastResult() {
        preferencesManager.lastItemUri?.let {
            lifecycleScope.launch {
                model.deleteRecordings(it)
                preferencesManager.lastItemUri = null
            }
        }
        cancelResult(true)
    }

    private fun cancelResult(quit: Boolean) {
        setResult(RESULT_CANCELED, Intent())
        hasRecordedAudio = false
        if (quit) {
            finish()
        }
    }

    private fun promptUser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_result_title)
            .setMessage(R.string.confirm_result_message)
            .setPositiveButton(R.string.confirm) { _: DialogInterface?, _: Int ->
                confirmLastResult()
            }
            .setNegativeButton(R.string.discard) { _: DialogInterface?, _: Int ->
                discardLastResult()
            }
            .setNeutralButton(R.string.record_again) { _: DialogInterface?, _: Int ->
                cancelResult(false)
            }
            .setCancelable(false)
            .show()
    }

    private val newRecordFileName: String
        get() {
            val tag = locationHelper.currentLocationName ?: FILE_NAME_FALLBACK
            val formatter =
                DateTimeFormatterBuilder()
                    .append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral(' ')
                    .append(DateTimeFormatter.ISO_LOCAL_TIME)
                    .toFormatter(Locale.getDefault())
            val now = LocalDateTime.now()
            return String.format(
                FILE_NAME_BASE,
                tag,
                formatter.format(now.truncatedTo(ChronoUnit.SECONDS)),
            ) + ".%1\$s"
        }

    companion object {
        const val ACTION_START_RECORDING = "org.prauga.recorder.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "org.prauga.recorder.action.STOP_RECORDING"
        const val ACTION_TOGGLE_RECORDING = "org.prauga.recorder.action.TOGGLE_RECORDING"

        private const val LIVE_TRANSCRIPT_CHARS = 280
        private const val FILE_NAME_BASE = "%1\$s (%2\$s)"
        private const val FILE_NAME_FALLBACK = "Sound record"
    }
}
