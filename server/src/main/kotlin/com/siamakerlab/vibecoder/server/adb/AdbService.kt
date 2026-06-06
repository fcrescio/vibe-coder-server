package com.siamakerlab.vibecoder.server.adb

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Manages ADB device discovery and host configuration.
 *
 * The ADB host is persisted in-memory (per server run) and can be configured
 * via the `/settings/adb` UI. When empty, `adb devices` is called without `-H`.
 */
class AdbService {

    @Volatile
    var adbHost: String = ""
        private set

    /** Persisted host from config — set once at startup. */
    fun initHost(host: String) {
        adbHost = host
        log.info { "adb host initialized: '${host}'" }
    }

    fun updateHost(host: String) {
        adbHost = host.trim()
    }

    /** Run `adb [-H <host>] devices -l` and parse the output. */
    fun listDevices(): AdbDevicesResult {
        val cmd = buildList {
            add(resolveAdb())
            addHostAndPort(this)
            add("devices"); add("-l")
        }
        return runCommand(cmd, timeoutSeconds = 10).parseDevices()
    }

    /** Run `adb [-H <host>] <args...>` and return raw output. */
    fun rawCommand(vararg args: String): AdbCommandResult {
        val cmd = buildList {
            add(resolveAdb())
            addHostAndPort(this)
            args.forEach { add(it) }
        }
        return runCommand(cmd, timeoutSeconds = 30)
    }

    /** Get a human-readable summary of connected devices for agent context. */
    fun deviceSummary(): String {
        val result = listDevices()
        if (!result.success) return "ADB: ${result.error ?: "unknown error"}"
        if (result.devices.isEmpty()) {
            val hostInfo = if (adbHost.isNotBlank()) " (host: $adbHost)" else ""
            return "No ADB devices connected$hostInfo."
        }
        val hostInfo = if (adbHost.isNotBlank()) " (ADB host: $adbHost)" else ""
        return result.devices.joinToString("; ") { d ->
            val model = d.model?.let { " $it" } ?: ""
            "${d.serial}$model (${d.status})"
        } + hostInfo
    }

    // region internals

    /** Add `-H <host> [-P <port>]` to the command list if [adbHost] is set. */
    private fun addHostAndPort(cmd: MutableList<String>) {
        if (adbHost.isBlank()) return
        val colon = adbHost.lastIndexOf(':')
        if (colon > 0 && colon < adbHost.length - 1) {
            val host = adbHost.substring(0, colon)
            val port = adbHost.substring(colon + 1)
            cmd.add("-H"); cmd.add(host)
            if (port.all { it.isDigit() }) {
                cmd.add("-P"); cmd.add(port)
            }
        } else {
            cmd.add("-H"); cmd.add(adbHost)
        }
    }

    private fun runCommand(cmd: List<String>, timeoutSeconds: Long): AdbCommandResult {
        log.info { "adb cmd: ${cmd.joinToString(" ")}" }
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!ok) {
                proc.destroyForcibly()
                log.warn { "adb timed out after ${timeoutSeconds}s: ${cmd.joinToString(" ")}" }
                AdbCommandResult(success = false, error = "ADB command timed out after ${timeoutSeconds}s")
            } else if (proc.exitValue() != 0) {
                log.warn { "adb exit ${proc.exitValue()}: ${output.lines().firstOrNull { it.isNotBlank() }}" }
                AdbCommandResult(success = false, error = output.lines().firstOrNull { it.isNotBlank() } ?: "exit code ${proc.exitValue()}")
            } else {
                AdbCommandResult(success = true, output = output)
            }
        } catch (e: IOException) {
            log.warn(e) { "adb io error" }
            AdbCommandResult(success = false, error = "ADB not found or failed: ${e.message}")
        } catch (e: Exception) {
            log.warn(e) { "adb error" }
            AdbCommandResult(success = false, error = e.message ?: "unknown error")
        }
    }

    private fun resolveAdb(): String {
        val env = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        if (env != null) return "$env/platform-tools/adb"
        return "adb"
    }

    // endregion
}

data class AdbDevice(
    val serial: String,
    val status: String,
    val model: String?,
    val product: String?,
    val transportId: String?,
)

data class AdbDevicesResult(
    val success: Boolean,
    val devices: List<AdbDevice> = emptyList(),
    val error: String? = null,
)

data class AdbCommandResult(
    val success: Boolean,
    val output: String = "",
    val error: String? = null,
) {
    /** Parse `adb devices -l` output into structured device list. */
    fun parseDevices(): AdbDevicesResult {
        if (!success) return AdbDevicesResult(success = false, error = error)
        val lines = output.lines()
        // First line should be "List of devices attached"
        val deviceLines = lines.drop(1).filter { it.isNotBlank() && !it.startsWith("*") }
        if (deviceLines.isEmpty()) return AdbDevicesResult(success = true, devices = emptyList())

        val devices = deviceLines.mapNotNull { line ->
            // Format: <serial>  <status> [product:...] [model:...] [transport_id:...]
            // Serial and status are separated by whitespace (spaces or tabs).
            // Find the first significant whitespace separator.
            val sep = line.indexOfFirst { it.isWhitespace() && it != ' ' }
                .takeIf { it >= 0 }
                ?: line.indexOf("  ").takeIf { it >= 0 }
                ?: line.indexOf(' ')
            if (sep < 0) return@mapNotNull null
            val serial = line.substring(0, sep).trim()
            val rest = line.substring(sep).trim()
            val status = rest.substringBefore(" ").trim()
            val props = rest.substringAfter(" ", "").trim()
            val model = Regex("""model:(\S+)""").find(props)?.groupValues?.get(1)
            val product = Regex("""product:(\S+)""").find(props)?.groupValues?.get(1)
            val transportId = Regex("""transport_id:(\S+)""").find(props)?.groupValues?.get(1)
            AdbDevice(serial, status, model, product, transportId)
        }
        return AdbDevicesResult(success = true, devices = devices)
    }
}
