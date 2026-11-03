/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Entry for the start/stop/toggle actions (tile, widget, shortcut, automation apps). Forwards to
 * [RecorderActivity] with CLEAR_TOP so the action is delivered even if another screen of ours is on
 * top of the task.
 */
class RecordActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, RecorderActivity::class.java)
                .setAction(intent.action)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
        finish()
    }
}
