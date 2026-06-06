package com.siamakerlab.vibecoder.server.devices

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

fun Routing.deviceRoutes(authDeps: AdminRoutesDeps, devices: DeviceService) {
    get("/devices/adb") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            DeviceTemplates.page(sess.username, devices, sess.csrf, lang = sess.language),
            ContentType.Text.Html,
        )
    }
}

object DeviceTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        devices: DeviceService,
        csrf: String?,
        lang: String,
    ): String {
        val deviceList = devices.listDevices()
        val deviceCards = if (deviceList.isEmpty()) {
            """<div class="card" style="text-align:center;padding:24px"><p class="dim">No ADB devices connected.</p></div>"""
        } else {
            deviceList.joinToString("\n") { d ->
                deviceCard(d, devices)
            }
        }

        return AdminTemplates.shell(
            title = "Devices",
            username = username,
            currentPath = "/devices/adb",
            csrf = csrf,
            body = """
<header>
  <h1>Devices</h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">
    Connected Android devices via ADB. Click a device to see available actions.
  </p>
</header>

<div style="display:flex;flex-direction:column;gap:14px">
$deviceCards
</div>
""",
            lang = lang,
        )
    }

    private fun deviceCard(d: DeviceInfo, devices: DeviceService): String {
        val serial = esc(d.serial)
        val model = esc(d.model)
        val product = esc(d.product)
        val statusClass = if (d.status == "device") "ok" else "warn"
        val status = esc(d.status)

        // Try to get a screenshot
        val screenshotDataUri = runCatching { devices.screencapDataUri(d.serial) }.getOrNull()

        val screenshotHtml = if (screenshotDataUri != null) {
            """<div style="margin-top:10px"><img src="$screenshotDataUri" alt="Screenshot of $serial" style="max-width:100%;max-height:480px;border:1px solid var(--border);border-radius:6px"></div>"""
        } else {
            """<p class="dim" style="margin-top:10px">Screenshot unavailable.</p>"""
        }

        return """
<div class="card">
  <div style="display:flex;justify-content:space-between;align-items:center">
    <div>
      <strong style="font-size:15px">$model</strong>
      <span class="dim" style="margin-left:8px"><code>$serial</code></span>
      <span class="$statusClass" style="margin-left:8px">$status</span>
    </div>
    <div style="display:flex;gap:6px">
      <a href="/devices/$serial/screencap" class="button secondary" style="padding:6px 12px;font-size:12px">📷 Screenshot</a>
    </div>
  </div>
  <div style="margin-top:6px;font-size:12px;color:var(--dim)">
    Product: <code>$product</code>
  </div>
  $screenshotHtml
</div>
"""
    }
}
