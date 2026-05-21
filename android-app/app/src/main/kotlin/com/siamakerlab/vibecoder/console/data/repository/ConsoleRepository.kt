package com.siamakerlab.vibecoder.console.data.repository

import com.siamakerlab.vibecoder.console.data.remote.ApiService
import com.siamakerlab.vibecoder.console.data.remote.ConsoleWsClient
import com.siamakerlab.vibecoder.shared.dto.ActionInvokeRequestDto
import com.siamakerlab.vibecoder.shared.dto.ActionTreeDto
import com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade joining HTTP and WS access for the Claude console.
 *
 * The console flow is:
 *  - WS connection (server → client): live conversation events.
 *  - HTTP POST: user prompt / new-session / action invoke.
 *
 * The ViewModel reconnects the WS with `?since=<lastSeq>` on transient drops.
 */
@Singleton
class ConsoleRepository @Inject constructor(
    private val api: ApiService,
    private val ws: ConsoleWsClient,
) {
    suspend fun status(projectId: String): ClaudeStatusDto = api.claudeStatus(projectId)

    suspend fun deleteProject(projectId: String) = api.deleteProject(projectId)

    suspend fun sendPrompt(projectId: String, text: String) {
        api.consolePrompt(projectId, text)
    }

    suspend fun startNewSession(projectId: String) {
        api.consoleNewSession(projectId)
    }

    suspend fun listActions(projectId: String): ActionTreeDto = api.listActions(projectId)

    suspend fun invokeAction(projectId: String, actionId: String) {
        api.invokeAction(projectId, ActionInvokeRequestDto(actionId = actionId))
    }

    /** Open a streaming WS connection. Caller collects [Flow] and manages reconnect. */
    suspend fun openSession(projectId: String, since: Long): ConsoleWsClient.ConsoleSession =
        ws.connect(projectId, since)

    fun streamFrames(session: ConsoleWsClient.ConsoleSession): Flow<WsFrame> = session.frames
}
