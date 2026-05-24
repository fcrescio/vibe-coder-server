package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.31.0+ — GET /api/projects/{id}/claude/prompt-suggestions 응답.
 * Query params: `prefix` (string, ≥2 chars 권장), `limit` (default 8).
 * 서버는 최근 user-turn 텍스트를 LIKE 매칭하여 상위 N개 반환.
 *
 * v0.64.0 — Android shared/ 와 동일한 모양으로 server shared/ 에 정식 등록.
 */
@Serializable
data class PromptSuggestionsResponseDto(
    val suggestions: List<String> = emptyList(),
)
