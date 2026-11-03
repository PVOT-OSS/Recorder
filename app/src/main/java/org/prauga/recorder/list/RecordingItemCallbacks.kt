/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.list

import org.prauga.recorder.models.Recording

interface RecordingItemCallbacks {
    fun onPlay(recording: Recording)
    fun onShare(recording: Recording)
    fun onDelete(recording: Recording)
    fun onRename(recording: Recording)
}
