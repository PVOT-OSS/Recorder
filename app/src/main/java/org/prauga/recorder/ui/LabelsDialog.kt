/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.ui

import android.app.Activity
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.prauga.recorder.R

object LabelsDialog {
    fun show(
        activity: Activity,
        current: List<String>,
        known: List<String>,
        onSave: (List<String>) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val chips = ChipGroup(activity)
        fun addChip(label: String, checked: Boolean) {
            if ((0 until chips.childCount).any { (chips.getChildAt(it) as Chip).text == label }) {
                return
            }
            chips.addView(
                Chip(activity).apply {
                    text = label
                    isCheckable = true
                    isChecked = checked
                }
            )
        }
        (known + current).distinct().sorted().forEach { addChip(it, it in current) }

        val input =
            TextInputEditText(activity).apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
                isSingleLine = true
            }
        val inputLayout =
            TextInputLayout(activity).apply {
                hint = activity.getString(R.string.labels_new)
                addView(input)
            }
        input.setOnEditorActionListener { _, _, _ ->
            input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { addChip(it, true) }
            input.text = null
            true
        }

        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(chips)
                addView(inputLayout)
            }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.labels_title)
            .setView(content)
            .setPositiveButton(R.string.labels_save) { _, _ ->
                input.text
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { addChip(it, true) }
                val selected =
                    (0 until chips.childCount)
                        .map { chips.getChildAt(it) as Chip }
                        .filter { it.isChecked }
                        .map { it.text.toString() }
                onSave(selected)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
