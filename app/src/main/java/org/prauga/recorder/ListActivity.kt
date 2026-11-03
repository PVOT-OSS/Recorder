/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.prauga.recorder.list.RecordingItemCallbacks
import org.prauga.recorder.list.RecordingItemDetailsLookup
import org.prauga.recorder.list.RecordingsAdapter
import org.prauga.recorder.models.Recording
import org.prauga.recorder.repository.RecordingsRepository
import org.prauga.recorder.ui.LabelsDialog
import org.prauga.recorder.ui.RenameDialog
import org.prauga.recorder.utils.RecordIntentHelper
import org.prauga.recorder.viewmodels.RecordingsViewModel

class ListActivity : AppCompatActivity() {
    // View models
    private val model: RecordingsViewModel by viewModels()

    // Views
    private val contentView by lazy { findViewById<View>(android.R.id.content) }
    private val listEmptyTextView by lazy { findViewById<TextView>(R.id.listEmptyTextView) }
    private val listLoadingProgressBar by lazy {
        findViewById<ProgressBar>(R.id.listLoadingProgressBar)
    }
    private val listRecyclerView by lazy { findViewById<RecyclerView>(R.id.listRecyclerView) }
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }

    // Adapters
    private val recordingItemCallbacks =
        object : RecordingItemCallbacks {
            override fun onPlay(recording: Recording) {
                this@ListActivity.onPlay(recording)
            }

            override fun onShare(recording: Recording) {
                this@ListActivity.onShare(recording)
            }

            override fun onDelete(recording: Recording) {
                this@ListActivity.onDelete(recording)
            }

            override fun onRename(recording: Recording) {
                this@ListActivity.onRename(recording)
            }

            override fun onEditLabels(recording: Recording) {
                LabelsDialog.show(this@ListActivity, recording.labels, model.labels.value) {
                    lifecycleScope.launch {
                        RecordingsRepository.setLabels(this@ListActivity, recording, it)
                    }
                }
            }

            override fun onToggleFavourite(recording: Recording) {
                lifecycleScope.launch {
                    RecordingsRepository.setFavourite(
                        this@ListActivity,
                        recording.id,
                        !recording.favourite,
                    )
                }
            }
        }
    private val recordingsAdapter by lazy { RecordingsAdapter(model, recordingItemCallbacks) }

    // Selection
    private var selectionTracker: SelectionTracker<Recording>? = null

    private val selectionTrackerObserver =
        object : SelectionTracker.SelectionObserver<Recording>() {
            override fun onSelectionChanged() {
                super.onSelectionChanged()

                updateSelection()
            }

            override fun onSelectionRefresh() {
                super.onSelectionRefresh()

                updateSelection()
            }

            override fun onSelectionRestored() {
                super.onSelectionRestored()

                updateSelection()
            }
        }

    private var actionMode: ActionMode? = null

    private val actionModeCallback =
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menuInflater.inflate(R.menu.menu_list_action_mode, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) =
                selectionTracker
                    ?.selection
                    ?.toList()
                    ?.toTypedArray()
                    ?.takeUnless { it.isEmpty() }
                    ?.let { selection ->
                        when (item?.itemId) {
                            R.id.deleteForever -> {
                                MaterialAlertDialogBuilder(this@ListActivity)
                                    .setTitle(R.string.delete_selected_title)
                                    .setMessage(getString(R.string.trash_move_selected_message))
                                    .setPositiveButton(R.string.delete) {
                                        _: DialogInterface?,
                                        _: Int ->
                                        lifecycleScope.launch { model.deleteRecordings(*selection) }
                                    }
                                    .setNegativeButton(R.string.cancel, null)
                                    .show()

                                true
                            }

                            R.id.share -> {
                                val uris = selection.map { it.uri }

                                startActivity(RecordIntentHelper.getShareIntents(uris, TYPE_AUDIO))

                                true
                            }

                            else -> false
                        }
                    } ?: false

            override fun onDestroyActionMode(mode: ActionMode?) {
                selectionTracker?.clearSelection()
            }
        }

    private val inSelectionModeObserver = Observer { inSelectionMode: Boolean ->
        if (inSelectionMode) {
            startSelectionMode()
        } else {
            endSelectionMode()
        }
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            lifecycleScope.launch {
                val count =
                    uris.count { RecordingsRepository.import(this@ListActivity, it) != null }
                Toast.makeText(
                        this@ListActivity,
                        resources.getQuantityString(R.plurals.import_done, count, count),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_list)

        // Setup edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setSupportActionBar(toolbar)
        supportActionBar?.let {
            it.setDisplayShowHomeEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
        }

        listRecyclerView.layoutManager = LinearLayoutManager(this)
        listRecyclerView.adapter = recordingsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            listRecyclerView.updatePadding(
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right,
            )

            windowInsets
        }

        selectionTracker =
            SelectionTracker.Builder(
                    "recordings",
                    listRecyclerView,
                    recordingsAdapter.itemKeyProvider,
                    RecordingItemDetailsLookup(listRecyclerView),
                    StorageStrategy.createParcelableStorage(Recording::class.java),
                )
                .withSelectionPredicate(SelectionPredicates.createSelectAnything())
                .build()
                .also {
                    recordingsAdapter.selectionTracker = it
                    it.addObserver(selectionTrackerObserver)
                }

        model.inSelectionMode.observe(this, inSelectionModeObserver)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.labels.collectLatest { updateFilterChips(it) } }
                model.recordings.collectLatest {
                    recordingsAdapter.submitList(it)

                    listLoadingProgressBar.isVisible = false

                    val isEmpty = it.isEmpty()
                    listEmptyTextView.setText(
                        if (
                            model.query.value.isBlank() &&
                                model.filter.value == RecordingsViewModel.Filter.All
                        )
                            R.string.list_error_empty
                        else R.string.list_no_results
                    )
                    changeEmptyView(isEmpty)
                    if (isEmpty) {
                        endSelectionMode()
                    }
                }
            }
        }
    }

    private fun updateFilterChips(labels: List<String>) {
        val group = findViewById<ChipGroup>(R.id.filterChipGroup)
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        val filters =
            listOf(RecordingsViewModel.Filter.All, RecordingsViewModel.Filter.Favourites) +
                labels.map { RecordingsViewModel.Filter.Label(it) }
        if (model.filter.value !in filters) model.filter.value = RecordingsViewModel.Filter.All
        for (filter in filters) {
            val chip =
                Chip(this).apply {
                    id = View.generateViewId()
                    isCheckable = true
                    text =
                        when (filter) {
                            RecordingsViewModel.Filter.All -> getString(R.string.filter_all)
                            RecordingsViewModel.Filter.Favourites ->
                                getString(R.string.filter_favourites)
                            is RecordingsViewModel.Filter.Label -> filter.name
                        }
                    tag = filter
                }
            group.addView(chip)
            if (filter == model.filter.value) group.check(chip.id)
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            ids.firstOrNull()?.let {
                model.filter.value = g.findViewById<Chip>(it).tag as RecordingsViewModel.Filter
            }
        }
    }

    fun onPlay(recording: Recording) {
        startActivity(PlayerActivity.intent(this, recording.uri))
    }

    fun onShare(recording: Recording) {
        startActivity(RecordIntentHelper.getShareIntent(recording.uri, TYPE_AUDIO))
    }

    fun onDelete(recording: Recording) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.trash_move_message)
            .setPositiveButton(R.string.delete) { _: DialogInterface?, _: Int ->
                lifecycleScope.launch { model.deleteRecordings(recording) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun onRename(recording: Recording) {
        RenameDialog.show(this, recording.title) { name ->
            lifecycleScope.launch { model.renameRecording(recording, name) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_list, menu)
        (menu.findItem(R.id.action_search)?.actionView as? SearchView)?.apply {
            queryHint = getString(R.string.search_hint)
            maxWidth = Int.MAX_VALUE
            if (model.query.value.isNotEmpty()) {
                menu.findItem(R.id.action_search).expandActionView()
                setQuery(model.query.value, false)
            }
            setOnQueryTextListener(
                object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String) = true

                    override fun onQueryTextChange(newText: String): Boolean {
                        model.query.value = newText
                        return true
                    }
                }
            )
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val deleteAllItem = menu.findItem(R.id.action_delete_all)
        val hasItems = recordingsAdapter.itemCount > 0
        deleteAllItem.setEnabled(hasItems)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        when (item.itemId) {
            R.id.action_import -> {
                importLauncher.launch(arrayOf("audio/*"))
                true
            }

            R.id.action_trash -> {
                startActivity(Intent(this, TrashActivity::class.java))
                true
            }

            R.id.action_delete_all -> {
                promptDeleteAllRecordings()
                true
            }

            else -> false
        }

    private fun updateSelection() {
        model.inSelectionMode.value = selectionTracker?.hasSelection() == true

        selectionTracker
            ?.selection
            ?.count()
            ?.takeIf { it > 0 }
            ?.let {
                startSelectionMode().apply {
                    title = resources.getQuantityString(R.plurals.recording_selection_count, it, it)
                }
            }
    }

    private fun startSelectionMode() =
        actionMode ?: toolbar.startActionMode(actionModeCallback).also { actionMode = it }

    private fun endSelectionMode() {
        actionMode?.finish()
        actionMode = null
    }

    override fun onActionModeFinished(mode: ActionMode) {
        super.onActionModeFinished(mode)
        endSelectionMode()
    }

    private fun changeEmptyView(isEmpty: Boolean) {
        listEmptyTextView.isVisible = isEmpty
        listRecyclerView.isVisible = !isEmpty
    }

    private fun promptDeleteAllRecordings() {
        if (recordingsAdapter.itemCount == 0) {
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(getString(R.string.trash_move_all_message))
            .setPositiveButton(R.string.delete) { _: DialogInterface?, _: Int ->
                lifecycleScope.launch {
                    model.deleteRecordings(*recordingsAdapter.currentList.toTypedArray())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val TYPE_AUDIO = "audio/*"
    }
}
