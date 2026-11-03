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
import org.prauga.recorder.clip.ClipExporter
import org.prauga.recorder.models.Recording
import org.prauga.recorder.playback.PlaybackService
import org.prauga.recorder.repository.RecordingsRepository
import org.prauga.recorder.transcription.Keywords
import org.prauga.recorder.transcription.TranscriptExport
import org.prauga.recorder.ui.LabelsDialog
import org.prauga.recorder.ui.PlaybackWaveformView
import org.prauga.recorder.ui.RenameDialog
import org.prauga.recorder.ui.TranscriptPane
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
            transcriptPane.bind(recording)
            onRecordingLoaded?.invoke(recording)
        }
    }

    var onRecordingLoaded: ((Recording) -> Unit)? = null

    val transcriptPane by lazy { TranscriptPane(this, findViewById(R.id.detailContainer)) }
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
        transcriptPane.onPosition(position)
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
        val hasTranscript = transcriptPane.hasTranscript
        menu.findItem(R.id.action_search_transcript)?.isVisible = hasTranscript
        menu.findItem(R.id.action_export_transcript)?.isVisible = hasTranscript
        menu.findItem(R.id.action_transcribe)?.isVisible = recording != null
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
            R.id.action_transcribe -> transcriptPane.transcribe()
            R.id.action_search_transcript -> transcriptPane.showSearch(true)
            R.id.action_export_transcript -> exportTranscript(recording)
            R.id.action_clip -> createClip(recording)
            R.id.action_edit -> setEditMode(!waveformView.selectionEnabled)
            R.id.action_share ->
                startActivity(RecordIntentHelper.getShareIntent(recording.uri, recording.mimeType))
            R.id.action_rename ->
                RenameDialog.show(
                    this,
                    recording.title.substringBeforeLast('.'),
                    Keywords.suggestTitles(transcriptPane.transcript, recording.locationName),
                ) { name ->
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

    private fun createClip(recording: Recording) {
        val shapes = ClipExporter.Shape.entries
        val labels =
            arrayOf(
                getString(R.string.clip_portrait),
                getString(R.string.clip_landscape),
                getString(R.string.clip_square),
            )
        var shape = 0
        val dark = booleanArrayOf(true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clip_shape)
            .setSingleChoiceItems(labels, 0) { _, which -> shape = which }
            .setPositiveButton(R.string.clip_create) { _, _ ->
                renderClip(recording, shapes[shape], dark[0])
            }
            .setNeutralButton(R.string.clip_dark, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { dialog ->
                val button = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                fun label() {
                    button.alpha = if (dark[0]) 1f else 0.5f
                }
                label()
                button.setOnClickListener {
                    dark[0] = !dark[0]
                    label()
                }
            }
    }

    private fun renderClip(recording: Recording, shape: ClipExporter.Shape, dark: Boolean) {
        val (start, end) =
            if (waveformView.selectionEnabled) {
                waveformView.selectionStartMs to waveformView.selectionEndMs
            } else {
                val from =
                    (controller?.currentPosition ?: 0L).coerceAtMost(
                        (recording.duration - 1000).coerceAtLeast(0)
                    )
                from to minOf(recording.duration, from + ClipExporter.MAX_LENGTH_MS)
            }
        val progress =
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.clip_rendering, 0))
                .setCancelable(false)
                .show()
        lifecycleScope.launch {
            try {
                val waveform =
                    WaveformExtractor.load(this@PlayerActivity, recording.id, recording.uri)
                val file =
                    ClipExporter.export(
                        this@PlayerActivity,
                        recording,
                        waveform,
                        start,
                        end,
                        transcriptPane.transcript,
                        transcriptPane::speakerName,
                        shape,
                        dark,
                    ) { fraction ->
                        runOnUiThread {
                            progress.setMessage(
                                getString(R.string.clip_rendering, (fraction * 100).toInt())
                            )
                        }
                    }
                val uri =
                    androidx.core.content.FileProvider.getUriForFile(
                        this@PlayerActivity,
                        "$packageName.exports",
                        file,
                    )
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .also { it.clipData = android.content.ClipData.newRawUri(null, uri) },
                        null,
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Clip failed", e)
                Toast.makeText(this@PlayerActivity, R.string.clip_failed, Toast.LENGTH_SHORT).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun exportTranscript(recording: Recording) {
        val formats = TranscriptExport.Format.entries
        val labels =
            arrayOf(
                getString(R.string.transcript_export_text),
                getString(R.string.transcript_export_srt),
                getString(R.string.transcript_export_vtt),
            )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transcript_export)
            .setItems(labels) { _, which ->
                startActivity(
                    TranscriptExport.shareIntent(
                        this,
                        recording.title.substringBeforeLast('.'),
                        transcriptPane.transcript,
                        formats[which],
                        transcriptPane::speakerName,
                    )
                )
            }
            .show()
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
