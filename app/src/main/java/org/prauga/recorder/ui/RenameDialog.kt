/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.ui

import android.app.Activity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.prauga.recorder.R
import org.prauga.recorder.ext.scheduleShowSoftInput

object RenameDialog {
    /** [suggestions], if given, offers a "Suggest" button that cycles through them. */
    fun show(
        activity: Activity,
        title: String,
        suggestions: List<String> = emptyList(),
        onRename: (String) -> Unit,
    ) {
        lateinit var alertDialog: AlertDialog
        lateinit var editText: EditText

        val onConfirm = {
            editText.text
                ?.takeIf { it.isNotEmpty() }
                ?.let { editable ->
                    onRename(editable.toString())
                    true
                } ?: false
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_content_rename, null, false)
        editText =
            view.findViewById<EditText>(R.id.nameEditText).apply {
                setText(title)
                setSelection(0, title.length)
                setOnEditorActionListener { _, actionId, _ ->
                    when (actionId) {
                        EditorInfo.IME_ACTION_UNSPECIFIED,
                        EditorInfo.IME_ACTION_DONE -> {
                            onConfirm().also {
                                if (it) {
                                    alertDialog.dismiss()
                                }
                            }
                        }

                        else -> false
                    }
                }
            }

        val builder =
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.list_edit_title)
                .setView(view)
                .setPositiveButton(R.string.list_edit_confirm) { _, _ -> onConfirm() }
                .setNegativeButton(R.string.cancel, null)
        if (suggestions.isNotEmpty()) {
            builder.setNeutralButton(R.string.rename_suggest, null)
        }
        alertDialog =
            builder.show().also {
                editText.requestFocus()
                activity
                    .getSystemService(InputMethodManager::class.java)
                    .scheduleShowSoftInput(editText, 0)
            }
        var next = 0
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val suggestion = suggestions[next++ % suggestions.size]
            editText.setText(suggestion)
            editText.setSelection(suggestion.length)
        }
    }
}
