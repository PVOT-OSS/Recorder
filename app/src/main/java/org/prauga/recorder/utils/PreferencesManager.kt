/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.utils

import android.content.Context
import android.net.Uri
import org.prauga.recorder.audio.QualityPreset

class PreferencesManager(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var qualityPreset: QualityPreset
        get() =
            when {
                preferences.contains(PREF_QUALITY_PRESET) ->
                    QualityPreset.fromKey(preferences.getString(PREF_QUALITY_PRESET, null))
                preferences.getInt(PREF_RECORDING_QUALITY, 0) == 1 -> QualityPreset.LOSSLESS
                else -> QualityPreset.STANDARD
            }
        set(value) {
            preferences.edit().putString(PREF_QUALITY_PRESET, value.key).apply()
        }

    var recordInStereo: Boolean
        get() = preferences.getBoolean(PREF_STEREO, false)
        set(value) {
            preferences.edit().putBoolean(PREF_STEREO, value).apply()
        }

    val qualityConfig
        get() = qualityPreset.let { it.withChannels(if (recordInStereo) it.channels else 1) }

    var clearVoice: Boolean
        get() = preferences.getBoolean(PREF_CLEAR_VOICE, false)
        set(value) {
            preferences.edit().putBoolean(PREF_CLEAR_VOICE, value).apply()
        }

    /** [android.media.AudioDeviceInfo.getId] of the preferred input, or 0 for automatic. */
    var inputDeviceId: Int
        get() = preferences.getString(PREF_INPUT_DEVICE, "0")?.toIntOrNull() ?: 0
        set(value) {
            preferences.edit().putString(PREF_INPUT_DEVICE, value.toString()).apply()
        }

    var tagWithLocation: Boolean
        get() = preferences.getBoolean(PREF_TAG_WITH_LOCATION, false)
        set(tagWithLocation) {
            preferences.edit().putBoolean(PREF_TAG_WITH_LOCATION, tagWithLocation).apply()
        }

    var onboardSettingsCounter: Int
        get() = preferences.getInt(PREF_ONBOARD_SETTINGS_COUNTER, 0)
        set(value) {
            preferences.edit().putInt(PREF_ONBOARD_SETTINGS_COUNTER, value).apply()
        }

    var onboardListCounter: Int
        get() = preferences.getInt(PREF_ONBOARD_SOUND_LIST_COUNTER, 0)
        set(value) {
            preferences.edit().putInt(PREF_ONBOARD_SOUND_LIST_COUNTER, value).apply()
        }

    var lastItemUri: Uri?
        get() {
            val uriStr = preferences.getString(PREF_LAST_SOUND, null)
            return if (uriStr == null) null else Uri.parse(uriStr)
        }
        set(value) {
            preferences.edit().putString(PREF_LAST_SOUND, value?.toString()).apply()
        }

    companion object {
        private const val PREFS = "preferences"
        private const val PREF_TAG_WITH_LOCATION = "tag_with_location"
        private const val PREF_RECORDING_QUALITY = "recording_quality"
        const val PREF_QUALITY_PRESET = "quality_preset"
        const val PREF_STEREO = "stereo"
        const val PREF_CLEAR_VOICE = "clear_voice"
        const val PREF_INPUT_DEVICE = "input_device"
        private const val PREF_ONBOARD_SETTINGS_COUNTER = "onboard_settings"
        private const val PREF_ONBOARD_SOUND_LIST_COUNTER = "onboard_list"
        private const val PREF_LAST_SOUND = "sound_last_path"
    }
}
