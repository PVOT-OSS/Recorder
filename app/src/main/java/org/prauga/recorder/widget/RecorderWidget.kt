/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.prauga.recorder.ListActivity
import org.prauga.recorder.R
import org.prauga.recorder.RecorderActivity
import org.prauga.recorder.service.SoundRecorderService

class RecorderWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        private fun views(context: Context) =
            RemoteViews(context.packageName, R.layout.widget_recorder).apply {
                val recording = SoundRecorderService.isRecording
                setImageViewResource(
                    R.id.widgetRecordButton,
                    if (recording) R.drawable.ic_stop else R.drawable.ic_mic,
                )
                setContentDescription(
                    R.id.widgetRecordButton,
                    context.getString(if (recording) R.string.stop else R.string.shortcut_record),
                )
                setOnClickPendingIntent(
                    R.id.widgetRecordButton,
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, RecorderActivity::class.java)
                            .setAction(RecorderActivity.ACTION_TOGGLE_RECORDING)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                setOnClickPendingIntent(
                    R.id.widgetListButton,
                    PendingIntent.getActivity(
                        context,
                        1,
                        Intent(context, ListActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecorderWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }
    }
}
