package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.30.0+ — cross-project conversation search 의 한 hit.
 *
 * `preview` 는 HTML-like 형식 — `<mark style="background:#facc15">matched</mark>` 가
 * 그대로 포함될 수 있다 (SSR `/history` 와 같은 hilite 로직 재사용). JSON
 * client 는 strip 하거나 AnnotatedString 으로 렌더.
 *
 * v0.64.0 — JSON variant (`/api/history/search`) 가 emit. SSR `/history` 는 그대로 유지.
 */
@Serializable
data class HistorySearchHitDto(
    val projectId: String,
    val sessionId: String? = null,
    /** Server 의 turn id (ConversationTurnRow.id, String). */
    val turnId: String? = null,
    val ts: String? = null,
    val role: String? = null,
    val preview: String = "",
)

/**
 * GET /api/history/search?q=... 응답.
 * 200-hit hard cap, ts DESC.
 */
@Serializable
data class HistorySearchResponseDto(
    val hits: List<HistorySearchHitDto> = emptyList(),
)

/**
 * v0.31.0+ — POST /api/projects/{id}/history/import 응답.
 * server-side 결과 요약. `warnings` 는 skip 사유 리스트 (sessionId 충돌 등).
 *
 * v0.64.0 — JSON variant (`/api/projects/{id}/history/import`) 가 emit.
 * 기존 SSR `/projects/{id}/history/import` 는 redirect 응답 유지.
 */
@Serializable
data class HistoryImportResponseDto(
    val accepted: Int = 0,
    val skipped: Int = 0,
    val warnings: List<String> = emptyList(),
    val dryRun: Boolean = false,
)
