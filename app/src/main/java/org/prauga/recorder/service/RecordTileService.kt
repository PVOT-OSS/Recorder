/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.prauga.recorder.R
import org.prauga.recorder.RecorderActivity

class RecordTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            val recording = SoundRecorderService.isRecording
            state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            icon =
                Icon.createWithResource(
                    this@RecordTileService,
                    if (recording) R.drawable.ic_stop else R.drawable.ic_mic,
                )
            label = getString(R.string.tile_label)
            subtitle = if (recording) getString(R.string.sound_recording_title_working) else null
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent =
            Intent(this, RecorderActivity::class.java)
                .setAction(RecorderActivity.ACTION_TOGGLE_RECORDING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun refresh(context: Context) =
            requestListeningState(context, ComponentName(context, RecordTileService::class.java))
    }
}
