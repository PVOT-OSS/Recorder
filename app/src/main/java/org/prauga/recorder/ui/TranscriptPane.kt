/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.ui

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.prauga.recorder.PlayerActivity
import org.prauga.recorder.R
import org.prauga.recorder.data.RecordingsDatabase
import org.prauga.recorder.data.Segment
import org.prauga.recorder.data.Tag
import org.prauga.recorder.models.Recording
import org.prauga.recorder.transcription.Keywords
import org.prauga.recorder.transcription.ModelManager
import org.prauga.recorder.transcription.SoundTagger
import org.prauga.recorder.transcription.TranscriptionService

/** The transcript under the player: follows playback, seeks on tap, edits and searches. */
class TranscriptPane(private val activity: PlayerActivity, container: ViewGroup) {
    private val root =
        LayoutInflater.from(activity).inflate(R.layout.transcript_pane, container, true)
    private val list = root.findViewById<RecyclerView>(R.id.transcriptRecyclerView)
    private val empty = root.findViewById<View>(R.id.transcriptEmpty)
    private val emptyText = root.findViewById<TextView>(R.id.transcriptEmptyTextView)
    private val progress = root.findViewById<LinearProgressIndicator>(R.id.transcriptProgress)
    private val transcribeButton = root.findViewById<Button>(R.id.transcribeButton)
    private val searchBar = root.findViewById<View>(R.id.transcriptSearchBar)
    private val searchEdit = root.findViewById<EditText>(R.id.transcriptSearchEditText)
    private val searchCount = root.findViewById<TextView>(R.id.transcriptSearchCount)

    private val db = RecordingsDatabase.get(activity)
    private val adapter = Adapter()
    private var recording: Recording? = null
    private var segments: List<Segment> = emptyList()
    private var speakers: Map<Int, String> = emptyMap()
    private var current = -1
    private var currentWord = -1
    private var query = ""
    private var matches: List<Int> = emptyList()
    private var match = -1
    private var userScrolledAt = 0L

