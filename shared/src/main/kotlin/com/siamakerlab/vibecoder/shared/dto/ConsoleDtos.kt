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
    val usagePercent: Int? = null,
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
)
