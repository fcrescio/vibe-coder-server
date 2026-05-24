package com.siamakerlab.vibecoder.shared.dto

/**
 * v0.52.0+ — `/api/projects/{id}/history?agent=` 와 `/api/chat/history?agent=` 의
 * 허용 값.
 *
 *  - `main`     — 메인 console turn 만 (sub-agent 제외).
 *  - `all`      — 메인 + 모든 sub-agent.
 *  - `@<name>` — 특정 sub-agent 의 turn 만. 예: `@frontend`, `@reviewer`.
 *
 * SSR 측은 `?agent=` 자체 + `?agent=*` 두 가지 매핑을 쓰는데, JSON API 는
 * 이 wire 의 `main`/`all`/`@name` 셋으로 단일화 (v0.64.0).
 *
 * Helper [forAgentName] 으로 sub-agent 이름을 안전하게 `@`-prefix 형식으로 변환.
 */
object HistoryAgentFilter {
    const val MAIN = "main"
    const val ALL = "all"

    /** sub-agent 이름을 `@<name>` 형식으로 감싼다. 이미 `@` 로 시작하면 그대로. */
    fun forAgentName(agent: String): String =
        if (agent.startsWith("@")) agent else "@$agent"
}
