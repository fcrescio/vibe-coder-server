package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.shared.ApiPath
import io.ktor.server.application.ApplicationCall
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
 * v1.26.1 — Bearer 토큰 caller 의 응답 언어 선택.
 * v1.26.2 — Bug-1 회수: RFC 7231 §5.3.5 q-value weighting 을 무시하던 자체 구현
 * → 기존 [Messages.fromAcceptLanguage] (q-sort + region strip + supported 매치
 * 완전 구현) 재사용. 매치 안 되면 "en" (Android client / Bearer caller 영문 기대).
 */
private fun resolveLang(call: ApplicationCall): String =
    Messages.fromAcceptLanguage(call.request.headers["Accept-Language"]) ?: "en"
