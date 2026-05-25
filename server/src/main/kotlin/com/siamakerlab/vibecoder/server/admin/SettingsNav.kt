package com.siamakerlab.vibecoder.server.admin

/**
 * v0.69.0 — Phase 48 UI 리뉴얼.
 *
 * 사이드바를 24개 평탄 메뉴에서 6개 top-level 로 압축한 후, 설정 페이지 안에
 * 8개 탭으로 묶기 위한 helper. AdminTemplates.kt 와 별도 파일로 분리한 이유:
 * AdminTemplates 가 매우 큰 raw-string-heavy 파일이라 Kotlin K2 parser 가
 * fragile — 신규 코드 추가 시 brace 매칭 에러 frequent. 별도 파일은 영향 없음.
 *
 * 사용처:
 *  - [AdminTemplates.shell] 가 currentPath 보고 settings 카테고리면 자동 inject.
 *  - 각 sub-page 의 body 는 그대로 — top 의 탭바만 추가.
 */
internal object SettingsNav {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    /**
     * currentPath -> top-level nav key 매핑.
     *
     * - dashboard: /
     * - projects:  /projects*
     * - chat:      /chat*
     * - tools:     /tools, /multi-console, /emulator, /logs, /code-search, /history
     * - settings:  나머지 admin sub-page 모두 (settings/password/2fa/webauthn/devices/
     *              env-setup/backup/usage/audit/users/prompts/agents)
     */
    fun topLevelOf(currentPath: String): String {
        val p = currentPath.ifBlank { "/" }
        return when {
            p == "/" -> "dashboard"
            p.startsWith("/projects") -> "projects"
            p.startsWith("/chat") -> "chat"
            p == "/tools" || p.startsWith("/tools/") -> "tools"
            p.startsWith("/multi-console") -> "tools"
            p.startsWith("/emulator") -> "tools"
            p == "/logs" || p.startsWith("/logs/") -> "tools"
            p.startsWith("/code-search") -> "tools"
            p == "/history" || p.startsWith("/history/") -> "tools"
            p.startsWith("/settings") -> "settings"
            p.startsWith("/password") -> "settings"
            p.startsWith("/2fa") -> "settings"
            p.startsWith("/webauthn") -> "settings"
            p.startsWith("/devices") -> "settings"
            p.startsWith("/env-setup") -> "settings"
            p.startsWith("/backup") -> "settings"
            p.startsWith("/usage") -> "settings"
            p.startsWith("/audit") -> "settings"
            p.startsWith("/users") -> "settings"
            p.startsWith("/prompts") -> "settings"
            p.startsWith("/agents") -> "settings"
            else -> "dashboard"
        }
    }

    /**
     * 설정 페이지 8개 탭 — 각 sub-page 상단에 inject.
     *
     * 호출자가 currentPath 를 넘기면 해당 탭이 active.
     */
    fun tabBar(currentPath: String): String {
        val tab = tabOf(currentPath)
        val tabs = listOf(
            Triple("general", "일반", "/settings"),
            Triple("security", "보안", "/password"),
            Triple("notifications", "알림", "/settings/email"),
            Triple("build-env", "빌드환경", "/env-setup"),
            Triple("prompts", "프롬프트 & 에이전트", "/prompts"),
            Triple("backup", "백업", "/backup"),
            Triple("monitoring", "모니터링", "/usage"),
            Triple("users", "사용자", "/users"),
        )
        val items = tabs.joinToString("\n") { (key, label, href) ->
            val cls = if (key == tab) "tab active" else "tab"
            "<a href=\"" + esc(href) + "\" class=\"" + esc(cls) + "\">" + esc(label) + "</a>"
        }
        return TAB_BAR_PREFIX + items + TAB_BAR_SUFFIX
    }

    /** currentPath -> settings 탭 key. */
    private fun tabOf(currentPath: String): String {
        val p = currentPath
        return when {
            p == "/settings" -> "general"
            p.startsWith("/password") -> "security"
            p.startsWith("/2fa") -> "security"
            p.startsWith("/webauthn") -> "security"
            p.startsWith("/devices") -> "security"
            p == "/settings/cors" -> "security"
            p == "/settings/email" -> "notifications"
            p == "/settings/webhook" -> "notifications"
            p == "/settings/push" -> "notifications"
            p.startsWith("/env-setup") -> "build-env"
            p == "/settings/git-integrations" -> "build-env"
            p == "/settings/cache" -> "build-env"
            p.startsWith("/prompts") -> "prompts"
            p.startsWith("/agents") -> "prompts"
            p.startsWith("/backup") -> "backup"
            p.startsWith("/usage") -> "monitoring"
            p.startsWith("/audit") -> "monitoring"
            p.startsWith("/users") -> "users"
            else -> "general"
        }
    }

    // CSS + 탭 컨테이너 — raw string 안 nested template 회피를 위해 plain string.
    private const val TAB_BAR_PREFIX = "<div class=\"settings-tabs\" style=\"display:flex;gap:4px;flex-wrap:wrap;border-bottom:1px solid #2a2f3a;margin:-8px -8px 14px -8px;padding:0 8px 0 8px\"><style>.settings-tabs .tab{padding:10px 14px;text-decoration:none;color:#9aa0aa;border-bottom:2px solid transparent;font-size:14px;transition:all 0.15s}.settings-tabs .tab:hover{color:#e6e6e6}.settings-tabs .tab.active{color:#e6e6e6;border-bottom-color:#facc15;font-weight:600}</style>"
    private const val TAB_BAR_SUFFIX = "</div>"
}
