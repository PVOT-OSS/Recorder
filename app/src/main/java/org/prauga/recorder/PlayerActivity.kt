/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.text.DateFormat
import java.text.DecimalFormat
import kotlinx.coroutines.launch
import org.prauga.recorder.audio.AudioEditor
import org.prauga.recorder.audio.WaveformExtractor
import org.prauga.recorder.models.Recording
import org.prauga.recorder.playback.PlaybackService
import org.prauga.recorder.repository.RecordingsRepository
import org.prauga.recorder.ui.LabelsDialog
import org.prauga.recorder.ui.PlaybackWaveformView
import org.prauga.recorder.ui.RenameDialog
import org.prauga.recorder.utils.PreferencesManager
import org.prauga.recorder.utils.RecordIntentHelper
import org.prauga.recorder.viewmodels.RecordingsViewModel

class PlayerActivity : AppCompatActivity(R.layout.activity_player) {
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }
    private val subtitleTextView by lazy { findViewById<TextView>(R.id.subtitleTextView) }
    val waveformView by lazy { findViewById<PlaybackWaveformView>(R.id.waveformView) }
    private val positionTextView by lazy { findViewById<TextView>(R.id.positionTextView) }
    private val durationTextView by lazy { findViewById<TextView>(R.id.durationTextView) }
    private val playPauseButton by lazy { findViewById<FloatingActionButton>(R.id.playPauseButton) }
    private val speedButton by lazy { findViewById<Button>(R.id.speedButton) }
    private val skipSilenceButton by lazy { findViewById<Button>(R.id.skipSilenceButton) }

    private val preferences by lazy { PreferencesManager(this) }
    private val model by lazy { RecordingsViewModel(application) }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set

    var recording: Recording? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val ticker =
        object : Runnable {
            override fun run() {
                updatePosition()
                handler.postDelayed(this, TICK_MS)
            }
        }

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPause()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPause()
                updatePosition()
            }
        }

    /** Called with the playback position on every tick; used by the transcript pane. */
    var onPositionChanged: ((Long) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = ""

        val content = findViewById<View>(R.id.playerContent)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        playPauseButton.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        findViewById<ImageButton>(R.id.rewindButton).setOnClickListener { controller?.seekBack() }
        findViewById<ImageButton>(R.id.forwardButton).setOnClickListener {
            controller?.seekForward()
        }
        speedButton.setOnClickListener { showSpeedMenu() }
        skipSilenceButton.setOnClickListener {
            preferences.skipSilence = skipSilenceButton.isSelected.not()
            applySkipSilence()
        }
        waveformView.listener =
            object : PlaybackWaveformView.Listener {
                override fun onScrub(positionMs: Long) {
                    positionTextView.text = format(positionMs)
                }

                override fun onSeek(positionMs: Long) {
                    controller?.seekTo(positionMs)
                }

                override fun onSelectionChanged(startMs: Long, endMs: Long) {
                    onSelectionChangedListener?.invoke(startMs, endMs)
                }
            }

        findViewById<Button>(R.id.trimButton).setOnClickListener { cut(AudioEditor.Mode.KEEP) }
        findViewById<Button>(R.id.removeButton).setOnClickListener { cut(AudioEditor.Mode.REMOVE) }
        onSelectionChangedListener = { _, _ -> updateSelectionText() }

        val uri = intent.data ?: intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val recording = RecordingsRepository.recording(this@PlayerActivity, uri)
            if (recording == null) {
                Toast.makeText(this@PlayerActivity, R.string.player_not_found, Toast.LENGTH_SHORT)
                    .show()
                finish()
                return@launch
            }
            bind(recording)
            waveformView.setWaveform(
                WaveformExtractor.load(this@PlayerActivity, recording.id, recording.uri),
                recording.duration,
            )
            connect(recording)
            onRecordingLoaded?.invoke(recording)
        }
    }

    var onRecordingLoaded: ((Recording) -> Unit)? = null
    var onSelectionChangedListener: ((Long, Long) -> Unit)? = null

    private fun bind(recording: Recording) {
        this.recording = recording
        title = recording.title.substringBeforeLast('.')
        supportActionBar?.title = title
        val date =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(recording.dateAdded)
        subtitleTextView.text = listOfNotNull(date, recording.locationName).joinToString(" · ")
        durationTextView.text = format(recording.duration)
        invalidateOptionsMenu()
    }

    private fun connect(recording: Recording) {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = future.get()
                this.controller = controller
                controller.addListener(playerListener)
                if (controller.currentMediaItem?.mediaId != recording.uri.toString()) {
                    controller.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(recording.uri.toString())
                            .setUri(recording.uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(recording.title.substringBeforeLast('.'))
                                    .setArtist(getString(R.string.app_name))
                                    .build()
                            )
                            .build()
                    )
                    controller.prepare()
                    controller.play()
                }
                controller.setPlaybackSpeed(preferences.playbackSpeed)
                applySkipSilence()
                updatePlayPause()
                handler.post(ticker)
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun applySkipSilence() {
        val enabled = preferences.skipSilence
        skipSilenceButton.isSelected = enabled
        (skipSilenceButton as? com.google.android.material.button.MaterialButton)?.isChecked =
            enabled
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_SKIP_SILENCE, Bundle.EMPTY),
            bundleOf(PlaybackService.EXTRA_ENABLED to enabled),
        )
    }

    private fun showSpeedMenu() {
        val popup = PopupMenu(this, speedButton)
        SPEEDS.forEachIndexed { i, speed ->
            popup.menu.add(
                0,
                i,
                i,
                getString(R.string.player_speed_value, speedFormat.format(speed)),
            )
        }
        popup.setOnMenuItemClickListener {
            val speed = SPEEDS[it.itemId]
            preferences.playbackSpeed = speed
            controller?.setPlaybackSpeed(speed)
            updateSpeed()
            true
        }
        popup.show()
    }

    private fun updateSpeed() {
        speedButton.text =
            getString(R.string.player_speed_value, speedFormat.format(preferences.playbackSpeed))
    }

    private fun updatePlayPause() {
        val playing = controller?.isPlaying == true
        playPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        playPauseButton.contentDescription =
            getString(if (playing) R.string.pause else R.string.play)
    }

    private fun updatePosition() {
        val controller = controller ?: return
        val position = controller.currentPosition
        waveformView.positionMs = position
        positionTextView.text = format(position)
        onPositionChanged?.invoke(position)
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        updatePosition()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != this.intent.data) {
            controller?.clearMediaItems()
            setIntent(intent)
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        updateSpeed()
        if (controller != null) handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_player, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val favourite = recording?.favourite == true
        menu.findItem(R.id.action_favourite)?.apply {
            setIcon(if (favourite) R.drawable.ic_star else R.drawable.ic_star_border)
            setTitle(if (favourite) R.string.unfavourite else R.string.favourite)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val recording = recording ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_favourite ->
                lifecycleScope.launch {
                    RecordingsRepository.setFavourite(
                        this@PlayerActivity,
                        recording.id,
                        !recording.favourite,
                    )
                    bind(recording.copy(favourite = !recording.favourite))
                }
            R.id.action_labels ->
                lifecycleScope.launch {
                    val known =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            RecordingsRepository.knownLabels(this@PlayerActivity)
                        }
                    LabelsDialog.show(this@PlayerActivity, recording.labels, known) { labels ->
                        lifecycleScope.launch {
                            RecordingsRepository.setLabels(this@PlayerActivity, recording, labels)
                            bind(recording.copy(labels = labels))
                        }
                    }
                }
            R.id.action_edit -> setEditMode(!waveformView.selectionEnabled)
            R.id.action_share ->
                startActivity(RecordIntentHelper.getShareIntent(recording.uri, recording.mimeType))
            R.id.action_rename ->
                RenameDialog.show(this, recording.title.substringBeforeLast('.')) { name ->
                    lifecycleScope.launch {
                        val newName = name + "." + recording.title.substringAfterLast('.', "m4a")
                        model.renameRecording(recording, newName)
                        bind(recording.copy(title = newName))
                    }
                }
            R.id.action_delete ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_title)
                    .setMessage(R.string.trash_move_message)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch {
                            controller?.stop()
                            controller?.clearMediaItems()
                            model.deleteRecordings(recording)
                            finish()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            else -> return onExtraMenuItem(item) || super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setEditMode(enabled: Boolean) {
        waveformView.selectionEnabled = enabled
        findViewById<View>(R.id.editBar).visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) updateSelectionText()
    }

    private fun updateSelectionText() {
        findViewById<TextView>(R.id.selectionTextView).text =
            getString(
                R.string.edit_selection,
                format(waveformView.selectionStartMs),
                format(waveformView.selectionEndMs),
            )
    }

    private fun cut(mode: AudioEditor.Mode) {
        val recording = recording ?: return
        val base = recording.title.substringBeforeLast('.')
        RenameDialog.show(this, getString(R.string.edit_copy_name, base)) { name ->
            val progress =
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.edit_saving)
                    .setCancelable(false)
                    .show()
            lifecycleScope.launch {
                val uri =
                    try {
                        AudioEditor.cut(
                            this@PlayerActivity,
                            recording,
                            waveformView.selectionStartMs,
                            waveformView.selectionEndMs,
                            mode,
                            name,
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("PlayerActivity", "Edit failed", e)
                        null
                    }
                progress.dismiss()
                if (uri == null) {
                    Toast.makeText(this@PlayerActivity, R.string.edit_failed, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    onNewIntent(intent(this@PlayerActivity, Uri.parse(uri)))
                }
            }
        }
    }

    /** Hook for menu items added by later features (edit, transcript export, clips). */
    var extraMenuHandler: ((MenuItem) -> Boolean)? = null

    private fun onExtraMenuItem(item: MenuItem) = extraMenuHandler?.invoke(item) == true

    companion object {
        private const val TICK_MS = 50L
        private const val EXTRA_URI = "uri"
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
        private val speedFormat = DecimalFormat("0.##")

        fun format(ms: Long): String = DateUtils.formatElapsedTime(ms / 1000)

        fun intent(context: Context, uri: Uri) =
            Intent(context, PlayerActivity::class.java).setData(uri)
    }
}
