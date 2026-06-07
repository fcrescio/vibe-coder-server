package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.claude.ClaudeEvent
import com.siamakerlab.vibecoder.server.claude.ClaudeStreamParser
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.env.ClaudeProcessEnv
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
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
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Spawns `claude` CLI child processes using the stream-json protocol.
 *
 * This is the original sub-agent provider — it mirrors what [SubAgentSessionManager]
 * did before the factory abstraction was introduced.
 */
class ClaudeAgentProcessFactory(
    private val config: ServerConfig,
    private val parser: ClaudeStreamParser = ClaudeStreamParser(),
) : AgentProcessFactory {

    override suspend fun spawn(
        projectRoot: Path,
        savedSessionId: String?,
        agentName: String,
    ): AgentProcess {
        val cmd = resolveClaudeCmd()
        val args = buildList {
            add(cmd)
            add("--output-format"); add("stream-json")
            add("--input-format"); add("stream-json")
            add("--verbose")
            add("--dangerously-skip-permissions")
            add("--disallowedTools")
            add("AskUserQuestion ExitPlanMode EnterPlanMode NotebookEdit")
            if (savedSessionId != null) {
                add("--resume"); add(savedSessionId)
            }
        }
        log.info { "[sub-agent:$agentName] spawning: ${args.joinToString(" ")} (cwd=$projectRoot)" }

        val proc = try {
            val pb = ProcessBuilder(args)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
            ClaudeProcessEnv.applyApiKey(pb.environment())
            pb.start()
        } catch (e: IOException) {
            throw IOException("Failed to spawn sub-agent claude: ${e.message}", e)
        }

        val stdin = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
        val stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val stderr = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))

        // Claude CLI sends a "system" init frame as the first line — read it to extract sessionId.
        val firstLine = withContext(Dispatchers.IO) { stdout.readLine() }
        val sessionId = if (firstLine != null) {
            val events = parser.parseLine(firstLine)
            events.filterIsInstance<ClaudeEvent.SessionStarted>().firstOrNull()?.sessionId
                ?: savedSessionId
        } else {
            savedSessionId
        }

        return AgentProcess(
            process = proc,
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            sessionId = sessionId.orEmpty(),
            projectRoot = projectRoot,
            agentName = agentName,
        )
    }

    override fun parseLine(line: String): List<ClaudeEvent> = parser.parseLine(line)

    override suspend fun buildPromptEnvelope(
        text: String,
        firstPrompt: Boolean,
        agentName: String,
        sessionId: String,
    ): String {
        val actualText = if (firstPrompt) {
            "Use the $agentName sub-agent to do the following:\n\n$text"
        } else text

        return buildJsonObject {
            put("type", "user")
            put("message", buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    addJsonObject {
                        put("type", "text")
                        put("text", actualText)
                    }
                })
            })
        }.toString()
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }
}
