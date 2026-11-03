/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.prauga.recorder.models.Recording
import org.prauga.recorder.viewmodels.RecordingsViewModel

class TrashActivity : AppCompatActivity(R.layout.activity_list) {
    private val model: RecordingsViewModel by viewModels()
    private val adapter = TrashAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.trash_title)
        supportActionBar?.setSubtitle(R.string.trash_summary)
        findViewById<View>(R.id.filterScrollView).isVisible = false

        val list = findViewById<RecyclerView>(R.id.listRecyclerView)
        val empty = findViewById<TextView>(R.id.listEmptyTextView)
        empty.setText(R.string.trash_empty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.trashed.collectLatest {
                    adapter.submitList(it)
                    findViewById<ProgressBar>(R.id.listLoadingProgressBar).isVisible = false
                    empty.isVisible = it.isEmpty()
                    list.isVisible = it.isNotEmpty()
                    invalidateOptionsMenu()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EMPTY, 0, R.string.trash_empty_action)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_EMPTY)?.isEnabled = adapter.itemCount > 0
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_EMPTY -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.trash_empty_action)
                    .setMessage(R.string.trash_empty_message)
                    .setPositiveButton(R.string.trash_delete_forever) { _, _ ->
                        lifecycleScope.launch {
                            model.deleteForever(*adapter.currentList.map { it.uri }.toTypedArray())
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun restore(recording: Recording) {
        lifecycleScope.launch { model.restoreRecordings(recording.uri) }
    }

    private fun deleteForever(recording: Recording) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trash_delete_forever)
            .setMessage(R.string.trash_delete_forever_message)
            .setPositiveButton(R.string.trash_delete_forever) { _, _ ->
                lifecycleScope.launch { model.deleteForever(recording.uri) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class TrashAdapter :
        ListAdapter<Recording, TrashAdapter.Holder>(
            object : DiffUtil.ItemCallback<Recording>() {
                override fun areItemsTheSame(a: Recording, b: Recording) = a.uri == b.uri

                override fun areContentsTheSame(a: Recording, b: Recording) = a == b
            }
        ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.list_item, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(getItem(position))

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            fun bind(recording: Recording) {
                itemView.findViewById<TextView>(R.id.titleTextView).text = recording.title
                itemView.findViewById<TextView>(R.id.dateTextView).text =
                    listOf(
                            DateFormat.getDateInstance().format(recording.dateAdded),
                            DateUtils.formatElapsedTime(recording.duration / 1000),
                        )
                        .joinToString(" · ")
                itemView.findViewById<View>(R.id.favouriteImageView).isVisible = false
                itemView.findViewById<ImageView>(R.id.playImageView).apply {
                    setImageResource(R.drawable.ic_restore)
                    contentDescription = getString(R.string.trash_restore)
                    setOnClickListener { restore(recording) }
                }
                itemView.setOnClickListener { restore(recording) }
                itemView.findViewById<ImageView>(R.id.menuImageView).setOnClickListener { v ->
                    PopupMenu(v.context, v).apply {
                        menu.add(0, 1, 0, R.string.trash_restore)
                        menu.add(0, 2, 1, R.string.trash_delete_forever)
                        setOnMenuItemClickListener {
                            if (it.itemId == 1) restore(recording) else deleteForever(recording)
                            true
                        }
                        show()
                    }
                }
            }
        }
    }

    companion object {
        private const val MENU_EMPTY = 1
    }
}
