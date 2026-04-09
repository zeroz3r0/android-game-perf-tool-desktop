package com.gameperf.desktop.core.model

/**
 * Platform-agnostic device hardware information.
 * Replaces the Android-specific `AdbBridge.DeviceInfo`.
 *
 * @property osVersion SDK int as string for Android (e.g. "33"), iOS version string for iOS (e.g. "17.4").
 * @property platform Which platform this device belongs to.
 */
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val cpu: String,
    val gpu: String,
    val ram: String,
    val cores: Int,
    val osVersion: String,
    val resolution: String,
    val platform: DevicePlatform,
)
