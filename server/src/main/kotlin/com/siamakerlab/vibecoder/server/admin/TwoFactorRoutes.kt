package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.AuthService
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.Totp
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v0.26.0 — `/2fa` SSR 페이지 + POST 라우트.
 *
 * 흐름:
 *   - GET /2fa
 *     - 비활성: pending secret 생성 (in-memory transient, 세션 단위) → otpauth URI
 *       + Base32 secret 표시. 사용자가 Authenticator 앱에 등록 후 코드 입력 폼.
 *     - 활성: "현재 활성" 안내 + 비활성화 폼 (현재 코드 1회 확인).
 *   - POST /2fa/enable: 6자리 코드 검증 → users.enableTotp(secret) 영구화.
 *   - POST /2fa/disable: 6자리 코드 검증 → users.disableTotp.
 *
 * Pending secret 은 in-memory `pendingSecrets[userId]` ConcurrentHashMap 에 저장 —
 * 서버 재시작 시 초기화 (재생성). UI 가 secret 을 폼에 hidden 으로 다시 보내지
 * 않는 이유: 브라우저 history / 캡처 위험 회피.
 */
fun Routing.twoFactorRoutes(deps: AdminRoutesDeps, users: AdminUserRepository) {
    val pendingSecrets = java.util.concurrent.ConcurrentHashMap<String, String>()

    get("/2fa") {
        val sess = requireSessionOrRedirect(deps) ?: return@get
        // v0.40.0 — 2FA 는 개인 보안 설정 — admin/member/viewer 모두 자기 자신 관리 허용.
        // 단 viewer 는 일반적으로 enable 후 forgot 만 위험하므로 별도 가드 안 함.
        val u = users.findById(sess.userId) ?: run {
            call.respondRedirect("/login"); return@get
        }
        if (u.totpEnabled) {
            call.respondText(
                TwoFactorTemplates.enabledPage(sess.username, sess.csrf, u.totpEnabledAt),
                ContentType.Text.Html,
            )
            return@get
        }
        // 비활성 → pending secret 보장. 이미 있으면 재사용 (페이지 새로고침 안전).
        val secret = pendingSecrets.computeIfAbsent(sess.userId) { Totp.generateSecret() }
        val issuer = deps.config.server.name
        val uri = Totp.otpauthUri(issuer, u.username, secret)
        call.respondText(
            TwoFactorTemplates.disabledPage(sess.username, sess.csrf, secret, uri),
            ContentType.Text.Html,
        )
    }

    post("/2fa/enable") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        requireCsrf()
        val params = call.receiveParameters()
        val code = params["code"]?.trim().orEmpty()
        val secret = pendingSecrets[sess.userId]
        if (secret.isNullOrBlank()) {
            call.respondRedirect("/2fa?err=${enc("세션 만료 — 페이지를 새로고침해 다시 시도하세요.")}")
            return@post
        }
        if (!Totp.verify(secret, code)) {
            call.respondRedirect("/2fa?err=${enc("코드가 일치하지 않습니다. 시간 동기화 확인 후 재시도.")}")
            return@post
        }
        users.enableTotp(sess.userId, secret)
        pendingSecrets.remove(sess.userId)
        deps.audit.twoFactorEnabled(sess.userId, call.request.local.remoteHost)
        log.info { "2FA enabled for user ${sess.username}" }
        call.respondRedirect("/2fa?ok=${enc("2FA 가 활성화되었습니다.")}")
    }

    post("/2fa/disable") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        requireCsrf()
        val u = users.findById(sess.userId) ?: run { call.respondRedirect("/login"); return@post }
        if (!u.totpEnabled) {
            call.respondRedirect("/2fa")
            return@post
        }
        val params = call.receiveParameters()
        val code = params["code"]?.trim().orEmpty()
        if (!Totp.verify(u.totpSecret!!, code)) {
            call.respondRedirect("/2fa?err=${enc("현재 활성 코드가 일치하지 않습니다.")}")
            return@post
        }
        users.disableTotp(sess.userId)
        deps.audit.twoFactorDisabled(sess.userId, call.request.local.remoteHost)
        log.info { "2FA disabled for user ${sess.username}" }
        call.respondRedirect("/2fa?ok=${enc("2FA 가 비활성화되었습니다.")}")
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

private object TwoFactorTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun disabledPage(username: String, csrf: String?, secret: String, otpauthUri: String): String =
        AdminTemplates.shell(
            title = "2단계 인증",
            username = username,
            currentPath = "/2fa",
            csrf = csrf,
            body = """
<header><h1>2단계 인증 (TOTP)</h1></header>

<div class="card">
  <h2>현재 비활성</h2>
  <p>Google Authenticator / 1Password / Authy 같은 TOTP 앱과 연동해 로그인 보안을 강화합니다.</p>
</div>

<div class="card" style="margin-top:14px">
  <h2>1. 앱에 등록</h2>
  <p>아래 URI 를 QR 코드 생성기로 변환해 Authenticator 앱으로 스캔하거나, secret 을 수동 입력하세요.</p>

  <p><strong>otpauth URI</strong></p>
  <pre class="diff-block" style="font-size:11px;word-break:break-all;white-space:pre-wrap">${esc(otpauthUri)}</pre>

  <p style="margin-top:12px"><strong>Base32 secret (수동 입력용)</strong></p>
  <pre class="diff-block" style="font-size:13px;letter-spacing:2px">${esc(secret.chunked(4).joinToString(" "))}</pre>

  <p class="hint">QR 생성기 예: <code>qrencode -t ANSI '<otpauth URI>'</code> (Linux 터미널)
  또는 브라우저 기반 QR 생성기. 이 secret 은 서버 재시작 시 새로 생성되므로 등록 즉시 다음 단계로 진행.</p>
</div>

<div class="card" style="margin-top:14px">
  <h2>2. 코드 확인 + 활성화</h2>
  <form method="post" action="/2fa/enable" style="display:grid;gap:10px;max-width:400px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>Authenticator 앱의 6자리 코드
      <input name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autofocus>
    </label>
    <button type="submit" class="primary">활성화</button>
  </form>
</div>
"""
        )

    fun enabledPage(username: String, csrf: String?, enabledAt: String?): String =
        AdminTemplates.shell(
            title = "2단계 인증",
            username = username,
            currentPath = "/2fa",
            csrf = csrf,
            body = """
<header><h1>2단계 인증 (TOTP)</h1></header>

<div class="card">
  <h2>✓ 현재 활성</h2>
  <p>활성화 시각: <code>${esc(enabledAt ?: "-")}</code></p>
  <p>다음 로그인부터 password 통과 후 6자리 코드 입력이 요구됩니다.</p>
</div>

<div class="card" style="margin-top:14px;border-color:var(--warn);background:rgba(255,150,80,0.06)">
  <h2 style="margin-top:0">비활성화</h2>
  <p class="hint">현재 활성된 Authenticator 코드 1회 확인 후 비활성화됩니다. Authenticator 앱이 손실됐다면 비밀번호 변경 + 새 admin 생성을 통해 복구하세요.</p>
  <form method="post" action="/2fa/disable" style="display:grid;gap:10px;max-width:400px;margin-top:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>현재 6자리 코드
      <input name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required>
    </label>
    <button type="submit" class="chip chip-danger">비활성화</button>
  </form>
</div>
"""
        )
}
