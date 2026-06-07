package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.agent.AgentProcessFactory
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/**
 * v0.44.0 — Owns a separate agent child process per (projectId, agentName) tuple so a project
 * can have multiple agent sessions running in parallel, each acting as a different sub-agent.
 *
 * The main project console keeps using [AgentRuntime]. Sub-agent consoles use this
 * manager. The two are completely independent: they spawn their own processes, write their own
 * session-id files (under `.vibecoder/agent-sessions/`), and broadcast on their own LogHub topic
 * ([LogHub.subAgentConsoleTopic]).
 *
 * The agent identity is communicated by prefixing the very first prompt of a fresh
 * session with `Use the <agentName> sub-agent to ...`. Subsequent prompts inside the same
 * session reuse the spawned child process directly — no extra fork per turn.
 *
 * The actual agent process (Claude CLI or Mistral Vibe ACP) is abstracted behind
 * [AgentProcessFactory], allowing the same manager to work with any provider.
 */
class SubAgentSessionManager(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val hub: LogHub,
    private val factory: AgentProcessFactory,
    private val idleTimeout: Duration = Duration.ofMinutes(30),
    /** v0.49.0 — conversation_turns 영구 적재. null 이면 history persistence 비활성. */
    private val history: ConversationHistoryService? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<AgentKey, AgentSession>()
    private val spawnLocks = ConcurrentHashMap<AgentKey, Mutex>()

    init {
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                reapIdleSessions()
            }
        }
    }

    suspend fun sendPrompt(
        projectId: String,
        agentName: String,
        text: String,
        limits: SubAgentRunLimits = SubAgentRunLimits.defaultsFor(agentName),
    ) {
        require(text.isNotBlank()) { "prompt text is required" }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_PROMPT_BYTES) {
            "prompt too large ($bytes bytes UTF-8 > $MAX_PROMPT_BYTES)"
        }
        val key = AgentKey(projectId, agentName)
        val session = ensureSession(key)
        session.runLimits = limits
        session.toolCallsThisRun = 0
        session.assistantMessagesThisRun = 0
        session.toolCallIdsThisRun.clear()
        // v0.49.0 — user prompt 영구 적재 (sub-agent name 으로 태깅; sub-agent 인지된 채로 history 페이지에 보임).
        history?.userPrompt(projectId, session.sessionId, text, agentName)

        val envelope = factory.buildPromptEnvelope(
            text = prependRunLimits(text, limits),
            firstPrompt = !session.firstPromptSent,
            agentName = agentName,
            sessionId = session.sessionId ?: "",
        )
        session.firstPromptSent = true

        session.stdinMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    session.stdin.write(envelope)
                    session.stdin.newLine()
                    session.stdin.flush()
                }
                session.lastActivity = Instant.now()
            } catch (e: IOException) {
                log.warn(e) { "[${key.id}] stdin write failed; will respawn on next prompt" }
                emitSystem(key, "process_crashed", "Sub-agent is no longer accepting input (${e.message}). Retrying on next prompt.")
                terminateSession(key)
                throw e
            }
        }
    }

    suspend fun startNew(projectId: String, agentName: String) {
        val key = AgentKey(projectId, agentName)
        terminateSession(key)
        runCatching { sessionIdFile(key).deleteIfExists() }
        hub.resetConsole(topic(key))
        emitSystem(key, "new_session_requested", "Sub-agent session reset. The next prompt starts a fresh conversation.")
    }

    suspend fun cancelTurn(projectId: String, agentName: String) {
        val key = AgentKey(projectId, agentName)
        val existed = sessions[key]?.process?.isAlive == true
        if (!existed) {
            emitSystem(key, "cancel_noop", "No sub-agent turn is currently running.")
            return
        }
        terminateSession(key)
        emitSystem(
            key, "turn_cancelled",
            "The user cancelled the sub-agent turn. The next prompt will continue in the same session.",
        )
    }

    fun isAlive(projectId: String, agentName: String): Boolean =
        sessions[AgentKey(projectId, agentName)]?.process?.isAlive == true

    fun currentSessionId(projectId: String, agentName: String): String? =
        sessions[AgentKey(projectId, agentName)]?.sessionId

    /** v0.44.0 — list every (projectId, agentName) tuple currently alive. */
    fun activeAgents(): List<Pair<String, String>> =
        sessions.entries
            .filter { it.value.process.isAlive }
            .map { it.key.projectId to it.key.agentName }

    fun activeAgentsFor(projectId: String): List<String> =
        sessions.entries
            .filter { it.key.projectId == projectId && it.value.process.isAlive }
            .map { it.key.agentName }

    suspend fun shutdown() {
        log.info { "shutting down ${sessions.size} sub-agent session(s)" }
        sessions.keys.toList().forEach { terminateSession(it) }
        scope.cancel()
    }

    // region internals

    private suspend fun ensureSession(key: AgentKey): AgentSession {
        sessions[key]?.let { existing ->
            if (existing.process.isAlive) return existing
            log.info { "[${key.id}] stale sub-agent session; respawning" }
            terminateSession(key)
        }
        val lock = spawnLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            sessions[key]?.takeIf { it.process.isAlive } ?: spawnSession(key)
        }
    }

    private suspend fun spawnSession(key: AgentKey): AgentSession {
        val projectRoot = workspace.projectRoot(key.projectId)
        if (!projectRoot.exists()) {
            throw IllegalStateException("project root not found: $projectRoot")
        }

        val savedId = readSessionId(key)
        val agentProcess = factory.spawn(projectRoot, savedId, key.agentName)

        val session = AgentSession(
            key = key,
            process = agentProcess.process,
            stdin = agentProcess.stdin,
            sessionId = agentProcess.sessionId.ifBlank { savedId },
            lastActivity = Instant.now(),
            // savedId 가 있다는 건 이전 세션 이어감 → agent prefix 이미 주입된 적 있음.
            firstPromptSent = savedId != null,
            startedAt = Instant.now(),
        )
        sessions[key] = session

        session.readerJob = scope.launch {
            try {
                while (isActive) {
                    val line = withContext(Dispatchers.IO) { agentProcess.stdout.readLine() } ?: break
                    if (line.isBlank()) continue
                    handleStdoutLine(key, agentProcess, line)
                }
            } catch (e: IOException) {
                log.debug(e) { "[${key.id}] sub-agent stdout reader ended" }
            } finally {
                onProcessExit(key, agentProcess.process, session)
            }
        }
        session.stderrJob = scope.launch {
            try {
                while (isActive) {
                    val line = withContext(Dispatchers.IO) { agentProcess.stderr.readLine() } ?: break
                    if (line.isBlank()) continue
                    log.debug { "[${key.id}][stderr] $line" }
                }
            } catch (e: IOException) {
                log.debug(e) { "[${key.id}] sub-agent stderr reader ended" }
            }
        }
        return session
    }

    private suspend fun handleStdoutLine(
        key: AgentKey,
        agentProcess: com.siamakerlab.vibecoder.server.agent.AgentProcess,
        line: String,
    ) {
        val events = factory.handleLine(agentProcess, line)
        if (events.isEmpty()) return
        for (event in events) {
            if (!applyRunLimits(key, event)) return
            if (event is ClaudeEvent.SessionStarted) {
                sessions[key]?.sessionId = event.sessionId
                runCatching { writeSessionId(key, event.sessionId) }
                    .onFailure { log.warn(it) { "[${key.id}] failed to persist sub-agent session-id" } }
            }
            hub.emitConsole(topic(key)) { seq -> toWsFrame(event, seq) }
            // v0.49.0 — sub-agent turn 영구 적재.
            val sidForRow = when (event) {
                is ClaudeEvent.SessionStarted -> event.sessionId
                else -> sessions[key]?.sessionId
            }
            history?.event(key.projectId, sidForRow, event, key.agentName)
        }
    }

    private suspend fun applyRunLimits(key: AgentKey, event: ClaudeEvent): Boolean {
        val session = sessions[key] ?: return false
        val limits = session.runLimits
        when (event) {
            is ClaudeEvent.ToolUse -> {
                if (!session.toolCallIdsThisRun.add(event.toolUseId)) return true
                session.toolCallsThisRun += 1
                val max = limits.maxToolCalls
                if (max != null && session.toolCallsThisRun > max) {
                    terminateForLimit(
                        key,
                        "tool_call_limit",
                        "Sub-agent stopped after ${session.toolCallsThisRun - 1} tool call(s); maxToolCalls=$max.",
                    )
                    return false
                }
            }
            is ClaudeEvent.AssistantMessage -> {
                if (!event.isPartial) {
                    session.assistantMessagesThisRun += 1
                    val max = limits.maxAssistantMessages
                    if (max != null && session.assistantMessagesThisRun > max) {
                        terminateForLimit(
                            key,
                            "assistant_message_limit",
                            "Sub-agent stopped after ${session.assistantMessagesThisRun - 1} assistant message(s); maxTurns=$max.",
                        )
                        return false
                    }
                }
            }
            else -> {}
        }
        return true
    }

    private suspend fun terminateForLimit(key: AgentKey, code: String, message: String) {
        log.warn { "[${key.id}] $message" }
        emitSystem(key, code, message)
        terminateSession(key)
    }

    private fun prependRunLimits(text: String, limits: SubAgentRunLimits): String = buildString {
        append("Run limits enforced by server: ")
        append("maxToolCalls=${limits.maxToolCalls?.toString() ?: "unlimited"}, ")
        append("maxTurns=${limits.maxAssistantMessages?.toString() ?: "unlimited"}. ")
        append("If you cannot finish within these limits, stop early and return blocked/partial results.\n\n")
        append(text)
    }

    private fun toWsFrame(event: ClaudeEvent, seq: Long): WsFrame = when (event) {
        is ClaudeEvent.SessionStarted -> WsFrame.ConsoleSessionStarted(
            sessionId = event.sessionId, model = event.model, cwd = event.cwd, seq = seq,
        )
        is ClaudeEvent.AssistantMessage -> WsFrame.ConsoleAssistant(
            text = event.text, isPartial = event.isPartial, seq = seq,
        )
        is ClaudeEvent.ToolUse -> WsFrame.ConsoleToolUse(
            toolName = event.toolName, input = event.input, toolUseId = event.toolUseId, seq = seq,
        )
        is ClaudeEvent.ToolResult -> WsFrame.ConsoleToolResult(
            toolUseId = event.toolUseId, output = event.output, isError = event.isError, seq = seq,
        )
        is ClaudeEvent.ErrorEvent -> WsFrame.ConsoleError(
            code = event.code, message = event.message, seq = seq,
        )
        is ClaudeEvent.Done -> WsFrame.ConsoleDone(reason = event.reason, seq = seq)
        is ClaudeEvent.UsageReport -> {
            val parts = mutableListOf<String>()
            event.inputTokens?.let { parts += "input ${it}" }
            event.outputTokens?.let { parts += "output ${it}" }
            event.cacheReadInputTokens?.let { parts += "cache-read ${it}" }
            event.cacheCreationInputTokens?.let { parts += "cache-create ${it}" }
            WsFrame.ConsoleSystem(code = "usage", message = parts.joinToString(" · "), seq = seq)
        }
        is ClaudeEvent.Unknown -> WsFrame.ConsoleUnknown(raw = event.raw, seq = seq)
    }

    private fun onProcessExit(key: AgentKey, proc: Process, session: AgentSession) {
        val exit = runCatching { proc.exitValue() }.getOrNull()
        val crashed = exit != null && exit != 0
        if (crashed) {
            log.warn { "[${key.id}] sub-agent claude exited with code $exit" }
            scope.launch {
                emitSystem(
                    key,
                    "process_crashed",
                    "Sub-agent claude exited with code $exit. Next prompt will attempt to resume.",
                )
            }
        } else {
            log.info { "[${key.id}] sub-agent claude exited cleanly (code=$exit)" }
        }
        runCatching { session.stdin.close() }
        sessions.remove(key, session)
    }

    private suspend fun terminateSession(key: AgentKey) {
        val session = sessions.remove(key) ?: return
        runCatching { session.stdin.close() }
        if (session.process.isAlive) {
            session.process.destroy()
            withContext(Dispatchers.IO) {
                if (!session.process.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn { "[${key.id}] SIGTERM grace expired; SIGKILL" }
                    session.process.destroyForcibly()
                }
            }
        }
        session.readerJob?.cancel()
        session.stderrJob?.cancel()
    }

    private suspend fun reapIdleSessions() {
        val now = Instant.now()
        val cutoff = now.minus(idleTimeout)
        sessions.values.toList().forEach { s ->
            if (s.lastActivity.isBefore(cutoff)) {
                log.info { "[${s.key.id}] sub-agent idle for ${Duration.between(s.lastActivity, now).toMinutes()}m; SIGTERM" }
                emitSystem(s.key, "idle_terminated", "Sub-agent session went idle and was paused. Send a prompt to resume.")
                terminateSession(s.key)
            }
        }
    }

    private suspend fun emitSystem(key: AgentKey, code: String, message: String) {
        hub.emitConsole(topic(key)) { seq ->
            WsFrame.ConsoleSystem(code = code, message = message, seq = seq)
        }
        // v0.49.0 — system notice 도 history 에 적재 (turn_cancelled / idle_terminated 등).
        history?.systemNotice(key.projectId, sessions[key]?.sessionId, code, message, key.agentName)
    }

    private fun topic(key: AgentKey) = LogHub.subAgentConsoleTopic(key.projectId, key.agentName)

    private fun sessionIdFile(key: AgentKey): Path =
        workspace.vibecoderDir(key.projectId).resolve("agent-sessions").resolve("${key.agentName}.id")

    private fun readSessionId(key: AgentKey): String? {
        val f = sessionIdFile(key)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    private fun writeSessionId(key: AgentKey, id: String) {
        val f = sessionIdFile(key)
        Files.createDirectories(f.parent)
        f.writeText(id)
    }

    private data class AgentKey(val projectId: String, val agentName: String) {
        val id: String get() = "$projectId::$agentName"
    }

    private data class AgentSession(
        val key: AgentKey,
        val process: Process,
        val stdin: BufferedWriter,
        @Volatile var sessionId: String?,
        @Volatile var lastActivity: Instant,
        @Volatile var firstPromptSent: Boolean,
        val stdinMutex: Mutex = Mutex(),
        @Volatile var readerJob: Job? = null,
        @Volatile var stderrJob: Job? = null,
        val startedAt: Instant = Instant.now(),
        @Volatile var runLimits: SubAgentRunLimits = SubAgentRunLimits(),
        @Volatile var toolCallsThisRun: Int = 0,
        @Volatile var assistantMessagesThisRun: Int = 0,
        val toolCallIdsThisRun: MutableSet<String> = mutableSetOf(),
    )

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        const val IDLE_CHECK_INTERVAL_MS = 60_000L
    }
}

data class SubAgentRunLimits(
    val maxToolCalls: Int? = null,
    val maxAssistantMessages: Int? = null,
) {
    companion object {
        fun defaultsFor(agentName: String): SubAgentRunLimits = when (agentName) {
            "phone-ui-navigator" -> SubAgentRunLimits(maxToolCalls = 40, maxAssistantMessages = 8)
            "phone-ui-run-summarizer" -> SubAgentRunLimits(maxToolCalls = 0, maxAssistantMessages = 2)
            else -> SubAgentRunLimits(maxToolCalls = 120, maxAssistantMessages = 20)
        }
    }
}
