package com.siamakerlab.vibecoder.server.admin

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v0.69.0 — Phase 48. `/tools` hub — UI 리뉴얼의 일부.
 *
 * 기존 사이드바에 평탄하게 나열돼 있던 "보조 도구" 들을 한 페이지에 묶음:
 *   - Multi-console (여러 프로젝트 콘솔 동시 보기)
 *   - Emulator (Android emulator 제어)
 *   - 코드 검색 (cross-project file content search)
 *   - 빌드 로그 검색 (cross-project build logs grep)
 *   - 대화 검색 (cross-project conversation history search)
 *
 * 각 도구의 기능 자체는 그대로 유지 — 이 hub 는 단순 카드 grid 진입점.
 *
 * 보안: cookie 세션 — `requireSessionOrRedirect` 통과 필요.
 */
fun Routing.toolsRoutes(authDeps: AdminRoutesDeps) {
    get("/tools") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val body = """
<h1 style="margin-bottom:16px">도구 <small class="dim" style="font-size:14px;font-weight:400">v0.69.0</small></h1>
<p class="dim" style="margin-bottom:24px">
  사이드바에 분산돼 있던 cross-project / 보조 도구들을 한 곳에 모았습니다.
  각 도구를 클릭하면 기존 페이지로 이동합니다.
</p>

<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px">
  ${toolCard("Multi-console", "여러 프로젝트의 Claude 콘솔을 동시에 모니터링.",
        "/multi-console", "📺")}
  ${toolCard("Emulator", "Android 에뮬레이터 launch / stop / VNC 접속.",
        "/emulator", "📱")}
  ${toolCard("코드 검색", "워크스페이스 전체 (모든 프로젝트) 파일 content 를 grep 으로 검색.",
        "/code-search", "🔎")}
  ${toolCard("빌드 로그 검색", "모든 빌드의 로그 파일을 가로질러 grep — 실패 패턴 찾기 등.",
        "/logs", "📜")}
  ${toolCard("대화 검색", "Claude 대화 (모든 프로젝트 + General Chat) 의 텍스트 검색.",
        "/history", "💬")}
</div>

<hr style="margin:28px 0;border:none;border-top:1px solid var(--border, #2a2f3a)">

<h2 style="font-size:16px;margin-bottom:12px">팁</h2>
<ul class="dim" style="font-size:13px;line-height:1.6">
  <li>각 프로젝트 페이지 안에는 별도 도구 (git / 빌드 / 파일 트리 / 의존성 audit / 코드 통계 / Gradle wrapper) 가 따로 있습니다 — 좌측 <strong>프로젝트</strong> 메뉴에서 진입.</li>
  <li>알림, 백업, 사용자 관리, 빌드환경 설치 등은 <strong>설정</strong> 페이지의 탭에서 통합 관리합니다.</li>
</ul>
""".trimIndent()
        call.respondText(
            AdminTemplates.shell(
                title = "도구",
                username = sess.username,
                currentPath = "/tools",
                csrf = sess.csrf,
                body = body,
            ),
            ContentType.Text.Html,
        )
    }
}

private fun toolCard(title: String, desc: String, href: String, icon: String): String =
    """
<a href="$href" class="card" style="text-decoration:none;color:inherit;display:block;
   padding:18px;border-radius:10px;border:1px solid var(--border, #2a2f3a);
   transition:border-color 0.15s,background 0.15s;cursor:pointer"
   onmouseover="this.style.borderColor='var(--primary, #facc15)'"
   onmouseout="this.style.borderColor='var(--border, #2a2f3a)'">
  <div style="font-size:24px;margin-bottom:8px">$icon</div>
  <div style="font-weight:600;margin-bottom:6px">$title</div>
  <div class="dim" style="font-size:12px;line-height:1.5">$desc</div>
</a>
""".trimIndent()
