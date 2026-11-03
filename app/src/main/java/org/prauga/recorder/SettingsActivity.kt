/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.prauga.recorder.utils.PermissionManager
import org.prauga.recorder.utils.PreferencesManager

class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.settings_title)

        val container = findViewById<android.view.View>(R.id.settingsContainer)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val permissionManager by lazy { PermissionManager(requireActivity()) }
        private val preferences by lazy { PreferencesManager(requireContext()) }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = PreferencesManager.PREFS
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<ListPreference>(PreferencesManager.PREF_QUALITY_PRESET)?.value =
                preferences.qualityPreset.key

            findPreference<SwitchPreferenceCompat>(PreferencesManager.PREF_TAG_WITH_LOCATION)
                ?.apply {
                    isChecked =
                        preferences.tagWithLocation && permissionManager.hasLocationPermission()
                    setOnPreferenceChangeListener { _, value ->
                        if (value == true && !permissionManager.hasLocationPermission()) {
                            permissionManager.requestLocationPermission()
                            false
                        } else {
                            true
                        }
                    }
                }

            findPreference<ListPreference>(PreferencesManager.PREF_THEME)
                ?.setOnPreferenceChangeListener { _, value ->
                    PreferencesManager.applyTheme(value as String)
                    true
                }

            findPreference<Preference>("version")?.summary =
                requireContext()
                    .packageManager
                    .getPackageInfo(requireContext().packageName, 0)
                    .versionName

            val isRecording = requireActivity().intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
            findPreference<PreferenceCategory>("category_recording")?.let {
                it.isEnabled = !isRecording
                if (isRecording) {
                    it.summary = getString(R.string.settings_disabled_while_recording)
                }
            }
        }

        override fun onResume() {
            super.onResume()
            if (permissionManager.hasLocationPermission() && preferences.tagWithLocation) {
                findPreference<SwitchPreferenceCompat>(PreferencesManager.PREF_TAG_WITH_LOCATION)
                    ?.isChecked = true
            }
        }
    }

    companion object {
        const val EXTRA_IS_RECORDING = "is_recording"
    }
}
