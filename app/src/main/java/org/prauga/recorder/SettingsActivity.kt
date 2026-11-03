/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder

import android.media.AudioDeviceInfo
import android.media.AudioManager
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

            setupMicrophones()

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

        private fun setupMicrophones() {
            val pref =
                findPreference<ListPreference>(PreferencesManager.PREF_INPUT_DEVICE) ?: return
            val devices =
                requireContext()
                    .getSystemService(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .filter { it.type in SUPPORTED_INPUTS }
                    .distinctBy { it.type to it.productName.toString() }
            pref.entries =
                arrayOf(getString(R.string.settings_microphone_auto)) +
                    devices.map { describe(it) }.toTypedArray()
            pref.entryValues = arrayOf("0") + devices.map { it.id.toString() }.toTypedArray()
            if (pref.findIndexOfValue(pref.value) < 0) pref.value = "0"
        }

        private fun describe(device: AudioDeviceInfo) =
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> getString(R.string.settings_microphone_builtin)
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> getString(R.string.settings_microphone_wired)
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> getString(R.string.settings_microphone_usb)
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET ->
                    getString(R.string.settings_microphone_bluetooth, device.productName)
                else -> getString(R.string.settings_microphone_other, device.productName)
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

        private val SUPPORTED_INPUTS =
            setOf(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
    }
}
