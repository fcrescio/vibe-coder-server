package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
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
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
 * Owns the lifecycle of one persistent `claude` child process per project.
 *
 * - First [sendPrompt] for a project spawns the process (resuming if a saved session-id exists).
 * - Subsequent prompts re-use the same stdin/stdout — no cold start.
 * - [startNew] tears down the current process and deletes the saved session-id.
 * - [shutdown] sends SIGTERM (then SIGKILL after 5 s) to every alive session.
 *
 * All disk reads/writes funnel through [workspace] so [WorkspacePath]'s path-safety rules apply.
 */
class ClaudeSessionManager(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val hub: LogHub,
    private val parser: ClaudeStreamParser = ClaudeStreamParser(),
    /** Idle SIGTERM after this duration. session-id file is preserved. */
    private val idleTimeout: Duration = Duration.ofMinutes(30),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ProjectSession>()

    /** Synchronizes spawn — prevents two simultaneous "first prompt" arrivals racing to start a process. */
    private val spawnLocks = ConcurrentHashMap<String, Mutex>()

    init {
        // Idle reaper
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                reapIdleSessions()
            }
        }
    }

    /** Send [text] as a user turn. Spawns the session if necessary. */
    suspend fun sendPrompt(projectId: String, text: String) {
        require(text.isNotBlank()) { "prompt text is required" }
        require(text.length <= MAX_PROMPT_BYTES) { "prompt too large (${text.length} > $MAX_PROMPT_BYTES)" }

        val session = ensureSession(projectId)
        val envelope = buildJsonObject {
            put("type", "user")
            put("message", buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                })
            })
        }.toString()

        session.stdinMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    session.stdin.write(envelope)
                    session.stdin.newLine()
                    session.stdin.flush()
                }
                session.lastActivity = Instant.now()
            } catch (e: IOException) {
                log.warn(e) { "[$projectId] stdin write failed; will respawn on next prompt" }
                emitSystem(projectId, "process_crashed", "Claude process is no longer accepting input (${e.message}). Retrying on next prompt.")
                terminateSession(projectId)
                throw e
            }
        }
    }

    /** Stop the current process (if any), forget its session-id, clear replay ring. */
    suspend fun startNew(projectId: String) {
        terminateSession(projectId)
        runCatching { sessionIdFile(projectId).deleteIfExists() }
        hub.resetConsole(topic(projectId))
        emitSystem(projectId, "new_session_requested", "Session reset. The next prompt starts a fresh conversation.")
    }

    fun isAlive(projectId: String): Boolean =
        sessions[projectId]?.process?.isAlive == true

    fun currentSessionId(projectId: String): String? = sessions[projectId]?.sessionId

    suspend fun shutdown() {
        log.info { "shutting down ${sessions.size} Claude session(s)" }
        sessions.keys.toList().forEach { terminateSession(it) }
        scope.cancel()
    }

    // region internals

    private suspend fun ensureSession(projectId: String): ProjectSession {
        sessions[projectId]?.let { existing ->
            if (existing.process.isAlive) return existing
            log.info { "[$projectId] stale session detected (process exited); respawning" }
            terminateSession(projectId)
        }
        val lock = spawnLocks.computeIfAbsent(projectId) { Mutex() }
        return lock.withLock {
            sessions[projectId]?.takeIf { it.process.isAlive } ?: spawnSession(projectId)
        }
    }

    private suspend fun spawnSession(projectId: String): ProjectSession {
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) {
            throw IllegalStateException("project root not found: $projectRoot")
        }
        val savedId = readSessionId(projectId)
        val cmd = resolveClaudeCmd()
        val args = buildList {
            add(cmd)
            add("--output-format"); add("stream-json")
            add("--input-format"); add("stream-json")
            add("--verbose")
            if (savedId != null) {
                add("--resume"); add(savedId)
            }
        }
        log.info { "[$projectId] spawning: ${args.joinToString(" ")} (cwd=$projectRoot)" }

        val proc = try {
            ProcessBuilder(args)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            emitSystem(projectId, "claude_unavailable", "Failed to spawn Claude: ${e.message}")
            throw e
        }

        val stdin = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
        val stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val stderr = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))

        val session = ProjectSession(
            projectId = projectId,
            process = proc,
            stdin = stdin,
            sessionId = savedId,
            lastActivity = Instant.now(),
            wasResuming = savedId != null,
            startedAt = Instant.now(),
        )
        sessions[projectId] = session

        // stdout reader
        session.readerJob = scope.launch {
            try {
                while (isActive) {
                    val line = withContext(Dispatchers.IO) { stdout.readLine() } ?: break
                    if (line.isBlank()) continue
                    handleStdoutLine(projectId, line)
                }
            } catch (e: IOException) {
                log.debug(e) { "[$projectId] stdout reader ended" }
            } finally {
                onProcessExit(projectId, proc, session)
            }
        }
        // stderr reader (informational, but we sample the last few lines for resume-failure detection)
        session.stderrJob = scope.launch {
            try {
                while (isActive) {
                    val line = withContext(Dispatchers.IO) { stderr.readLine() } ?: break
                    if (line.isBlank()) continue
                    log.debug { "[$projectId][stderr] $line" }
                    synchronized(session.stderrTail) {
                        session.stderrTail.addLast(line)
                        while (session.stderrTail.size > STDERR_TAIL_LIMIT) session.stderrTail.pollFirst()
                    }
                }
            } catch (e: IOException) {
                log.debug(e) { "[$projectId] stderr reader ended" }
            }
        }
        return session
    }

    /**
     * Returns true when the just-exited process appears to have died because `--resume <id>`
     * referenced a session the CLI no longer accepts. Heuristic:
     *  - The session was launched with `--resume`.
     *  - It exited within [RESUME_FAILURE_WINDOW_MS] (real session work takes longer).
     *  - Stderr contains one of the [RESUME_FAILURE_PATTERNS] phrases OR no SessionStarted
     *    frame was ever observed (so the CLI never accepted the resume).
     */
    private fun looksLikeResumeFailure(session: ProjectSession): Boolean {
        if (!session.wasResuming) return false
        val elapsed = java.time.Duration.between(session.startedAt, Instant.now()).toMillis()
        if (elapsed > RESUME_FAILURE_WINDOW_MS) return false
        // If a SessionStarted frame was observed, the resume succeeded — the crash is something else.
        if (session.sawSessionStarted) return false
        val stderrText = synchronized(session.stderrTail) { session.stderrTail.joinToString("\n") }.lowercase()
        return RESUME_FAILURE_PATTERNS.any { stderrText.contains(it) }
            || stderrText.isEmpty()    // silent fast exit on resume is treated as failure too
    }

    private suspend fun handleStdoutLine(projectId: String, line: String) {
        val events = parser.parseLine(line)
        if (events.isEmpty()) return
        for (event in events) {
            // capture session-id from the system/init line
            if (event is ClaudeEvent.SessionStarted) {
                sessions[projectId]?.let {
                    it.sessionId = event.sessionId
                    it.sawSessionStarted = true
                }
                runCatching { writeSessionId(projectId, event.sessionId) }
                    .onFailure { log.warn(it) { "[$projectId] failed to persist session-id" } }
            }
            hub.emitConsole(topic(projectId)) { seq -> toWsFrame(event, seq) }
        }
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
        is ClaudeEvent.Unknown -> WsFrame.ConsoleUnknown(raw = event.raw, seq = seq)
    }

    private fun onProcessExit(projectId: String, proc: Process, session: ProjectSession) {
        val exit = runCatching { proc.exitValue() }.getOrNull()
        val crashed = exit != null && exit != 0
        if (crashed) {
            log.warn { "[$projectId] claude exited with code $exit" }
            val resumeFailed = looksLikeResumeFailure(session)
            scope.launch {
                if (resumeFailed) {
                    runCatching { sessionIdFile(projectId).deleteIfExists() }
                    emitSystem(
                        projectId,
                        "resume_failed_starting_new",
                        "Previous Claude session could not be resumed (CLI rejected --resume). " +
                            "Cleared session id; the next prompt will start a new session.",
                    )
                } else {
                    emitSystem(
                        projectId,
                        "process_crashed",
                        "Claude exited with code $exit. Next prompt will attempt to resume the session.",
                    )
                }
            }
        } else {
            log.info { "[$projectId] claude exited cleanly (code=$exit)" }
        }
        runCatching { session.stdin.close() }
        sessions.remove(projectId, session)
    }

    private suspend fun terminateSession(projectId: String) {
        val session = sessions.remove(projectId) ?: return
        runCatching { session.stdin.close() }
        if (session.process.isAlive) {
            session.process.destroy()
            withContext(Dispatchers.IO) {
                if (!session.process.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn { "[$projectId] SIGTERM grace expired; SIGKILL" }
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
                log.info { "[${s.projectId}] idle for ${Duration.between(s.lastActivity, now).toMinutes()}m; SIGTERM" }
                emitSystem(s.projectId, "idle_terminated", "Session went idle and was paused. Send a prompt to resume.")
                terminateSession(s.projectId)
            }
        }
    }

    private suspend fun emitSystem(projectId: String, code: String, message: String) {
        hub.emitConsole(topic(projectId)) { seq ->
            WsFrame.ConsoleSystem(code = code, message = message, seq = seq)
        }
    }

    private fun topic(projectId: String) = LogHub.consoleTopic(projectId)

    private fun sessionIdFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("claude-session.id")

    private fun readSessionId(projectId: String): String? {
        val f = sessionIdFile(projectId)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    private fun writeSessionId(projectId: String, id: String) {
        val f = sessionIdFile(projectId)
        Files.createDirectories(f.parent)
        f.writeText(id)
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }

    private data class ProjectSession(
        val projectId: String,
        val process: Process,
        val stdin: BufferedWriter,
        @Volatile var sessionId: String?,
        @Volatile var lastActivity: Instant,
        val stdinMutex: Mutex = Mutex(),
        @Volatile var readerJob: Job? = null,
        @Volatile var stderrJob: Job? = null,
        /** True iff this process was launched with `--resume <savedId>`. */
        val wasResuming: Boolean = false,
        /** Wall-clock time the process started — used for resume-failure detection. */
        val startedAt: Instant = Instant.now(),
        /** Flips true once a `system/init` frame arrives, proving the CLI accepted the resume. */
        @Volatile var sawSessionStarted: Boolean = false,
        /** Last N stderr lines for resume-failure heuristics. */
        val stderrTail: java.util.ArrayDeque<String> = java.util.ArrayDeque(),
    )

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        const val IDLE_CHECK_INTERVAL_MS = 60_000L
        /** Sessions that die within this window with `--resume` are treated as resume failures. */
        const val RESUME_FAILURE_WINDOW_MS = 5_000L
        const val STDERR_TAIL_LIMIT = 20

        /** Substrings (lowercase) in stderr that mark a resume rejection by the CLI. */
        val RESUME_FAILURE_PATTERNS = listOf(
            "session not found",
            "invalid session",
            "no such session",
            "could not resume",
            "session id not recognized",
            "unknown session",
        )
    }
}
