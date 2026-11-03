/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.utils

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.os.bundleOf
import org.prauga.recorder.DeleteLastActivity

object RecordIntentHelper {
    fun getShareIntent(uri: Uri?, mimeType: String?): Intent {
        val intent = Intent(Intent.ACTION_SEND)
        intent.setType(mimeType)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        uri?.let { intent.clipData = ClipData.newRawUri(null, it) }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooserIntent = Intent.createChooser(intent, null)
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        return chooserIntent
    }

    fun getShareIntents(uris: List<Uri>, mimeType: String?): Intent {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        intent.setType(mimeType)
        intent.putExtras(bundleOf(Intent.EXTRA_STREAM to ArrayList(uris)))
        uris.firstOrNull()?.let { first ->
            intent.clipData =
                ClipData.newRawUri(null, first).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooserIntent = Intent.createChooser(intent, null)
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        return chooserIntent
    }

    fun getOpenIntent(uri: Uri?, mimeType: String?): Intent {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mimeType)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    fun getDeleteIntent(context: Context?): Intent {
        return Intent(context, DeleteLastActivity::class.java)
    }
}
