/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.materialswitch.MaterialSwitch
import org.prauga.recorder.transcription.ModelsActivity
import org.prauga.recorder.utils.PermissionManager
import org.prauga.recorder.utils.PreferencesManager

/** Shown once: what stays on the phone, optional models, location opt-in, then permissions. */
class OnboardingActivity : AppCompatActivity(R.layout.activity_onboarding) {
    private val preferences by lazy { PreferencesManager(this) }
    private val permissionManager by lazy { PermissionManager(this) }
    private val locationSwitch by lazy {
        findViewById<MaterialSwitch>(R.id.onboardingLocationSwitch)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.onboardingRoot)) {
            v,
            insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = bars.top,
                bottom = bars.bottom,
                left = bars.left,
                right = bars.right,
            )
            insets
        }
        findViewById<Button>(R.id.onboardingModelsButton).setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
        locationSwitch.isChecked = preferences.tagWithLocation
        locationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !permissionManager.hasLocationPermission()) {
                permissionManager.requestLocationPermission()
            } else {
                preferences.tagWithLocation = checked
            }
        }
        findViewById<Button>(R.id.onboardingDoneButton).setOnClickListener {
            preferences.onboarded = true
            if (!permissionManager.requestEssentialPermissions()) finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (preferences.onboarded) {
            finish()
            return
        }
        preferences.tagWithLocation =
            permissionManager.hasLocationPermission() && locationSwitch.isChecked
        locationSwitch.isChecked = preferences.tagWithLocation
    }
}
