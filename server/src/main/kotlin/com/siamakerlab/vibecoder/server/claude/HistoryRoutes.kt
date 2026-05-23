package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v0.16.0 — `/projects/{id}/history` + `/chat/history` 페이지.
 *
 * 일반 프로젝트 history: `/projects/{id}/history`
 * General Chat history (`__scratch__`): `/chat/history`
 */
fun Routing.historyRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    repo: ConversationTurnRepository,
) {
    get("/projects/{id}/history") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondText("project not found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound)
            return@get
        }
        renderHistory(call, sess.username, sess.csrf, id, p.name, isChat = false, repo = repo)
    }

    get("/chat/history") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val p = projects.ensureScratchProject()
        renderHistory(call, sess.username, sess.csrf, p.id, p.name, isChat = true, repo = repo)
    }
}

private suspend fun renderHistory(
    call: io.ktor.server.application.ApplicationCall,
    username: String,
    csrf: String?,
    projectId: String,
    displayName: String,
    isChat: Boolean,
    repo: ConversationTurnRepository,
) {
    val params = call.request.queryParameters
    val filter = ConversationTurnRepository.Filter(
        projectId = projectId,
        sessionId = params["session"]?.ifBlank { null },
        role = params["role"]?.ifBlank { null },
        toolName = params["tool"]?.ifBlank { null },
        fromTs = params["from"]?.ifBlank { null },
        toTs = params["to"]?.ifBlank { null },
        q = params["q"]?.ifBlank { null },
    )
    val page = (params["p"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
    val pageSize = 100
    val rows = repo.list(filter, limit = pageSize, offset = page * pageSize.toLong())
    val total = repo.count(filter)
    val sessions = repo.distinctSessions(projectId)
    call.respondText(
        HistoryTemplates.page(
            username = username,
            projectId = projectId,
            displayName = displayName,
            isChat = isChat,
            rows = rows,
            filter = filter,
            sessions = sessions,
            page = page,
            pageSize = pageSize,
            total = total,
            csrf = csrf,
        ),
        ContentType.Text.Html,
    )
}

object HistoryTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        projectId: String,
        displayName: String,
        isChat: Boolean,
        rows: List<com.siamakerlab.vibecoder.server.repo.ConversationTurnRow>,
        filter: ConversationTurnRepository.Filter,
        sessions: List<String>,
        page: Int,
        pageSize: Int,
        total: Long,
        csrf: String? = null,
    ): String {
        val navPath = if (isChat) "/chat" else "/projects"
        val backLink = if (isChat)
            """<a href="/chat" class="chip chip-link">← Chat</a>"""
        else
            """<a href="/projects/${esc(projectId)}" class="chip chip-link">← 프로젝트</a>
               <a href="/projects/${esc(projectId)}/console" class="chip chip-link">콘솔로</a>"""

        val sessionOpts = ("""<option value="">(all sessions)</option>""" +
            sessions.joinToString("") { s ->
                val sel = if (s == filter.sessionId) " selected" else ""
                """<option value="${esc(s)}"$sel>${esc(s.take(20))}…</option>"""
            })
        val roleOpts = listOf("", "user", "assistant", "tool_use", "tool_result", "tool_result_error", "system", "error", "unknown")
            .joinToString("") { v ->
                val label = if (v.isEmpty()) "(all roles)" else v
                val sel = if (v == filter.role) " selected" else ""
                """<option value="${esc(v)}"$sel>${esc(label)}</option>"""
            }

        val rowsHtml = if (rows.isEmpty()) {
            """<tr><td colspan="5" class="dim" style="text-align:center;padding:14px">no conversation history</td></tr>"""
        } else {
            rows.joinToString("\n") { r ->
                val roleCls = when (r.role) {
                    "user" -> "user"
                    "assistant" -> "assistant"
                    "tool_use" -> "tool"
                    "tool_result" -> "tool-out"
                    "tool_result_error", "error" -> "err"
                    else -> "sys"
                }
                val previewLen = 800
                val preview = if (r.content.length > previewLen)
                    r.content.take(previewLen) + " …(+" + (r.content.length - previewLen) + ")"
                else r.content
                val toolBadge = r.toolName?.let { """<span class="dim" style="font-size:11px"> · $it</span>""" } ?: ""
                """<tr>
                  <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px;white-space:nowrap">${esc(r.ts)}</td>
                  <td><span class="$roleCls" style="font-size:11px;text-transform:uppercase">${esc(r.role)}</span>$toolBadge</td>
                  <td><pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-word;max-width:900px">${esc(preview)}</pre></td>
                </tr>"""
            }
        }

        val from = page * pageSize + 1
        val to = (page * pageSize + rows.size).coerceAtMost(total.toInt())
        val nextHref = buildHref(projectId, isChat, filter, page + 1)
        val prevHref = buildHref(projectId, isChat, filter, (page - 1).coerceAtLeast(0))
        val hasNext = (page + 1) * pageSize < total
        val hasPrev = page > 0

        val title = if (isChat) "Chat 히스토리" else "$displayName · 히스토리"
        val baseAction = if (isChat) "/chat/history" else "/projects/${esc(projectId)}/history"

        return AdminTemplates.shell(
            title = title,
            username = username,
            currentPath = navPath,
            csrf = csrf,
            body = """
<header>
  <h1>${esc(if (isChat) "General Chat 히스토리" else "대화 히스토리")}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(displayName)} · $total turns</small>
  </h1>
</header>

<form method="get" action="$baseAction" class="card" style="margin-bottom:14px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;align-items:end">
  <label style="margin:0">Session
    <select name="session" style="width:100%">$sessionOpts</select>
  </label>
  <label style="margin:0">Role
    <select name="role" style="width:100%">$roleOpts</select>
  </label>
  <label style="margin:0">Tool name (e.g. Bash)
    <input type="text" name="tool" value="${esc(filter.toolName)}" placeholder="Bash / Read / Edit ...">
  </label>
  <label style="margin:0;grid-column:span 2">Content contains (LIKE)
    <input type="text" name="q" value="${esc(filter.q)}" placeholder="search content">
  </label>
  <label style="margin:0">From (ISO ts)
    <input type="text" name="from" value="${esc(filter.fromTs)}" placeholder="2026-05-24T00:00:00Z">
  </label>
  <label style="margin:0">To (ISO ts)
    <input type="text" name="to" value="${esc(filter.toTs)}" placeholder="2026-05-25T00:00:00Z">
  </label>
  <div style="display:flex;gap:6px">
    <button type="submit" class="primary" style="padding:8px 14px">검색</button>
    <a href="$baseAction" class="chip chip-link" style="padding:8px 14px">초기화</a>
  </div>
</form>

<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;gap:8px;flex-wrap:wrap">
  <div style="display:flex;gap:6px">$backLink</div>
  <div style="display:flex;gap:6px">
    ${if (hasPrev) """<a href="$prevHref" class="chip chip-link">← Prev</a>""" else """<span class="chip" style="opacity:0.4">← Prev</span>"""}
    <small class="dim" style="align-self:center">${if (rows.isEmpty()) "0 / $total" else "$from–$to / $total"}</small>
    ${if (hasNext) """<a href="$nextHref" class="chip chip-link">Next →</a>""" else """<span class="chip" style="opacity:0.4">Next →</span>"""}
  </div>
</div>

<table class="devices">
  <thead><tr>
    <th style="width:160px">Time (UTC)</th>
    <th style="width:140px">Role / Tool</th>
    <th>Content (clip 800)</th>
  </tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:14px;font-size:12px">
  ts 정렬은 ascending (오래된 → 최근). assistant partial chunks 는 영구 적재되지 않음 (turn 단위 final 만).
  ${if (filter.q != null) "Content 검색은 LIKE 기반 — 다음 cycle 에서 PostgreSQL tsvector 로 교체 예정." else ""}
</p>
"""
        )
    }

    private fun buildHref(
        projectId: String,
        isChat: Boolean,
        filter: ConversationTurnRepository.Filter,
        page: Int,
    ): String {
        val base = if (isChat) "/chat/history" else "/projects/${esc(projectId)}/history"
        val params = listOfNotNull(
            filter.sessionId?.let { "session=${enc(it)}" },
            filter.role?.let { "role=${enc(it)}" },
            filter.toolName?.let { "tool=${enc(it)}" },
            filter.fromTs?.let { "from=${enc(it)}" },
            filter.toTs?.let { "to=${enc(it)}" },
            filter.q?.let { "q=${enc(it)}" },
            "p=$page".takeIf { page > 0 },
        )
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
