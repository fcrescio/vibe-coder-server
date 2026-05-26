package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * v1.5.0 — 키스토어 관리 라우트.
 *
 * SSR:
 *   GET  /settings/keystores               — 목록 + create form
 *   POST /settings/keystores               — 새 키스토어 set 생성
 *   POST /settings/keystores/{pkg}/delete  — 키스토어 set 삭제
 */
fun Routing.keystoreRoutes(authDeps: AdminRoutesDeps, service: KeystoreService) {
    get("/settings/keystores") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val entries = runCatching { service.list() }.getOrDefault(emptyList())
        val flash = call.request.queryParameters["flash"]
        call.respondText(
            KeystoreTemplates.page(
                username = sess.username,
                entries = entries,
                defaults = authDeps.config.keystore.defaults,
                flash = flash,
                csrf = sess.csrf,
                lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    post("/settings/keystores") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        com.siamakerlab.vibecoder.server.auth.CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val form = call.receiveParameters()
        val pkg = form["packageName"]?.trim().orEmpty()
        val req = CreateKeystoreRequest(
            packageName = pkg,
            name = form["name"]?.trim().orEmpty(),
            organization = form["organization"]?.trim().orEmpty(),
            unit = form["unit"]?.trim().orEmpty(),
            country = form["country"]?.trim().orEmpty(),
            state = form["state"]?.trim().orEmpty(),
            city = form["city"]?.trim().orEmpty(),
            password = form["password"]?.trim().orEmpty(),
            validityYears = form["validityYears"]?.trim()?.toIntOrNull(),
            admob = AdmobIds(
                appId = form["admobAppId"]?.trim().orEmpty(),
                appOpenUnitId = form["admobAppOpenUnitId"]?.trim().orEmpty(),
                bannerUnitId = form["admobBannerUnitId"]?.trim().orEmpty(),
                nativeUnitId = form["admobNativeUnitId"]?.trim().orEmpty(),
            ).takeIf {
                it.appId.isNotBlank() || it.bannerUnitId.isNotBlank() ||
                    it.appOpenUnitId.isNotBlank() || it.nativeUnitId.isNotBlank()
            },
        )
        val flash = runCatching { service.create(req) }
            .map { "ok:${it.packageName}" }
            .getOrElse { "err:${it.message ?: "create_failed"}" }
        call.respondRedirect("/settings/keystores?flash=$flash")
    }

    post("/settings/keystores/{pkg}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        com.siamakerlab.vibecoder.server.auth.CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val pkg = call.parameters["pkg"].orEmpty()
        runCatching { service.delete(pkg) }
            .onFailure { log.warn(it) { "keystore delete failed for $pkg" } }
        call.respondRedirect("/settings/keystores?flash=deleted:$pkg")
    }
}

internal object KeystoreTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        entries: List<KeystoreEntry>,
        defaults: KeystoreDefaults,
        flash: String?,
        csrf: String?,
        lang: String = "en",
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val csrfHidden = csrf?.let { """<input type="hidden" name="_csrf" value="${esc(it)}">""" } ?: ""
        val flashHtml = when {
            flash == null -> ""
            flash.startsWith("ok:") -> """<div class="flash ok">✓ ${esc(t("ks.flash.created"))} <code>${esc(flash.removePrefix("ok:"))}</code></div>"""
            flash.startsWith("deleted:") -> """<div class="flash ok">✓ ${esc(t("ks.flash.deleted"))} <code>${esc(flash.removePrefix("deleted:"))}</code></div>"""
            flash.startsWith("err:") -> """<div class="flash err">⚠ ${esc(flash.removePrefix("err:"))}</div>"""
            else -> ""
        }

        val rows = if (entries.isEmpty()) {
            """<tr><td colspan="4" class="dim" style="text-align:center;padding:18px">${esc(t("ks.empty"))}</td></tr>"""
        } else entries.joinToString("\n") { e ->
            val files = buildList {
                if (e.releaseExists) add("release")
                if (e.debugExists) add("debug")
                if (e.propertiesExists) add(".properties")
                if (e.admobExists) add("admob")
            }.joinToString(" · ")
            """<tr>
              <td><code>${esc(e.packageName)}</code></td>
              <td class="dim">${esc(files)}</td>
              <td class="dim" style="font-size:12px">${esc(e.createdAt ?: "—")}</td>
              <td style="text-align:right">
                <form method="post" action="/settings/keystores/${esc(e.packageName)}/delete"
                      style="display:inline" onsubmit="return confirm('${esc(t("ks.confirm.delete"))}')">
                  $csrfHidden
                  <button type="submit" class="chip chip-action" style="background:#7f1d1d;color:#fff">
                    ${esc(t("ks.delete"))}
                  </button>
                </form>
              </td>
            </tr>"""
        }

        val passwordPrefilled = if (defaults.defaultPassword.isNotBlank())
            """value="${esc(defaults.defaultPassword)}"""" else ""

        return AdminTemplates.shell(
            title = t("ks.title"),
            username = username,
            currentPath = "/settings/keystores",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("ks.title"))}</h1>
    <a href="/settings" class="chip chip-link">${esc(t("ks.backToSettings"))}</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">${esc(t("ks.intro"))}</p>
