package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ProjectTemplateDto
import com.siamakerlab.vibecoder.shared.dto.ProjectTemplatesResponseDto
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v0.66.0 — Phase 45. `GET /api/project-templates` — 신규 프로젝트 생성 시 노출할
 * starter 템플릿 카탈로그 (id / title / description / starterPrompt).
 *
 * 본 endpoint 도입 전엔 [ProjectTemplates] 카탈로그가 서버 admin SSR 신규 프로젝트
 * 폼에서만 조회 가능했음. vibe-coder-android v0.7.24+ 가 신규 프로젝트 dialog 에서
 * dropdown 으로 노출하기 위해 추가.
 *
 * 응답은 정적 데이터라 캐싱 안전. Bearer 인증만 — viewer/member/admin 모두 접근 가능.
 */
fun Routing.projectTemplateRoutes() {
    authenticate(AUTH_BEARER) {
        get(ApiPath.PROJECT_TEMPLATES) {
            val items = ProjectTemplates.all.map { t ->
                ProjectTemplateDto(
                    id = t.id,
                    title = t.title,
                    description = t.description,
                    starterPrompt = t.starterPrompt,
                )
            }
            call.respond(ProjectTemplatesResponseDto(items))
        }
    }
}
