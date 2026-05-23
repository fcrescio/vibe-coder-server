package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.McpCatalog
import com.siamakerlab.vibecoder.server.env.McpService
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * MCP 카탈로그 페이지 라우트 — v0.8.0.
 *
 *   GET  /env-setup/mcp                    — 카탈로그 페이지 (체크박스 + 설정 폼)
 *   POST /env-setup/mcp/install            — 체크된 항목 일괄 설치 (configValues 같이)
 *   POST /env-setup/mcp/unregister         — 체크된 항목 .mcp.json 에서 제거
 */
fun Routing.mcpRoutes(
    authDeps: AdminRoutesDeps,
    mcp: McpService,
) {
    get("/env-setup/mcp") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val states = mcp.detectAll().associateBy { it.id }
        val flash = call.request.queryParameters["flash"]
        call.respondText(
            McpTemplates.catalogPage(sess.username, states, flash),
            ContentType.Text.Html,
        )
    }

    post("/env-setup/mcp/install") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val form = call.receiveParameters()
        // 체크박스: name="select" value="<id>" (multiple)
        val selectedIds = form.getAll("select").orEmpty().distinct()
        if (selectedIds.isEmpty()) {
            call.respondRedirect("/env-setup/mcp?flash=no-selection")
            return@post
        }
        // config 값: name="cfg.<id>.<key>" value=<user input>
        val selections: Map<String, Map<String, String>> = selectedIds.associateWith { id ->
            val entry = McpCatalog.get(id) ?: return@associateWith emptyMap()
            entry.configFields.mapNotNull { f ->
                val v = form["cfg.$id.${f.key}"].orEmpty().trim()
                if (v.isNotEmpty()) f.key to v else null
            }.toMap()
        }
        val taskId = try {
            mcp.spawnBatch(selections)
        } catch (e: ApiException) {
            call.respondText(
                EnvSetupTemplates.errorBlurb(e.message ?: "설치 거부됨"),
                ContentType.Text.Html, HttpStatusCode.fromValue(e.statusCode),
            )
            return@post
        }
        log.info { "MCP install batch by ${sess.username}: ${selectedIds.joinToString(",")}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    post("/env-setup/mcp/unregister") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val form = call.receiveParameters()
        val ids = form.getAll("select").orEmpty().distinct()
        if (ids.isEmpty()) {
            call.respondRedirect("/env-setup/mcp?flash=no-selection")
            return@post
        }
        mcp.unregister(ids)
        log.info { "MCP unregister by ${sess.username}: ${ids.joinToString(",")}" }
        call.respondRedirect("/env-setup/mcp?flash=unregistered")
    }
}
