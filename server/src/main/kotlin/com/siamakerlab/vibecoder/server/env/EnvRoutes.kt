package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.shared.ApiPath
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.envRoutes(status: StatusService, env: EnvDiagnostics) {
    authenticate(AUTH_BEARER) {
        // v1.26.1 — JSON API entry. Accept-Language 가 있으면 그 lang 우선, 없으면
        // 명시적 "en" (Android client / Bearer caller 의 영문 기대). 이전엔 no-arg
        // overload 가 server default ("ko" 가능) 로 떨어져 client 가정 깨졌음 (Q4).
        get(ApiPath.SERVER_STATUS) { call.respond(status.snapshot(resolveLang(call))) }
        get(ApiPath.SERVER_ENVIRONMENT) { call.respond(env.run(resolveLang(call))) }
        post(ApiPath.SERVER_ENVIRONMENT_CHECK) { call.respond(env.run(resolveLang(call))) }
    }
}

/**
 * v1.26.1 — Bearer 토큰 caller 의 응답 언어 선택. Accept-Language 헤더의 첫 토큰
 * (e.g. "ko-KR,ko;q=0.9" → "ko") 이 지원 lang ("en"/"ko") 에 매치되면 사용, 아니면
 * default "en" (영문 기대 client 호환).
 */
private fun resolveLang(call: io.ktor.server.application.ApplicationCall): String {
    val raw = call.request.headers["Accept-Language"]?.substringBefore(',')?.substringBefore('-')?.trim()?.lowercase()
    return if (raw == "ko") "ko" else "en"
}
