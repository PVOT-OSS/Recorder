/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.app.Application
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.prauga.recorder.audio.RecordingRepair
import org.prauga.recorder.utils.PreferencesManager

class RecorderApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Observe dynamic colors changes
        DynamicColors.applyToActivitiesIfAvailable(this)

        PreferencesManager.applyTheme(PreferencesManager(this).theme)

        // No recording can be running yet: whatever is left in the work dir was interrupted
        appScope.launch { RecordingRepair.repairAll(this@RecorderApplication) }
    }

    companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
