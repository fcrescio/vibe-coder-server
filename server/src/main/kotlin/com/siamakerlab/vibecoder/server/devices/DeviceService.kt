package com.siamakerlab.vibecoder.server.devices

import com.siamakerlab.vibecoder.server.adb.AdbService
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
     * Capture a screenshot from [serial] via `adb exec-out screencap -p`.
     * Returns the raw PNG bytes, or null on failure.
     */
    fun screencap(serial: String): ByteArray? {
        return adb.rawBinary("-s", serial, "exec-out", "screencap", "-p")
    }

    /** Base64-encoded PNG data URI for inline <img> display. */
    fun screencapDataUri(serial: String): String? {
        val bytes = screencap(serial) ?: return null
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return "data:image/png;base64,$b64"
    }
}

data class DeviceInfo(
    val serial: String,
    val model: String,
    val product: String,
    val status: String,
)