    private val highlight =
        MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimaryContainer)
    private val searchHighlight =
        MaterialColors.getColor(root, com.google.android.material.R.attr.colorTertiaryContainer)

    val hasTranscript
        get() = segments.isNotEmpty()

    val transcript
        get() = segments

    fun speakerName(speaker: Int): String? =
        if (speaker < 0) null
        else speakers[speaker] ?: activity.getString(R.string.transcript_speaker, speaker + 1)

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        userScrolledAt = System.currentTimeMillis()
                    }
                }
            }
        )
        transcribeButton.setOnClickListener { transcribe() }
        searchEdit.doAfterTextChanged { search(it?.toString().orEmpty()) }
        searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                step(1)
                true
            } else false
        }
        root.findViewById<ImageButton>(R.id.transcriptSearchNext).setOnClickListener { step(1) }
        root.findViewById<ImageButton>(R.id.transcriptSearchPrevious).setOnClickListener {
            step(-1)
        }
        root.findViewById<ImageButton>(R.id.transcriptSearchClose).setOnClickListener {
            showSearch(false)
        }
    }

    fun bind(recording: Recording) {
        this.recording = recording
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(db.changes, TranscriptionService.progress) { _, p -> p[recording.id] }
                    .collect { fraction ->
                        val (s, names) =
                            withContext(Dispatchers.IO) {
                                db.segments(recording.id) to db.speakers(recording.id)
                            }
                        val tags = withContext(Dispatchers.IO) { db.tags(recording.id) }
                        showTags(tags)
                        segments = s
                        speakers = names
                        showKeywords()
                        current = -1
                        search(query)
                        adapter.notifyDataSetChanged()
                        updateEmpty(fraction)
                        activity.invalidateOptionsMenu()
                    }
            }
        }
    }

    /** Notable sound labels: tinted on the waveform, chips cycle through their occurrences. */
    private fun showTags(tags: List<Tag>) {
        val notable = tags.filter { it.label in SoundTagger.NOTABLE }
        activity.waveformView.ranges =
            notable.map {
                PlaybackWaveformView.Range(
                    it.startMs,
                    it.endMs,
                    ContextCompat.getColor(activity, tagColor(it.label)),
                )
            }
        val group = activity.findViewById<ChipGroup>(R.id.tagChipGroup)
        (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filter { it.tag == SOUND_TAG }
            .forEach { group.removeView(it) }
        notable
            .groupBy { it.label }
            .entries
            .forEachIndexed { index, (label, ranges) ->
                var next = 0
                group.addView(
                    Chip(activity).apply {
                        text = activity.getString(tagName(label))
                        tag = SOUND_TAG
                        chipBackgroundColor =
                            android.content.res.ColorStateList.valueOf(
                                ContextCompat.getColor(activity, tagColor(label))
                            )
                        setOnClickListener { activity.seekTo(ranges[next++ % ranges.size].startMs) }
                    },
                    index,
                )
            }
        group.isVisible = group.childCount > 0
    }

    private fun tagName(label: String) =
        when (label) {
            SoundTagger.MUSIC -> R.string.tag_music
            SoundTagger.SINGING -> R.string.tag_singing
            SoundTagger.APPLAUSE -> R.string.tag_applause
            SoundTagger.LAUGHTER -> R.string.tag_laughter
            else -> R.string.tag_animal
        }

    private fun tagColor(label: String) =
        when (label) {
            SoundTagger.MUSIC -> R.color.tag_music
            SoundTagger.SINGING -> R.color.tag_singing
            SoundTagger.APPLAUSE -> R.color.tag_applause
            SoundTagger.LAUGHTER -> R.color.tag_laughter
            else -> R.color.tag_animal
        }

    /** Keyword chips above the waveform; tapping one finds it in the transcript. */
    private fun showKeywords() {
        val group = activity.findViewById<ChipGroup>(R.id.tagChipGroup)
        (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filter { it.tag == KEYWORD_TAG }
            .forEach { group.removeView(it) }
        for (keyword in Keywords.extract(segments)) {
            group.addView(
                Chip(activity).apply {
                    text = keyword
                    tag = KEYWORD_TAG
                    setOnClickListener {
                        showSearch(true)
                        searchEdit.setText(keyword)
                        searchEdit.setSelection(keyword.length)
                        step(0)
                    }
                }
            )
        }
        group.isVisible = group.childCount > 0
    }

    private fun updateEmpty(fraction: Float?) {
        list.isVisible = segments.isNotEmpty()
        empty.isVisible = segments.isEmpty()
        progress.isVisible = fraction != null
        transcribeButton.isVisible = fraction == null
        emptyText.text =
            when {
                fraction != null ->
                    activity.getString(R.string.transcript_working, (fraction * 100).toInt())
                else -> activity.getString(R.string.transcript_none)
            }
        fraction?.let { progress.setProgressCompat((it * 100).toInt(), true) }
    }

    fun transcribe() {
        val recording = recording ?: return
        if (ModelManager.speechModel(activity) == null) {
            Toast.makeText(activity, R.string.transcribe_no_model, Toast.LENGTH_SHORT).show()
            activity.startActivity(
                android.content.Intent(
                    activity,
                    org.prauga.recorder.transcription.ModelsActivity::class.java,
                )
            )
            return
        }
        TranscriptionService.enqueue(activity, recording.uri, force = true)
        updateEmpty(0f)
    }

    fun onPosition(positionMs: Long) {
        val index = segments.indexOfLast { it.startMs <= positionMs }
        val word = segments.getOrNull(index)?.words?.indexOfLast { it.startMs <= positionMs } ?: -1
        if (index == current && word == currentWord) return
        val previous = current
        current = index
        currentWord = word
        if (previous >= 0) adapter.notifyItemChanged(previous)
        if (index >= 0) {
            adapter.notifyItemChanged(index)
            if (
                index != previous && System.currentTimeMillis() - userScrolledAt > FOLLOW_DELAY_MS
            ) {
                scrollTo(index)
            }
        }
    }

    private fun scrollTo(index: Int) {
        val scroller =
            object : LinearSmoothScroller(activity) {
                override fun getVerticalSnapPreference() = SNAP_TO_START
            }
        scroller.targetPosition = index
        list.layoutManager?.startSmoothScroll(scroller)
    }

    fun showSearch(show: Boolean) {
        searchBar.isVisible = show
        if (show) {
            searchEdit.requestFocus()
        } else {
            searchEdit.text = null
            search("")
        }
    }

    private fun search(text: String) {
        query = text.trim()
        matches =
            if (query.isEmpty()) emptyList()
            else segments.indices.filter { segments[it].text.contains(query, ignoreCase = true) }
        match = if (matches.isEmpty()) -1 else 0
        updateSearchCount()
        adapter.notifyDataSetChanged()
        if (match >= 0) list.scrollToPosition(matches[match])
    }

    private fun step(delta: Int) {
        if (matches.isEmpty()) return
        match = (match + delta + matches.size) % matches.size
        updateSearchCount()
        val segment = segments[matches[match]]
        userScrolledAt = 0
        activity.seekTo(segment.startMs)
        scrollTo(matches[match])
    }

    private fun updateSearchCount() {
        searchCount.text =
            if (query.isEmpty()) ""
            else activity.getString(R.string.transcript_matches, match + 1, matches.size)
    }

    private fun renameSpeaker(speaker: Int) {
        val recording = recording ?: return
        RenameDialog.show(
            activity,
            speakerName(speaker).orEmpty(),
            dialogTitle = R.string.transcript_rename_speaker,
        ) { name ->
            activity.lifecycleScope.launch(Dispatchers.IO) {
                db.renameSpeaker(recording.id, speaker, name)
            }
        }
    }

    private fun edit(segment: Segment) {
        val recording = recording ?: return
        val input = TextInputEditText(activity).apply { setText(segment.text) }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val frame =
            android.widget.FrameLayout(activity).apply {
                setPadding(padding, padding / 2, padding, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.transcript_edit)
            .setView(frame)
            .setPositiveButton(R.string.labels_save) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty() || text == segment.text) return@setPositiveButton
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    // Word timings no longer match edited text, keep only the segment's span
                    db.updateSegment(recording.id, segment.copy(text = text, words = emptyList()))
                    db.reindex(recording.id, recording.title)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        override fun getItemCount() = segments.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.transcript_item, parent, false)
            )

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(segments[position], position)

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val header = view.findViewById<TextView>(R.id.segmentHeaderTextView)
            private val text = view.findViewById<TextView>(R.id.segmentTextView)

            fun bind(segment: Segment, position: Int) {
                header.text =
                    listOfNotNull(
                            speakerName(segment.speaker),
                            PlayerActivity.format(segment.startMs),
                        )
                        .joinToString(" · ")
                val spannable = SpannableString(segment.text)
                if (position == current) {
                    val word = segment.words.getOrNull(currentWord)
                    val start = word?.let { locate(segment, currentWord) } ?: -1
                    if (word != null && start >= 0) {
                        spannable.setSpan(
                            BackgroundColorSpan(highlight),
                            start,
                            start + word.text.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        spannable.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (query.isNotEmpty()) {
                    var from = segment.text.indexOf(query, ignoreCase = true)
                    while (from >= 0) {
                        spannable.setSpan(
                            BackgroundColorSpan(searchHighlight),
                            from,
                            from + query.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        from = segment.text.indexOf(query, from + query.length, ignoreCase = true)
                    }
                }
                text.text = spannable
                itemView.contentDescription = "${header.text}. ${segment.text}"
                itemView.setOnClickListener {
                    userScrolledAt = 0
                    activity.seekTo(segment.startMs)
                }
                itemView.setOnLongClickListener {
                    edit(segment)
                    true
                }
                header.setOnClickListener(
                    if (segment.speaker >= 0)
                        View.OnClickListener { renameSpeaker(segment.speaker) }
                    else null
                )
                header.isClickable = segment.speaker >= 0
            }
        }
    }

    /** Character offset of word [index] in the segment text, matching words in order. */
    private fun locate(segment: Segment, index: Int): Int {
        var from = 0
        for (i in 0..index) {
            val w = segment.words[i].text
            val at = segment.text.indexOf(w, from)
            if (at < 0) return -1
            if (i == index) return at
            from = at + w.length
        }
        return -1
    }

    companion object {
        private const val FOLLOW_DELAY_MS = 4000L
        private const val KEYWORD_TAG = "keyword"
        private const val SOUND_TAG = "sound"
    }
}
