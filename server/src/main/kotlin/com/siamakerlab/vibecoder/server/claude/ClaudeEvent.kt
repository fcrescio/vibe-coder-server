package com.siamakerlab.vibecoder.server.claude

import kotlinx.serialization.json.JsonElement

/**
 * In-memory representation of one stream-json line emitted by `claude --output-format stream-json`.
 *
 * This is the server-internal model — translation to [com.siamakerlab.vibecoder.shared.ws.WsFrame]
 * sub-types happens in [ConsoleHub] when frames are published to subscribers.
 *
 * Keep this sealed hierarchy independent of the wire format so the CLI format can drift
 * without affecting the wire — [ClaudeStreamParser] absorbs the drift via [Unknown].
 */
sealed class ClaudeEvent {

    data class SessionStarted(
        val sessionId: String,
        val model: String?,
        val cwd: String?,
    ) : ClaudeEvent()

    data class AssistantMessage(
        val text: String,
        val isPartial: Boolean,
    ) : ClaudeEvent()

    data class ToolUse(
        val toolName: String,
        val input: JsonElement,
        val toolUseId: String,
    ) : ClaudeEvent()

    data class ToolResult(
        val toolUseId: String,
        val output: JsonElement,
        val isError: Boolean,
    ) : ClaudeEvent()

    data class ErrorEvent(
        val code: String,
        val message: String,
    ) : ClaudeEvent()

    data class Done(
        val reason: String,
    ) : ClaudeEvent()

    /** CLI emitted a known top-level type we don't model — passed through. */
    data class Unknown(val raw: JsonElement) : ClaudeEvent()
}