</header>

$flashHtml

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("ks.existing"))}</h2>
  <table style="width:100%;border-collapse:collapse">
    <thead>
      <tr style="border-bottom:1px solid #333">
        <th style="text-align:left;padding:8px">${esc(t("ks.col.package"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("ks.col.files"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("ks.col.created"))}</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      $rows
    </tbody>
  </table>
</div>

<div class="card">
  <h2 style="margin-top:0">${esc(t("ks.create.title"))}</h2>
  <p class="dim" style="font-size:13px;margin:0 0 12px">${esc(t("ks.create.hint"))}</p>
  <form method="post" action="/settings/keystores">
    $csrfHidden
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
      <label style="grid-column:1/3">
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.package"))}</div>
        <input name="packageName" required placeholder="com.siamakerlab.myapp"
               pattern="[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+"
               style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.password"))}</div>
        <input name="password" type="text" required $passwordPrefilled
               style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.validity"))}</div>
        <input name="validityYears" type="number" min="1" max="100"
               value="${defaults.validityYears}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.name"))}</div>
        <input name="name" value="${esc(defaults.name)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.organization"))}</div>
        <input name="organization" value="${esc(defaults.organization)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.unit"))}</div>
        <input name="unit" value="${esc(defaults.unit)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.country"))}</div>
        <input name="country" value="${esc(defaults.country)}" maxlength="2"
               style="width:100%;padding:8px;text-transform:uppercase">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.state"))}</div>
        <input name="state" value="${esc(defaults.state)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.city"))}</div>
        <input name="city" value="${esc(defaults.city)}" style="width:100%;padding:8px">
      </label>
    </div>

    <details style="margin-top:14px">
      <summary style="cursor:pointer;font-size:13px;color:#aaa">${esc(t("ks.admob.toggle"))}</summary>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:8px">
        <label style="grid-column:1/3">
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.appId"))}</div>
          <input name="admobAppId" placeholder="ca-app-pub-XXXX~YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
        <label>
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.appOpen"))}</div>
          <input name="admobAppOpenUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
        <label>
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.banner"))}</div>
          <input name="admobBannerUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
        <label>
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.native"))}</div>
          <input name="admobNativeUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
      </div>
    </details>

    <p class="dim" style="font-size:12px;margin:14px 0 8px">${esc(t("ks.create.warn"))}</p>
    <button type="submit" class="chip chip-action" style="background:#1e40af;color:#fff">
      ${esc(t("ks.create.button"))}
    </button>
  </form>
</div>

<div class="card" style="background:#1a1a0a;border-color:#525200;margin-top:14px">
  <h2 style="margin-top:0">${esc(t("ks.usage.title"))}</h2>
  <p>${esc(t("ks.usage.body"))}</p>
  <ol style="margin:8px 0 0 18px">
    <li>${esc(t("ks.usage.step1"))}</li>
    <li>${esc(t("ks.usage.step2"))}</li>
    <li>${esc(t("ks.usage.step3"))}</li>
  </ol>
</div>
""",
        )
    }
}
