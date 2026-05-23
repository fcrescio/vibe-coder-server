package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.EnvSetupService
import com.siamakerlab.vibecoder.server.env.SetupComponent
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * 빌드환경 SSR 라우트.
 *
 * v0.6.0 Phase A — 상태 진단 + 카드 UI + 명령 안내.
 * v0.6.1 Phase B — 원클릭 설치 (POST) + 일괄 설치 + 진행 페이지 + WS.
 */
fun Routing.envSetupRoutes(
    authDeps: AdminRoutesDeps,
    setupService: EnvSetupService,
) {
    get("/env-setup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val states = setupService.detectAll()
        call.respondText(
            EnvSetupTemplates.envSetupPage(sess.username, states),
            ContentType.Text.Html,
        )
    }

    post("/env-setup/install-all") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val taskId = setupService.spawnInstallAll()
        log.info { "env-setup install-all: $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    post("/env-setup/{componentId}/install") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["componentId"]!!
        val comp = SetupComponent.byId(id)
            ?: throw ApiException(404, "unknown_component", "Unknown component: $id")
        val taskId = runCatching { setupService.spawnInstall(comp) }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: "설치 시작 실패"
            call.respondText(
                EnvSetupTemplates.errorBlurb(msg),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        log.info { "env-setup install: ${comp.id} → task $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    get("/env-setup/tasks/{taskId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val taskId = call.parameters["taskId"]!!
        call.respondText(
            EnvSetupTemplates.taskProgressPage(sess.username, taskId),
            ContentType.Text.Html,
        )
    }
}
