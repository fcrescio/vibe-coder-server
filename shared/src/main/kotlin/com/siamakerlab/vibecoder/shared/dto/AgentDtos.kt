package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.36.0+ — Custom agent catalog entry (서버 `.agents/` 디렉토리에 등록된 항목).
 * `name` 은 그대로 URL path segment 로 쓰인다 (filesystem identifier).
 * `preview` 는 system prompt 의 앞부분 발췌 (UI 미리보기용).
 *
 * v0.64.0 — Android shared/ 와 동일한 모양으로 server shared/ 에 정식 등록.
 */
@Serializable
data class AgentInfoDto(
    val name: String,
    val sizeBytes: Long? = null,
    val preview: String? = null,
)

/** GET /api/agents 응답 (v0.36+). */
@Serializable
data class AgentsCatalogResponseDto(
    val agents: List<AgentInfoDto> = emptyList(),
)

/**
 * v0.44.0+ — GET /api/projects/{id}/agents/active 응답.
 * `agents` 는 현재 process 가 살아 있는 sub-agent 이름 리스트.
 */
@Serializable
data class ActiveAgentsResponseDto(
    val projectId: String,
    val agents: List<String> = emptyList(),
)

/**
 * v0.44.0+ — POST .../agents/{agent}/console/prompt 응답.
 * 단순 ack. 실제 출력은 WebSocket [com.siamakerlab.vibecoder.shared.ApiPath.wsAgentConsoleLogs] 로.
 */
@Serializable
data class AgentPromptAcceptedDto(val ok: Boolean = true)
