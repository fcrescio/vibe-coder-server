package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
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
 * 프로젝트 / 콘솔 / 빌드 SSR 라우트.
 *
 * 인증 모델: AdminRoutes 와 동일하게 `vibe_session` 쿠키 기반 (requireSessionOrRedirect).
 * REST API (`/api/projects/...`) 는 그대로 두고 폼/페이지만 새로 만든다.
 */
fun Routing.webProjectRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    builds: BuildService,
    buildRepo: BuildRepository,
    artifactRepo: ArtifactRepository,
    sessionManager: ClaudeSessionManager,
    hub: LogHub,
) {

    // ── 목록 + 등록 폼 ────────────────────────────────────────────────
    get("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val list = projects.list()
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]?.let { "프로젝트가 생성되었습니다." }
        call.respondText(
            WebProjectTemplates.projectsPage(sess.username, list, flashErr = err, flashOk = ok),
            ContentType.Text.Html,
        )
    }

    post("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val params = call.receiveParameters()
        val projectId = params["projectId"]?.trim().orEmpty()
        val appName = params["appName"]?.trim().orEmpty()
        val packageName = params["packageName"]?.trim().orEmpty()

        val basicErr = when {
            projectId.isBlank() -> "프로젝트 ID 를 입력하세요."
            appName.isBlank() -> "앱 이름을 입력하세요."
            packageName.isBlank() -> "패키지명을 입력하세요."
            else -> null
        }
        if (basicErr != null) {
            val list = projects.list()
            call.respondText(
                WebProjectTemplates.projectsPage(sess.username, list, flashErr = basicErr),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        val result = runCatching {
            projects.register(
                RegisterProjectRequestDto(
                    projectId = projectId,
                    appName = appName,
                    packageName = packageName,
                    keystore = null,
                )
            )
        }

        val created = result.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: "프로젝트 생성 실패"
            log.warn(e) { "project register failed: $projectId by ${sess.username}" }
            val list = projects.list()
            call.respondText(
                WebProjectTemplates.projectsPage(sess.username, list, flashErr = msg),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        log.info { "project registered: ${created.id} by ${sess.username}" }
        call.respondRedirect("/projects/${created.id}")
    }

    // ── 상세 ──────────────────────────────────────────────────────────
    get("/projects/{id}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val recent = buildRepo.listForProject(id, limit = 5).map { row ->
            BuildDto(
                id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
                startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
                artifactId = row.artifactId, errorMessage = row.errorMessage,
            )
        }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            WebProjectTemplates.projectDetailPage(sess.username, p, recent, flashErr = err, flashOk = ok),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val removed = projects.delete(id)
        log.info { "project delete: id=$id removed=$removed by ${sess.username}" }
        val ok = if (removed) "프로젝트가 삭제되었습니다." else "프로젝트가 존재하지 않습니다."
        call.respondRedirect("/projects?ok=${ok.encodeUrl()}")
    }

    // ── 콘솔 ──────────────────────────────────────────────────────────
    get("/projects/{id}/console") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val alive = sessionManager.isAlive(id)
        val sid = sessionManager.currentSessionId(id)
        call.respondText(
            WebProjectTemplates.consolePage(sess.username, p, sid, alive),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/console/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        runCatching { sessionManager.startNew(id) }
            .onFailure { log.warn(it) { "console reset failed for $id" } }
        log.info { "console reset: $id by ${sess.username}" }
        call.respondRedirect("/projects/$id/console")
    }

    // ── 빌드 ──────────────────────────────────────────────────────────
    get("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val rows = buildRepo.listForProject(id, limit = 100)
        val buildDtos = rows.map { row ->
            BuildDto(
                id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
                startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
                artifactId = row.artifactId, errorMessage = row.errorMessage,
            )
        }
        val artifacts = artifactRepo.listForProject(id).associateBy { it.buildId }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            WebProjectTemplates.buildsPage(
                sess.username, p, buildDtos, artifacts,
                flashErr = err, flashOk = ok,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val row = runCatching { builds.enqueueDebug(id, hub) }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: "빌드 큐 등록 실패"
            log.warn(e) { "build enqueue failed: $id" }
            call.respondRedirect("/projects/$id/builds?err=${msg.encodeUrl()}")
            return@post
        }
        log.info { "build enqueued: ${row.id} project=$id by ${sess.username}" }
        call.respondRedirect("/projects/$id/builds?ok=${("빌드 ${row.id.take(12)} 큐 등록 완료.").encodeUrl()}")
    }
}

/**
 * 폼 flash 메시지를 query string 으로 옮길 때 쓰는 단순 URL encoder.
 * java.net.URLEncoder 가 form-encoding 이라 공백을 `+` 로 바꿔서 SSR에 보이기 어색.
 * 표준 percent-encoding 으로 통일한다.
 */
private fun String.encodeUrl(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
