package com.siamakerlab.vibecoder.server.adb

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * `/settings/adb` SSR — ADB host configuration + connected device list.
 *
 * GET  /settings/adb  — show form + device table
 * POST /settings/adb  — update ADB host, then redirect
 */
fun Routing.adbSettingsRoutes(authDeps: AdminRoutesDeps, adb: AdbService) {
    get("/settings/adb") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(
            AdbSettingsTemplates.page(sess.username, adb, sess.csrf, ok, err, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    post("/settings/adb") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val params = requireCsrf()
        val host = params["host"] ?: ""
        adb.updateHost(host)
        call.respondRedirect("/settings/adb?ok=saved")
    }
}

object AdbSettingsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        adb: AdbService,
        csrf: String?,
        ok: String?,
        err: String?,
        lang: String,
    ): String {
        val okHtml = ok?.let {
            when (it) {
                "saved" -> """<div class="ok-banner">✓ ADB host saved.</div>"""
                else -> """<div class="ok-banner">✓ $it</div>"""
            }
        } ?: ""
        val errHtml = err?.let { """<div class="error">$it</div>""" } ?: ""

        val devicesResult = adb.listDevices()
        val deviceRows = if (devicesResult.success) {
            if (devicesResult.devices.isEmpty()) {
                """<tr><td colspan="4" class="dim" style="text-align:center;padding:16px">No devices connected.</td></tr>"""
            } else {
                devicesResult.devices.joinToString("\n") { d ->
                    val model = esc(d.model)
                    val product = esc(d.product)
                    val serial = esc(d.serial)
                    val status = esc(d.status)
                    val statusClass = if (d.status == "device") "ok" else "warn"
                    """<tr><td><code>$serial</code></td><td>$model</td><td>$product</td><td><span class="$statusClass">$status</span></td></tr>"""
                }
            }
        } else {
            val error = esc(devicesResult.error)
            """<tr><td colspan="4" class="warn" style="text-align:center;padding:16px">ADB error: $error</td></tr>"""
        }

        val hostInfo = if (adb.adbHost.isNotBlank()) " (host: ${esc(adb.adbHost)})" else ""

        return AdminTemplates.shell(
            title = "ADB Devices",
            username = username,
            currentPath = "/settings/adb",
            csrf = csrf,
            body = """
<header>
  <h1>ADB Devices$hostInfo</h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">
    Connected Android devices via <code>adb devices -l</code>.
    Set a custom ADB host to connect to a remote ADB server.
  </p>
</header>

$okHtml
$errHtml

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">ADB Host</h2>
  <form method="post" action="/settings/adb" style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
    ${CsrfTokens.hiddenInput(csrf)}
    <input type="text" name="host" value="${esc(adb.adbHost)}"
           placeholder="Leave empty for local ADB"
           style="width:280px;padding:8px;font-family:monospace">
    <button type="submit" class="primary" style="padding:8px 16px;flex-shrink:0">Save</button>
  </form>
  <p class="dim" style="font-size:12px;margin:6px 0 0">
    Format: <code>host:port</code> (e.g. <code>192.168.1.100:5037</code>).
    Leave empty to use the local ADB server.
  </p>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">Connected Devices</h2>
  <table style="width:100%;border-collapse:collapse">
    <thead>
      <tr>
        <th style="text-align:left;padding:6px 8px;border-bottom:1px solid var(--border)">Serial</th>
        <th style="text-align:left;padding:6px 8px;border-bottom:1px solid var(--border)">Model</th>
        <th style="text-align:left;padding:6px 8px;border-bottom:1px solid var(--border)">Product</th>
        <th style="text-align:left;padding:6px 8px;border-bottom:1px solid var(--border)">Status</th>
      </tr>
    </thead>
    <tbody>
      $deviceRows
    </tbody>
  </table>
  <div style="margin-top:10px">
    <a href="/settings/adb" class="button secondary" style="padding:6px 14px;font-size:13px">↻ Refresh</a>
  </div>
</div>

<div class="card" style="background:rgba(80,150,255,0.06)">
  <h2 style="margin-top:0">Agent Context</h2>
  <p class="dim" style="font-size:13px">
    Connected device information is automatically injected into the LLM prompt context
    so agents are aware of available Android devices.
  </p>
</div>
""",
            lang = lang,
        )
    }
}
