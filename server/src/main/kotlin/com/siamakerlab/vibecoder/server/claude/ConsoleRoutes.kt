package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.PromptAcceptedDto
import com.siamakerlab.vibecoder.shared.dto.PromptRequestDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * Routes for the persistent Claude console session attached to a project.
 *
 * `POST .../console/prompt` — append a user turn (spawns the session on first call).
 *   Response is 202 Accepted with the post-emit seq baseline so the client can
 *   correlate when the answer arrives over WS.
 *
 * `POST .../console/new`    — terminate current session + delete saved session-id.
 *
 * `GET  .../claude/status`  — best-effort session snapshot (no /status invocation
 *   in Phase A; Phase E will plug in [ClaudeStatusService]).
 */
fun Routing.consoleRoutes(
    projects: ProjectService,
    sessionManager: ClaudeSessionManager,
    hub: LogHub,
    statusService: ClaudeStatusService,
    envDiagnostics: EnvDiagnostics,
) {
    authenticate(AUTH_BEARER) {
        post("/api/projects/{projectId}/claude/console/prompt") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException(400, "bad_request", "projectId is required")
            // ensure project is registered (404 path matches the rest of the codebase)
            projects.rowOrThrow(projectId)

            // 인증 안 된 상태에서 자식 프로세스를 띄우면 사용자는 의미 없는 stderr 만 보게 된다.
            // 미리 차단하고 명확한 가이드를 응답으로 돌려준다.
            val env = envDiagnostics.run()
            if (env.claude.status != CheckStatus.OK) {
                throw ApiException(503, "claude_cli_missing",
                    "Claude CLI 가 설치되지 않았습니다. 컨테이너 안에서 'vibe-doctor claude' 를 실행하세요.")
            }
            if (env.claudeAuth?.status == CheckStatus.ERROR) {
                throw ApiException(503, "claude_auth_required",
                    "Claude CLI 로그인이 필요합니다. 'docker exec -it --user vibe vibe-coder claude login' 후 다시 시도하세요.")
            }

            val body = call.receive<PromptRequestDto>()
            val text = body.text.trim()
            if (text.isEmpty()) throw ApiException(400, "bad_request", "text is required")
            if (text.length > ClaudeSessionManager.MAX_PROMPT_BYTES) {
                throw ApiException(400, "prompt_too_large",
                    "prompt exceeds ${ClaudeSessionManager.MAX_PROMPT_BYTES} bytes")
            }

            try {
                sessionManager.sendPrompt(projectId, text)
            } catch (e: Exception) {
                log.warn(e) { "[$projectId] prompt failed" }
                throw ApiException(500, "claude_send_failed", e.message ?: "unknown error")
            }

            val seq = hub.consoleCurrentSeq(LogHub.consoleTopic(projectId))
            call.respond(HttpStatusCode.Accepted, PromptAcceptedDto(seq = seq))
        }

        post("/api/projects/{projectId}/claude/console/new") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException(400, "bad_request", "projectId is required")
            projects.rowOrThrow(projectId)
            sessionManager.startNew(projectId)
            call.respond(HttpStatusCode.Accepted)
        }

        get("/api/projects/{projectId}/claude/status") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException(400, "bad_request", "projectId is required")
            projects.rowOrThrow(projectId)
            call.respond(statusService.snapshot(projectId))
        }
    }
}
