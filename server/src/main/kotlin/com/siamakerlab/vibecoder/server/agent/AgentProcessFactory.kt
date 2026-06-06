package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.claude.ClaudeEvent
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Path

/**
 * Abstracts the spawning of an agent child process and the parsing of its stdout.
 *
 * Two implementations exist:
 * - [ClaudeAgentProcessFactory] — spawns `claude` CLI with stream-json protocol
 * - [AcpAgentProcessFactory] — spawns `vibe-acp` (or configured command) with ACP JSON-RPC
 */
interface AgentProcessFactory {

    /**
     * Spawn a new agent child process for the given [projectRoot].
     *
     * @param projectRoot  The working directory for the process.
     * @param savedSessionId  A previously persisted session ID to resume, or null for a fresh session.
     * @param agentName  The sub-agent name (e.g. "reviewer"), used for the first-prompt prefix.
     * @return An [AgentProcess] with open stdin/stdout/stderr streams.
     */
    suspend fun spawn(
        projectRoot: Path,
        savedSessionId: String?,
        agentName: String,
    ): AgentProcess

    /**
     * Parse a single non-empty line from the process stdout into zero or more [ClaudeEvent]s.
     *
     * Implementations must be safe to call from any coroutine — no mutable state
     * beyond what is owned by the [AgentProcess] itself.
     */
    fun parseLine(line: String): List<ClaudeEvent>

    /**
     * Handle a single stdout line from [process].
     *
     * Providers that only emit assistant events can rely on [parseLine]. Providers with
     * bidirectional stdout JSON-RPC, such as ACP, override this to also answer client
     * tool requests before returning events to the common sub-agent pipeline.
     */
    suspend fun handleLine(process: AgentProcess, line: String): List<ClaudeEvent> = parseLine(line)

    /**
     * Build the text envelope to write to stdin for a user prompt.
     *
     * @param text  The raw prompt text.
     * @param firstPrompt  Whether this is the first prompt in the session (may trigger a prefix).
     * @param agentName  The sub-agent name for the first-prompt prefix.
     * @param sessionId  The current ACP session ID (used by ACP factory for JSON-RPC requests).
     * @return The string to write to stdin (may be multi-line).
     */
    suspend fun buildPromptEnvelope(
        text: String,
        firstPrompt: Boolean,
        agentName: String,
        sessionId: String,
    ): String
}

data class AgentProcess(
    val process: Process,
    val stdin: BufferedWriter,
    val stdout: BufferedReader,
    val stderr: BufferedReader,
    /** The session ID returned by the agent after initialisation / resume. */
    val sessionId: String,
    /** The project root used as the safety boundary for provider-side tool requests. */
    val projectRoot: Path,
)
