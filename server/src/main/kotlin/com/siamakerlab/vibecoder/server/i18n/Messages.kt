package com.siamakerlab.vibecoder.server.i18n

/**
 * v0.77.0 — Phase 64 SSR 다국어 지원.
 *
 * Map 기반 단순 lookup. key 는 dot notation (`nav.home`, `settings.title`,
 * `projects.empty.body` 등). [t] 가 모든 SSR 렌더링의 i18n 진입점.
 *
 * 언어 결정 fallback:
 *   1. 사용자별 `admin_users.language` (Settings → General → Language)
 *   2. `i18n.defaultLanguage` (server.yml / `VIBECODER_DEFAULT_LANGUAGE` env)
 *   3. "en"
 *
 * 새 언어 추가 절차:
 *   1. 본 파일의 `BUNDLES` 에 신규 Map 추가
 *   2. [SUPPORTED] / [resolve] 검증 분기
 *   3. server.yml 의 `i18n.defaultLanguage` 주석에 추가
 *   4. Settings dropdown 의 옵션에 추가
 *
 * 미정의 key 는 fallback chain 끝에서 key 자체를 반환 (디버깅 용이 + 회귀 안전).
 */
object Messages {

    /** v0.77.0 — 지원 언어 코드. 다른 값은 모두 [DEFAULT] 로 fallback. */
    val SUPPORTED = setOf("en", "ko")

    const val DEFAULT = "en"

    /**
     * 사용자 선택 / 서버 default / 잘못된 코드 모두 안전하게 정규화.
     * 빈 문자열 / null / 미지원 코드 → DEFAULT.
     */
    fun resolve(userLang: String?, serverDefault: String?): String {
        userLang?.trim()?.lowercase()?.takeIf { it in SUPPORTED }?.let { return it }
        serverDefault?.trim()?.lowercase()?.takeIf { it in SUPPORTED }?.let { return it }
        return DEFAULT
    }

    /**
     * v0.91.0 — Phase 66 Accept-Language end-to-end.
     *
     * RFC 7231 §5.3.5 Accept-Language 헤더 parse — q-value 정렬된 첫 [SUPPORTED]
     * 매치 반환. 예: `ko-KR,ko;q=0.9,en;q=0.8` → `ko`.
     *
     * Region tag 는 무시 (ko-KR → ko, en-US → en). null / 빈 문자열 / 매치 없음
     * → null (호출자가 fallback chain 의 다음 단계로 진행).
     *
     * 잘못된 q-value (parse 실패 / 범위 밖) 는 1.0 으로 간주.
     */
    fun fromAcceptLanguage(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return header.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val parts = entry.split(';').map { it.trim() }
                val tag = parts[0].substringBefore('-').lowercase()
                val q = parts.drop(1)
                    .firstNotNullOfOrNull { p ->
                        p.takeIf { it.startsWith("q=", ignoreCase = true) }
                            ?.substring(2)?.toDoubleOrNull()
                    }
                    ?.coerceIn(0.0, 1.0)
                    ?: 1.0
                tag to q
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .firstOrNull { it in SUPPORTED }
    }

    /**
     * v0.91.0 — Phase 66 통합 resolve. 우선순위:
     *   1. Accept-Language 헤더 (per-request, android client 가 device locale 송신).
     *   2. user.language (DB 컬럼, 사용자가 web admin /settings/language 에서 설정).
     *   3. serverDefault (server.yml `i18n.defaultLanguage`).
     *   4. [DEFAULT] ("en").
     *
     * SSR 흐름에서는 sess.language 가 이미 `resolve(user.language, serverDefault)`
     * 결과라 `acceptLanguage=null` 로 호출하면 기존 동작 유지.
     * JSON API 흐름에서는 Bearer token 인증이라 user.language 가 직접 조회 불가
     * (DevicePrincipal 만 있음) → Accept-Language 가 주된 소스.
     */
    fun resolveFromRequest(
        acceptLanguage: String?,
        userLang: String? = null,
        serverDefault: String? = null,
    ): String {
        fromAcceptLanguage(acceptLanguage)?.let { return it }
        return resolve(userLang, serverDefault)
    }

    /**
     * key lookup + 인자 치환 (`%s` 가 있으면 `String.format`).
     * key 가 현재 언어에 없으면 [DEFAULT] 번들로 fallback.
     * 그래도 없으면 key 자체 (개발자가 누락 발견 용이).
     */
    fun t(lang: String, key: String, vararg args: Any?): String {
        val bundle = BUNDLES[lang] ?: BUNDLES.getValue(DEFAULT)
        val raw = bundle[key] ?: BUNDLES.getValue(DEFAULT)[key] ?: return key
        return if (args.isEmpty()) raw else runCatching { raw.format(*args) }.getOrDefault(raw)
    }

    /** 같은 키의 두 언어 값을 비교/디버그 — 운영 진단용. */
    fun debugBoth(key: String): String = "[en=${BUNDLES.getValue("en")[key]} ko=${BUNDLES.getValue("ko")[key]}]"

    /** 모든 번들 — 양쪽 언어가 같은 key set 유지. */
    private val BUNDLES: Map<String, Map<String, String>> = mapOf(
        "en" to MessagesEn.MAP,
        "ko" to MessagesKo.MAP,
    )
}
