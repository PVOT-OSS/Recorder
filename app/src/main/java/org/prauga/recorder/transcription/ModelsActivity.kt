/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.transcription

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.prauga.recorder.R
import org.prauga.recorder.utils.PreferencesManager

class ModelsActivity : AppCompatActivity(org.prauga.recorder.R.layout.activity_list) {
    private val preferences by lazy { PreferencesManager(this) }
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.models_title)
        supportActionBar?.setSubtitle(R.string.models_summary)
        findViewById<View>(R.id.filterScrollView).isVisible = false
        findViewById<ProgressBar>(R.id.listLoadingProgressBar).isVisible = false

        val list = findViewById<RecyclerView>(R.id.listRecyclerView)
        list.isVisible = true
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ModelManager.changes.collect { adapter.refresh() } }
                while (true) {
                    ModelManager.completePending(this@ModelsActivity)
                    adapter.refresh()
                    delay(1000)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) {
            finish()
            true
        } else super.onOptionsItemSelected(item)

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private val models = Models.SPEECH + Models.SPEAKER + Models.TAGGER
        private var states = models.map { ModelManager.State.NotInstalled as ModelManager.State }

        fun refresh() {
            val newStates = models.map { ModelManager.state(this@ModelsActivity, it) }
            if (newStates != states) {
                states = newStates
                notifyDataSetChanged()
            }
        }

        override fun getItemCount() = models.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.model_item, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(models[position], states[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val radio = view.findViewById<RadioButton>(R.id.modelRadioButton)
            private val title = view.findViewById<TextView>(R.id.modelTitleTextView)
            private val summary = view.findViewById<TextView>(R.id.modelSummaryTextView)
            private val progress = view.findViewById<LinearProgressIndicator>(R.id.modelProgress)
            private val action = view.findViewById<ImageButton>(R.id.modelActionButton)

            fun bind(model: SpeechModel, state: ModelManager.State) {
                val context = itemView.context
                title.text =
                    when {
                        model.isSpeaker -> getString(R.string.models_speaker)
                        model.isTagger -> getString(R.string.models_tagger)
                        else -> model.language
                    }
                val size = Formatter.formatShortFileSize(context, model.size)
                summary.text =
                    when (state) {
                        ModelManager.State.Installed -> getString(R.string.models_installed)
                        is ModelManager.State.Downloading ->
                            getString(R.string.models_downloading, size)
                        ModelManager.State.Installing -> getString(R.string.models_installing)
                        ModelManager.State.Failed -> getString(R.string.models_failed)
                        ModelManager.State.NotInstalled -> size
                    }
                progress.isVisible =
                    state is ModelManager.State.Downloading ||
                        state == ModelManager.State.Installing
                if (state is ModelManager.State.Downloading) {
                    progress.isIndeterminate = false
                    progress.setProgressCompat((state.progress * 100).toInt(), true)
                } else {
                    progress.isIndeterminate = true
                }
                radio.isVisible = !model.isSpeaker && !model.isTagger
                radio.isEnabled = state == ModelManager.State.Installed
                radio.isChecked =
                    state == ModelManager.State.Installed &&
                        ModelManager.speechModel(context)?.id == model.id
                itemView.setOnClickListener {
                    if (
                        !model.isSpeaker && !model.isTagger && state == ModelManager.State.Installed
                    ) {
                        preferences.speechModel = model.id
                        notifyDataSetChanged()
                    }
                }
                val busy =
                    state is ModelManager.State.Downloading ||
                        state == ModelManager.State.Installing
                action.setImageResource(
                    when {
                        state == ModelManager.State.Installed -> R.drawable.ic_delete
                        busy -> R.drawable.ic_close
                        else -> R.drawable.ic_download
                    }
                )
                action.contentDescription =
                    getString(
                        when {
                            state == ModelManager.State.Installed -> R.string.delete
                            busy -> R.string.cancel
                            else -> R.string.models_download
                        }
                    )
                action.isEnabled = state != ModelManager.State.Installing
                action.setOnClickListener {
                    when {
                        state == ModelManager.State.Installed -> ModelManager.delete(context, model)
                        busy -> ModelManager.cancel(context, model)
                        else -> {
                            ModelManager.cancel(context, model)
                            ModelManager.download(context, model, preferences.downloadOnWifiOnly)
                            if (
                                !model.isSpeaker &&
                                    !model.isTagger &&
                                    ModelManager.speechModel(context) == null
                            ) {
                                preferences.speechModel = model.id
                            }
                        }
                    }
                    refresh()
                }
            }
        }
    }
}
