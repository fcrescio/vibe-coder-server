package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.ProjectAclRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * v0.49.0 — Project ACL management UI. Admin-only.
 *
 *   GET  /users/{userId}/projects       — checkbox list of every project, pre-checked from ACL
 *   POST /users/{userId}/projects       — bulk replace ACL via form (projectIds=… repeat)
 *
 * Semantics (see [ProjectAclRepository]):
 *   - No ACL rows for a user → user sees all projects (default).
 *   - 1+ ACL rows → user sees only those.
 */
fun Routing.projectAclRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    userRepo: AdminUserRepository,
    aclRepo: ProjectAclRepository,
) {
    get("/users/{userId}/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val targetUserId = call.parameters["userId"]
            ?: return@get call.respondRedirect("/users?err=missing_id")
        val target = userRepo.findById(targetUserId)
            ?: return@get call.respondRedirect("/users?err=user_not_found")

        val all = projects.list()  // admin-perspective full list
        val granted = aclRepo.listForUser(targetUserId).toSet()
        val unrestricted = granted.isEmpty()
        val ok = call.request.queryParameters["ok"]

        val rows = all.joinToString("\n") { p ->
            val checked = if (granted.contains(p.id)) "checked" else ""
            """
            <label class="acl-row" style="display:flex;gap:10px;align-items:center;padding:8px;border-bottom:1px solid #222">
              <input type="checkbox" name="projectIds" value="${esc(p.id)}" $checked>
              <div>
                <strong>${esc(p.name)}</strong>
                <div class="dim" style="font-size:11px">${esc(p.id)} · ${esc(p.packageName)}</div>
              </div>
            </label>"""
        }

        val t = { key: String -> Messages.t(sess.language, key) }
        val statusBadge = if (unrestricted)
            """<span class="warn">${esc(t("acl.unrestricted"))}</span>"""
        else
            """<span class="ok">${esc(Messages.t(sess.language, "acl.allowedCount", granted.size))}</span>"""

        val body = """
<header>
  <h1>${esc(t("acl.title"))} <small class="dim" style="font-size:14px;font-weight:400">${esc(target.username)}</small></h1>
</header>

${ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""}

<div class="card" style="margin-bottom:16px">
  <p style="margin:0 0 6px"><strong>${esc(t("acl.currentState"))} $statusBadge</strong></p>
  <p class="dim" style="margin:0;font-size:12px">
    ${t("acl.hint")}
  </p>
</div>

<form method="post" action="/users/${esc(target.id)}/projects" class="card">
  ${CsrfTokens.hiddenInput(sess.csrf)}
  $rows
  <div style="display:flex;gap:8px;margin-top:14px;justify-content:flex-end">
    <a href="/users" class="chip chip-link">${esc(t("acl.backToUsers"))}</a>
    <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(t("acl.saveBtn"))}</button>
  </div>
</form>
"""
        call.respondText(
            AdminTemplates.shell(
                title = Messages.t(sess.language, "acl.titleWithUsername", target.username),
                username = sess.username,
                currentPath = "/users",
                csrf = sess.csrf,
                lang = sess.language,
                body = body,
            ),
            ContentType.Text.Html,
        )
    }

    post("/users/{userId}/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val targetUserId = call.parameters["userId"]
            ?: return@post call.respondRedirect("/users?err=missing_id")
        val target = userRepo.findById(targetUserId)
            ?: return@post call.respondRedirect("/users?err=user_not_found")
        val form = requireCsrf()
        val selected = form.getAll("projectIds")?.toSet() ?: emptySet()
        aclRepo.replaceForUser(target.id, selected, grantedBy = sess.userId)
        val okMsg = if (selected.isEmpty()) Messages.t(sess.language, "acl.flash.removed")
            else Messages.t(sess.language, "acl.flash.updated", selected.size)
        call.respondRedirect("/users/${target.id}/projects?ok=${java.net.URLEncoder.encode(okMsg, Charsets.UTF_8)}")
    }
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
