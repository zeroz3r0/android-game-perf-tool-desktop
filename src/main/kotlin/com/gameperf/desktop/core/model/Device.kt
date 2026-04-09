package com.gameperf.desktop.core.model

/**
 * Platform identifier for connected devices.
 * Used by [Device], [DeviceInfo], and routing logic in CompositeBridge.
 */
enum class DevicePlatform {
    ANDROID,
    IOS,
}

/**
 * Platform-agnostic representation of a connected device.
 * Replaces the Android-specific `AdbBridge.Device` for cross-platform consumers
 * (AppViewModel, HomeScreen, ReportGenerator).
 *
 * @property id Unique device identifier — ADB serial for Android, UDID for iOS.
 * @property model Human-readable model name (e.g. "Pixel 6", "iPhone 15 Pro").
 * @property platform Which platform this device belongs to.
 * @property isWifi Whether this device is connected over WiFi (Android-only; always false for iOS).
 */
data class Device(
    val id: String,
    val model: String,
    val platform: DevicePlatform,
    val isWifi: Boolean = false,
)
