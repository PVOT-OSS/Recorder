/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.prauga.recorder.R
import org.prauga.recorder.models.UiStatus

/**
 * The sheet that slides up while recording: status, timer, live waveform and transcript, with
 * pause/resume and stop. It can't be swiped away while a recording is running.
 */
class RecordingSheet(
    root: View,
    private val onStop: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    private val context = root.context
    private val sheet = root.findViewById<View>(R.id.recordingSheet)
    private val title = root.findViewById<TextView>(R.id.sheetTitleTextView)
    private val elapsed = root.findViewById<TextView>(R.id.sheetElapsedTextView)
    private val waveform = root.findViewById<WaveFormView>(R.id.sheetWaveFormView)
    private val transcript = root.findViewById<TextView>(R.id.sheetTranscriptTextView)
    private val pauseResume = root.findViewById<ImageButton>(R.id.sheetPauseResumeButton)
    private val behavior = BottomSheetBehavior.from(sheet)

    private var status: UiStatus? = null

    init {
        behavior.isHideable = true
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
        behavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    onVisibilityChanged(newState != BottomSheetBehavior.STATE_HIDDEN)
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
            }
        )
        root.findViewById<FloatingActionButton>(R.id.sheetStopButton).setOnClickListener {
            onStop()
        }
        pauseResume.setOnClickListener { onTogglePause() }
    }

    val isShown
        get() = behavior.state != BottomSheetBehavior.STATE_HIDDEN

    fun setStatus(status: UiStatus) {
        val previous = this.status
        this.status = status
        if (status == UiStatus.READY) {
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
            transcript.text = null
            elapsed.text = DateUtils.formatElapsedTime(0)
            waveform.setAmplitude(0)
            return
        }
        behavior.isHideable = false
        behavior.isDraggable = false
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val paused = status == UiStatus.PAUSED
        title.setText(
            if (paused) R.string.sound_recording_title_paused
            else R.string.sound_recording_title_working
        )
        pauseResume.contentDescription =
            context.getString(if (paused) R.string.resume else R.string.pause)
        pauseResume.tooltipText = pauseResume.contentDescription
        if (previous == null || previous == UiStatus.READY) {
            pauseResume.setImageResource(
                if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause
            )
        } else if (previous != status) {
            val drawable =
                ContextCompat.getDrawable(
                    context,
                    // Upstream's drawables are named after the state they leave, not reach
                    if (paused) R.drawable.avd_play_to_pause else R.drawable.avd_pause_to_play,
                )
            pauseResume.setImageDrawable(drawable)
            (drawable as? AnimatedVectorDrawable)?.start()
        }
        if (paused) waveform.setAmplitude(0)
    }

    fun setAmplitude(amplitude: Int) = waveform.post { waveform.setAmplitude(amplitude) }

    fun setElapsedTime(seconds: Long) {
        elapsed.text = DateUtils.formatElapsedTime(seconds)
    }

    fun setTranscript(text: String) {
        transcript.text = text.takeLast(TRANSCRIPT_CHARS)
    }

    companion object {
        private const val TRANSCRIPT_CHARS = 200
    }
}
