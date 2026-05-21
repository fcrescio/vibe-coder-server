package com.siamakerlab.vibecoder.console.ui.console

import com.siamakerlab.vibecoder.shared.dto.ActionTreeDto
import com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto
import kotlinx.serialization.json.JsonElement

/**
 * Compact representation of one chat-style row in the console.
 * Each row keeps its server-assigned `seq` for replay/ordering.
 */
sealed class ConsoleMessage {
    abstract val seq: Long

    data class SessionBanner(
        override val seq: Long,
        val sessionId: String,
        val model: String?,
        val cwd: String?,
    ) : ConsoleMessage()

    data class UserPrompt(
        override val seq: Long,
        val text: String,
    ) : ConsoleMessage()

    data class Assistant(
        override val seq: Long,
        val text: String,
    ) : ConsoleMessage()

    data class ToolUse(
        override val seq: Long,
        val toolName: String,
        val toolUseId: String,
        val input: JsonElement,
    ) : ConsoleMessage()

    data class ToolResult(
        override val seq: Long,
        val toolUseId: String,
        val output: JsonElement,
        val isError: Boolean,
    ) : ConsoleMessage()

    data class ErrorNotice(
        override val seq: Long,
        val code: String,
        val message: String,
    ) : ConsoleMessage()

    data class SystemNotice(
        override val seq: Long,
        val code: String,
        val message: String,
    ) : ConsoleMessage()

    data class TurnDone(
        override val seq: Long,
        val reason: String,
    ) : ConsoleMessage()

    data class Unknown(
        override val seq: Long,
        val raw: JsonElement,
    ) : ConsoleMessage()
}

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting, Failed }

data class ConsoleUiState(
    val projectId: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected,
    val messages: List<ConsoleMessage> = emptyList(),
    val lastSeq: Long = 0L,
    val sending: Boolean = false,
    val error: String? = null,
    val processAlive: Boolean = false,
    val sessionId: String? = null,
    val status: ClaudeStatusDto? = null,
    val actions: ActionTreeDto? = null,
)
