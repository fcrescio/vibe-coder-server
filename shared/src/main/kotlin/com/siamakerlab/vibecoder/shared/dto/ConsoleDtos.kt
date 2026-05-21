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
 * - `updatedAt`     : ISO-8601 timestamp of the snapshot.
 */
@Serializable
data class ClaudeStatusDto(
    val sessionId: String? = null,
    val processAlive: Boolean = false,
    val model: String? = null,
    val plan: String? = null,
    val quotaRemaining: String? = null,
    val updatedAt: String,
)
