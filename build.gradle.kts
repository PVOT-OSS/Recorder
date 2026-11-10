/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
}

ext {
    val versionMajor = 1
    val versionMinor = 1
    val versionPatch = 0

    set("versionMajor", versionMajor)
    set("versionMinor", versionMinor)
    set("versionPatch", versionPatch)
    set("computedVersionCode", versionMajor * 10000 + versionMinor * 100 + versionPatch)
    set("computedVersionName", "$versionMajor.$versionMinor.$versionPatch")
}

tasks.register<Delete>("clean").configure { delete(rootProject.layout.buildDirectory) }
