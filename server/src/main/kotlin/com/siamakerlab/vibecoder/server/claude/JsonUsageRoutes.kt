package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.UsageSummaryDto
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v0.64.0 — Phase 43. `/api/usage` JSON variant — Anthropic 토큰/캐시 누적 통계.
 *
 * 기존 SSR `/usage` (admin-only HTML) 와 동일한 ConversationTurnRepository.usageSummary
 * 집계를 모든 프로젝트에 대해 합산한 결과를 JSON 으로 노출. byDay 시계열은
 * 후속 cycle 에서 추가 — 본 wire 에는 빈 list 로 emit (forward-compat).
 *
 * 인증: Bearer 토큰 (cookie 세션 토큰도 허용). 모든 사용자 노출 가능 — viewer 도 조회 OK
 * (Anthropic 토큰 정보 자체는 본인 작업이라 권한 제한 없음).
 */
fun Routing.jsonUsageRoutes(
    projects: ProjectService,
    conversationRepo: ConversationTurnRepository,
) {
    authenticate(AUTH_BEARER) {
        get(ApiPath.USAGE_JSON) {
            call.requireDevice()
            // 모든 프로젝트의 usage 합산.
            val pids = projects.list().map { it.id }
            var input = 0L; var output = 0L; var cr = 0L; var cc = 0L
            for (pid in pids) {
                val s = conversationRepo.usageSummary(pid)
                input += s.inputTokens
                output += s.outputTokens
                cr += s.cacheReadTokens
                cc += s.cacheCreationTokens
            }
            val totalAllInput = input + cr + cc
            val hitRate = if (totalAllInput == 0L) null
            else cr.toDouble() / totalAllInput.toDouble()  // 0.0..1.0
            call.respond(UsageSummaryDto(
                input = input,
                output = output,
                cacheRead = cr,
                cacheCreate = cc,
                hitRate = hitRate,
                // byDay 시계열은 후속 cycle — usage row 의 ts 컬럼을 date 로 group 하는
                // 별도 repository 메소드 + UI 차트가 필요. 현재 wire 는 forward-compat 빈 list.
                byDay = emptyList(),
            ))
        }
    }
}
