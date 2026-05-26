package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * Request body for POST /api/projects/{projectId}/claude/console/prompt
 * — `text` is the raw user prompt (≤ 32 KB enforced server-side).
 */
@Serializable
data class PromptRequestDto(val text: String)

/**
 * Response body for POST .../prompt. `seq` is the next sequence number the
 * client should observe — anything older has already been broadcast.
 */
@Serializable
data class PromptAcceptedDto(val seq: Long)

/**
 * Snapshot of the Claude session attached to a project.
 *
 * - `sessionId`     : the Claude CLI session UUID (may be null if not yet started).
 * - `processAlive`  : whether the spawned `claude` child process is currently alive.
 * - `model`         : last-seen model name from session init.
 * - `plan`          : subscription plan name parsed from `/status` (Phase E).
 * - `quotaRemaining`: free-form quota summary parsed from `/status` (Phase E).
 * - `usagePercent`  : (v0.21.0) extracted percent value (0-100) from quota line if present.
 *                     Null = couldn't parse (older CLI / different output format).
 * - `resetAt`       : (v0.21.0) ISO-ish reset timestamp extracted from quota output.
 * - `updatedAt`     : ISO-8601 timestamp of the snapshot.
 */
@Serializable
data class ClaudeStatusDto(
    val sessionId: String? = null,
    val processAlive: Boolean = false,
    val model: String? = null,
    val plan: String? = null,
    val quotaRemaining: String? = null,
    /**
     * v0.21.0 legacy — usage % (단일). v1.0.1 부터는 sessionUsagePercent /
     * weeklyUsagePercent 의 max 값 (둘 다 있으면 큰 쪽). backward compatible —
     * 기존 클라이언트는 그대로 사용.
     */
    val usagePercent: Int? = null,
    /** v0.21.0 legacy — reset 시각 단일 free-form. v1.0.1 부터는 sessionResetAt 우선. */
    val resetAt: String? = null,
    val updatedAt: String,
    /**
     * v0.98.0 — true 면 현재 사용자 prompt 처리 중 (Claude 가 응답을 stream 중).
     * false 면 다음 prompt 대기. Web client 는 동일 정보를 WS 의 sendPrompt
     * 전송 + ConsoleDone / system(turn_cancelled|process_crashed|idle_terminated)
     * 수신으로 도출하지만, Android client 가 status REST 폴링 또는 첫 진입 시
     * 즉시 알 수 있도록 server-side 도 노출.
     */
    val busy: Boolean = false,
    /**
     * v1.0.1 — 5시간 세션 사용량 % (Pro/Max 의 rolling 세션 quota). null = 파싱 실패
     * 또는 `claude /status` 출력에 해당 줄 없음.
     */
    val sessionUsagePercent: Int? = null,
    /** v1.0.1 — 7일 weekly rolling 사용량 % (Pro/Max plan). null = 미감지. */
    val weeklyUsagePercent: Int? = null,
    /** v1.0.1 — 세션 quota reset 시각 (free-form 문자열, 예: "in 3h 24m"). */
    val sessionResetAt: String? = null,
    /** v1.0.1 — 주간 quota reset 시각 (free-form, 예: "in 3d 5h"). */
    val weeklyResetAt: String? = null,
)
