package com.siamakerlab.vibecoder.console.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siamakerlab.vibecoder.console.data.remote.ConsoleWsClient
import com.siamakerlab.vibecoder.console.data.repository.ConsoleRepository
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.min

/**
 * Owns the Console session lifecycle:
 *  - Initial connect with since=0 (full ring replay).
 *  - Reconnect with since=lastSeq + exponential backoff (1, 2, 4, 8, 16s capped).
 *  - User prompts go through HTTP POST (more robust for one-shot acks).
 *  - Frames received over WS are folded into [ConsoleMessage] for the UI.
 */
@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val repo: ConsoleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ConsoleUiState())
    val state = _state.asStateFlow()

    private var streamJob: Job? = null
    private var reconnectAttempt = 0

    fun bind(projectId: String) {
        if (_state.value.projectId == projectId) return
        _state.update { it.copy(projectId = projectId) }
        refreshStatus()
        loadActions()
        connect()
    }

    private fun refreshStatus() {
        viewModelScope.launch {
            runCatching { repo.status(_state.value.projectId) }
                .onSuccess { dto ->
                    _state.update {
                        it.copy(
                            status = dto,
                            processAlive = dto.processAlive,
                            sessionId = dto.sessionId ?: it.sessionId,
                        )
                    }
                }
        }
    }

    fun requestStatusRefresh() = refreshStatus()

    private fun loadActions() {
        viewModelScope.launch {
            runCatching { repo.listActions(_state.value.projectId) }
                .onSuccess { tree -> _state.update { it.copy(actions = tree) } }
                .onFailure { /* actions are optional — Phase C will surface errors */ }
        }
    }

    private fun connect() {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _state.update { it.copy(connection = ConnectionState.Connecting, error = null) }
            try {
                val session = repo.openSession(_state.value.projectId, _state.value.lastSeq)
                _state.update { it.copy(connection = ConnectionState.Connected) }
                reconnectAttempt = 0
                streamSession(session)
                _state.update { it.copy(connection = ConnectionState.Disconnected) }
            } catch (e: Exception) {
                _state.update { it.copy(connection = ConnectionState.Reconnecting, error = e.message) }
                scheduleReconnect()
            }
        }
    }

    private suspend fun streamSession(session: ConsoleWsClient.ConsoleSession) {
        repo.streamFrames(session).collect { frame ->
            applyFrame(frame)
        }
    }

    private fun scheduleReconnect() {
        viewModelScope.launch {
            val delayMs = min(16_000L, 1000L * (1L shl reconnectAttempt))
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(4)
            delay(delayMs)
            connect()
        }
    }

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return

        // Optimistic local echo so the conversation feels responsive.
        val tempSeq = (_state.value.lastSeq + 1L)
        _state.update {
            it.copy(
                sending = true,
                error = null,
                messages = it.messages + ConsoleMessage.UserPrompt(seq = tempSeq, text = trimmed),
            )
        }
        viewModelScope.launch {
            runCatching { repo.sendPrompt(_state.value.projectId, trimmed) }
                .onSuccess { _state.update { it.copy(sending = false) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            sending = false,
                            error = e.message,
                            messages = it.messages + ConsoleMessage.ErrorNotice(
                                seq = (it.lastSeq + 1L),
                                code = "send_failed",
                                message = e.message ?: "send failed",
                            ),
                        )
                    }
                }
        }
    }

    fun startNewSession() {
        viewModelScope.launch {
            streamJob?.cancel()
            runCatching { repo.startNewSession(_state.value.projectId) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            messages = emptyList(),
                            lastSeq = 0L,
                            sessionId = null,
                            processAlive = false,
                            error = null,
                        )
                    }
                    reconnectAttempt = 0
                    connect()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun invokeAction(actionId: String) {
        viewModelScope.launch {
            runCatching { repo.invokeAction(_state.value.projectId, actionId) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun deleteProject(onDeleted: () -> Unit) {
        val projectId = _state.value.projectId
        viewModelScope.launch {
            streamJob?.cancel()
            runCatching { repo.deleteProject(projectId) }
                .onSuccess { onDeleted() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    private fun applyFrame(frame: WsFrame) {
        when (frame) {
            is WsFrame.ConsoleSessionStarted -> {
                _state.update {
                    it.copy(
                        sessionId = frame.sessionId,
                        processAlive = true,
                        lastSeq = maxOf(it.lastSeq, frame.seq),
                        messages = it.messages + ConsoleMessage.SessionBanner(
                            seq = frame.seq,
                            sessionId = frame.sessionId,
                            model = frame.model,
                            cwd = frame.cwd,
                        ),
                    )
                }
            }
            is WsFrame.ConsoleAssistant -> append(
                ConsoleMessage.Assistant(seq = frame.seq, text = frame.text),
                seq = frame.seq,
            )
            is WsFrame.ConsoleToolUse -> append(
                ConsoleMessage.ToolUse(
                    seq = frame.seq, toolName = frame.toolName,
                    toolUseId = frame.toolUseId, input = frame.input,
                ),
                seq = frame.seq,
            )
            is WsFrame.ConsoleToolResult -> append(
                ConsoleMessage.ToolResult(
                    seq = frame.seq, toolUseId = frame.toolUseId,
                    output = frame.output, isError = frame.isError,
                ),
                seq = frame.seq,
            )
            is WsFrame.ConsoleError -> append(
                ConsoleMessage.ErrorNotice(seq = frame.seq, code = frame.code, message = frame.message),
                seq = frame.seq,
            )
            is WsFrame.ConsoleDone -> append(
                ConsoleMessage.TurnDone(seq = frame.seq, reason = frame.reason),
                seq = frame.seq,
            )
            is WsFrame.ConsoleUnknown -> append(
                ConsoleMessage.Unknown(seq = frame.seq, raw = frame.raw),
                seq = frame.seq,
            )
            is WsFrame.ConsoleSystem -> {
                if (frame.code == "process_crashed" || frame.code == "idle_terminated") {
                    _state.update { it.copy(processAlive = false) }
                }
                append(
                    ConsoleMessage.SystemNotice(seq = frame.seq, code = frame.code, message = frame.message),
                    seq = frame.seq,
                )
            }
            is WsFrame.ConsoleReplayBegin,
            is WsFrame.ConsoleReplayEnd,
            is WsFrame.Ping,
            -> Unit
            else -> Unit
        }
    }

    private fun append(msg: ConsoleMessage, seq: Long) {
        _state.update { st ->
            // Optimistic local echo: if we previously inserted a UserPrompt with seq > lastSeq,
            // drop the optimistic copy now that real frames have caught up. (Phase A server
            // doesn't broadcast UserPrompt back, so we keep our local echo permanently.)
            st.copy(
                lastSeq = maxOf(st.lastSeq, seq),
                messages = st.messages + msg,
            )
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
