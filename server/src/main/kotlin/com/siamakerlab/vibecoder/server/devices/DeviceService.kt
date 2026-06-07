package com.siamakerlab.vibecoder.server.devices

import com.siamakerlab.vibecoder.server.adb.AdbService
import com.siamakerlab.vibecoder.server.adb.AdbCommandResult
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64

private val log = KotlinLogging.logger {}

/**
 * High-level operations on ADB-connected Android devices.
 *
 * Wraps [AdbService] with device-specific actions: listing, screencap, etc.
 */
class DeviceService(private val adb: AdbService) {

    /** List connected devices with parsed details. */
    fun listDevices(): List<DeviceInfo> {
        val result = adb.listDevices()
        if (!result.success) return emptyList()
        return result.devices.map { d ->
            DeviceInfo(
                serial = d.serial,
                model = d.model ?: "unknown",
                product = d.product ?: "unknown",
                status = d.status,
            )
        }
    }

    /**
     * Wake up and unlock the device screen.
     * Sends KEYCODE_WAKEUP to turn the screen on, then KEYCODE_MENU to
     * dismiss the lock screen (works for swipe-to-unlock; PIN/pattern
     * will still show the lock screen but at least the screen is on).
     */
    fun wakeUpAndUnlock(serial: String) {
        adb.rawCommand("-s", serial, "shell", "input", "keyevent", "224") // KEYCODE_WAKEUP
        adb.rawCommand("-s", serial, "shell", "input", "keyevent", "82")  // KEYCODE_MENU
    }

    /**
     * Launch an installed application without exposing raw ADB to the agent.
     *
     * When [activity] is provided it may be a fully-qualified activity or a short
     * ".MainActivity" suffix. Without an activity, Android's monkey launcher is
     * used to resolve the package's default launcher activity.
     */
    fun launchApp(serial: String, packageName: String, activity: String? = null): AdbCommandResult {
        wakeUpAndUnlock(serial)
        val trimmedActivity = activity?.trim()?.takeIf { it.isNotBlank() }
        val result = if (trimmedActivity != null) {
            val component = when {
                trimmedActivity.contains("/") -> trimmedActivity
                trimmedActivity.startsWith(".") -> "$packageName/$trimmedActivity"
                else -> "$packageName/$trimmedActivity"
            }
            adb.rawCommand("-s", serial, "shell", "am", "start", "-W", "-n", component)
        } else {
            adb.rawCommand(
                "-s", serial, "shell", "monkey",
                "-p", packageName,
                "-c", "android.intent.category.LAUNCHER",
                "1",
            )
        }
        return normalizeLaunchResult(result)
    }

    private fun normalizeLaunchResult(result: AdbCommandResult): AdbCommandResult {
        if (!result.success) return result
        val firstError = result.output.lines().firstOrNull { line ->
            line.contains("Error:", ignoreCase = true) ||
                line.contains("Error type", ignoreCase = true) ||
                line.contains("does not exist", ignoreCase = true) ||
                line.contains("No activities found", ignoreCase = true) ||
                line.contains("monkey aborted", ignoreCase = true)
        } ?: return result
        return result.copy(success = false, error = firstError)
    }

    /**
     * Capture a screenshot from [serial] via `adb exec-out screencap -p`.
     * Wakes and unlocks the device first.
     * Returns the raw PNG bytes, or null on failure.
     */
    fun screencap(serial: String): ByteArray? {
        wakeUpAndUnlock(serial)
        return adb.rawBinary("-s", serial, "exec-out", "screencap", "-p")
    }

    /** Base64-encoded PNG data URI for inline <img> display. */
    fun screencapDataUri(serial: String): String? {
        val bytes = screencap(serial) ?: return null
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return "data:image/png;base64,$b64"
    }

    /** Delegate to [AdbService.deviceSummary]. */
    fun deviceSummary(): String = adb.deviceSummary()

    /**
     * Send a tap event at (x, y) on [serial].
     * Uses `input tap x y`.
     */
    fun tap(serial: String, x: Int, y: Int): AdbCommandResult {
        return adb.rawCommand("-s", serial, "shell", "input", "tap", x.toString(), y.toString())
    }

    /**
     * Send a swipe gesture from (x1, y1) to (x2, y2) over [durationMs] milliseconds.
     * Uses `input swipe x1 y1 x2 y2 [duration]`.
     */
    fun swipe(serial: String, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): AdbCommandResult {
        return adb.rawCommand("-s", serial, "shell", "input", "swipe",
            x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
    }

    /**
     * Capture a screenshot and return the raw PNG bytes as a base64-encoded string.
     * Returns null on failure.
     */
    fun screencapBase64(serial: String): String? {
        val bytes = screencap(serial) ?: return null
        return Base64.getEncoder().encodeToString(bytes)
    }
}

data class DeviceInfo(
    val serial: String,
    val model: String,
    val product: String,
    val status: String,
)
