package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.claude.ClaudeEvent
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.projects.ProjectScaffolder
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/**
 * Spawns `vibe-acp` (or configured command) child processes using the ACP JSON-RPC protocol.
 *
 * This factory handles the ACP handshake (initialize → session/new or session/load) and
 * translates ACP `session/update` notifications into [ClaudeEvent] for the common
 * [SubAgentSessionManager] pipeline.
 */
class AcpAgentProcessFactory(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
) : AgentProcessFactory {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val assistantBuffers = ConcurrentHashMap<String, StringBuilder>()

    override suspend fun spawn(
        projectRoot: Path,
        savedSessionId: String?,
        agentName: String,
    ): AgentProcess {
        ProjectScaffolder.ensureClaudeFiles(projectRoot)

        val cmd = System.getenv("VIBECODER_AGENT_COMMAND")?.takeIf { it.isNotBlank() }
            ?: config.agent.command
        val vibeHome = prepareRuntimeVibeHome()

        val proc = ProcessBuilder(cmd)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
            .also { pb ->
                pb.environment()["VIBE_HOME"] = vibeHome.toString()
                pb.environment()["PYTHONUNBUFFERED"] = "1"
            }
            .start()

        val stdin = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
        val stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val stderr = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))

        // ACP handshake
        val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        val readerJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            readResponses(stdout, pending)
        }

        try {
            // Initialize
            request(stdin, pending, "initialize", buildJsonObject {
                put("protocol_version", 1)
                putJsonObject("client_capabilities") {
                    putJsonObject("fs") {
                        put("readTextFile", true)
                        put("writeTextFile", true)
                    }
                    put("terminal", true)
                    putJsonObject("auth") {
                        put("terminal", false)
                    }
                    putJsonObject("_meta") {
                        put("terminal-auth", false)
                        put("vibe-coder-server", true)
                    }
                }
                putJsonObject("client_info") {
                    put("name", "vibe-coder-server")
                    put("title", "Vibe Coder Server")
                    put("version", config.server.version)
                }
            }, timeoutMs = 10_000)

            // Create or load session
            val sessionResult = if (savedSessionId != null) {
                runCatching {
                    request(stdin, pending, "session/load", buildJsonObject {
                        put("cwd", projectRoot.toString())
                        put("sessionId", savedSessionId)
                        putJsonArray("mcp_servers") {}
                    }, timeoutMs = 30_000)
                }.onFailure {
                    log.info { "[sub-agent:$agentName] failed to load ACP session $savedSessionId; starting new (${it.message})" }
                }.getOrNull()
            } else null

            val finalResult = sessionResult ?: request(stdin, pending, "session/new", buildJsonObject {
                put("cwd", projectRoot.toString())
                putJsonArray("mcp_servers") {}
            }, timeoutMs = 20_000)

            val sessionId = finalResult["sessionId"]?.jsonPrimitive?.contentOrNull
                ?: finalResult["session_id"]?.jsonPrimitive?.contentOrNull
                ?: savedSessionId
                ?: throw IllegalStateException("vibe-acp session did not return session_id")

            readerJob.cancel()
            return AgentProcess(
                process = proc,
                stdin = stdin,
                stdout = stdout,
                stderr = stderr,
                sessionId = sessionId,
            )
        } catch (e: Exception) {
            readerJob.cancel()
            proc.destroy()
            throw e
        }
    }

    override fun parseLine(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return emptyList()

        // Skip JSON-RPC responses (have "id" + "result"/"error")
        val id = obj["id"]
        if (id != null && (obj["result"] != null || obj["error"] != null)) return emptyList()

        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        if (method != "session/update") return emptyList()

        val params = obj["params"]?.jsonObject ?: return emptyList()
        val update = params["update"]?.jsonObject ?: return emptyList()

        return parseSessionUpdate(update)
    }

    private fun parseSessionUpdate(update: JsonObject): List<ClaudeEvent> {
        val out = mutableListOf<ClaudeEvent>()

        when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull
            ?: update["session_update"]?.jsonPrimitive?.contentOrNull) {

            "agent_message_chunk" -> {
                val text = update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return out
                out += ClaudeEvent.AssistantMessage(text = text, isPartial = true)
            }

            "agent_thought_chunk" -> {
                val text = update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return out
                out += ClaudeEvent.Unknown(update)
            }

            "tool_call" -> {
                val toolUseId = update["toolCallId"]?.jsonPrimitive?.contentOrNull
                    ?: update["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: return out
                val toolName = acpToolName(update)
                out += ClaudeEvent.ToolUse(toolName = toolName, input = update, toolUseId = toolUseId)
            }

            "tool_call_update" -> {
                val toolUseId = update["toolCallId"]?.jsonPrimitive?.contentOrNull
                    ?: update["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: return out
                val isError = update["status"]?.jsonPrimitive?.contentOrNull == "failed"
                out += ClaudeEvent.ToolResult(toolUseId = toolUseId, output = update, isError = isError)
            }

            "usage_update" -> {
                val used = update["used"]?.jsonPrimitive?.contentOrNull
                val size = update["size"]?.jsonPrimitive?.contentOrNull
                if (used != null || size != null) {
                    out += ClaudeEvent.UsageReport(
                        inputTokens = null,
                        outputTokens = null,
                        cacheReadInputTokens = null,
                        cacheCreationInputTokens = null,
                    )
                }
            }
        }

        return out
    }

    private fun acpToolName(update: JsonObject): String {
        val explicit = update["field_meta"]?.jsonObject?.get("tool_name")?.jsonPrimitive?.contentOrNull
        val title = update["title"]?.jsonPrimitive?.contentOrNull
        val raw = explicit ?: when {
            title == null -> null
            title.startsWith("Reading ") -> "read"
            title.startsWith("Editing ") -> "edit"
            title.startsWith("Writing ") -> "write_file"
            title.startsWith("Grepping ") -> "grep"
            title.startsWith("bash:") -> "bash"
            else -> title
        } ?: "tool"
        return raw.take(64)
    }

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
            put("jsonrpc", "2.0")
            put("id", java.util.concurrent.atomic.AtomicLong(1).incrementAndGet().toString())
            put("method", "session/prompt")
            putJsonObject("params") {
                put("sessionId", sessionId)
                putJsonArray("prompt") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", actualText)
                    })
                }
            }
        }.toString()
    }

    private suspend fun request(
        stdin: BufferedWriter,
        pending: ConcurrentHashMap<String, CompletableDeferred<JsonObject>>,
        method: String,
        params: JsonObject,
        timeoutMs: Long,
    ): JsonObject {
        val id = java.util.concurrent.atomic.AtomicLong(1).incrementAndGet().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        withContext(Dispatchers.IO) {
            stdin.write(request.toString())
            stdin.newLine()
            stdin.flush()
        }
        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: throw IOException("ACP $method timed out after ${timeoutMs}ms")
        pending.remove(id)
        response["error"]?.jsonObject?.let {
            val msg = it["message"]?.jsonPrimitive?.contentOrNull ?: "ACP request failed"
            throw IOException("ACP $method error: $msg")
        }
        return response["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun readResponses(
        stdout: BufferedReader,
        pending: ConcurrentHashMap<String, CompletableDeferred<JsonObject>>,
    ) {
        try {
            while (true) {
                val line = stdout.readLine() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val idElement = obj["id"]
                val id = idElement?.jsonPrimitive?.contentOrNull
                    ?: idElement?.jsonPrimitive?.intOrNull?.toString()
                if (id != null && (obj["result"] != null || obj["error"] != null)) {
                    pending.remove(id)?.complete(obj)
                }
            }
        } catch (_: IOException) {
            // stream closed
        }
    }

    private fun prepareRuntimeVibeHome(): Path {
        val sourceHome = Path.of(
            System.getenv("VIBE_HOME")?.takeIf { it.isNotBlank() }
                ?: config.agent.home
        )
        val runtimeHome = workspace.root.resolve(".vibecoder/agent/vibe-home").toAbsolutePath().normalize()
        Files.createDirectories(runtimeHome)
        Files.createDirectories(runtimeHome.resolve("logs/session"))

        val sourceConfig = sourceHome.resolve("config.toml")
        val targetConfig = runtimeHome.resolve("config.toml")
        if (sourceConfig.exists()) {
            val text = sourceConfig.readText()
            val sanitized = sanitizeConfigToml(text, runtimeHome)
            if (targetConfig.notExists() || targetConfig.readText() != sanitized) {
                targetConfig.writeText(sanitized)
            }
        } else if (targetConfig.notExists()) {
            targetConfig.writeText(defaultLocalConfig(runtimeHome))
        }

        val sourceEnv = sourceHome.resolve(".env")
        if (sourceEnv.exists()) {
            Files.copy(sourceEnv, runtimeHome.resolve(".env"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return runtimeHome
    }

    private fun sanitizeConfigToml(text: String, runtimeHome: Path): String {
        val sessionDir = runtimeHome.resolve("logs/session").toString().replace("\\", "\\\\")
        val saveDirLine = Regex("""(?m)^save_dir\s*=\s*".*"$""")
        val replaced = if (saveDirLine.containsMatchIn(text)) {
            text.replace(saveDirLine, """save_dir = "$sessionDir"""")
        } else {
            text.trimEnd() + "\n\n[session_logging]\nsave_dir = \"$sessionDir\"\nsession_prefix = \"session\"\nenabled = true\n"
        }
        return forceLocalAgentConfig(replaced)
            .replace(Regex("""(?m)^enable_telemetry\s*=\s*true\s*$"""), "enable_telemetry = false")
            .replace(Regex("""(?m)^enable_update_checks\s*=\s*true\s*$"""), "enable_update_checks = false")
            .replace(Regex("""(?m)^enable_auto_update\s*=\s*true\s*$"""), "enable_auto_update = false")
    }

    private fun defaultLocalConfig(runtimeHome: Path): String {
        val endpoint = agentEndpoint()
        val modelName = agentModelName()
        val modelAlias = agentModelAlias()
        return """
            active_model = "$modelAlias"
            enable_telemetry = false
            enable_update_checks = false
            enable_auto_update = false

            [[providers]]
            name = "llamacpp"
            api_base = "$endpoint"
            api_key_env_var = ""
            api_style = "openai"
            backend = "generic"
            reasoning_field_name = "reasoning_content"

            [[models]]
            name = "$modelName"
            provider = "llamacpp"
            alias = "$modelAlias"
            auto_compact_threshold = 200000

            [session_logging]
            save_dir = "${runtimeHome.resolve("logs/session")}"
            session_prefix = "session"
            enabled = true
        """.trimIndent() + "\n"
    }

    private fun forceLocalAgentConfig(text: String): String {
        val endpoint = agentEndpoint()
        val modelName = agentModelName()
        val modelAlias = agentModelAlias()
        val withActive = if (Regex("""(?m)^active_model\s*=\s*".*"$""").containsMatchIn(text)) {
            text.replace(Regex("""(?m)^active_model\s*=\s*".*"$"""), """active_model = "$modelAlias"""")
        } else {
            """active_model = "$modelAlias"""" + "\n" + text
        }
        val withProviderEndpoint = replaceLlamacppApiBase(withActive, endpoint)
        val withProvider = if (Regex("""(?m)^\s*name\s*=\s*"llamacpp"\s*$""").containsMatchIn(withProviderEndpoint)) {
            withProviderEndpoint
        } else {
            withProviderEndpoint.trimEnd() + """

                [[providers]]
                name = "llamacpp"
                api_base = "$endpoint"
                api_key_env_var = ""
                api_style = "openai"
                backend = "generic"
                reasoning_field_name = "reasoning_content"
            """.trimIndent()
        }
        return if (Regex("""(?ms)\[\[models]]\s+name\s*=\s*"\Q$modelName\E".*?alias\s*=\s*"\Q$modelAlias\E"""").containsMatchIn(withProvider)) {
            withProvider
        } else {
            withProvider.trimEnd() + """

                [[models]]
                name = "$modelName"
                provider = "llamacpp"
                alias = "$modelAlias"
                auto_compact_threshold = 200000
            """.trimIndent() + "\n"
        }
    }

    private fun replaceLlamacppApiBase(text: String, endpoint: String): String {
        val lines = text.lines().toMutableList()
        var inProvider = false
        var providerStart = -1
        var providerNameIsLlamacpp = false
        var apiBaseIndex = -1

        fun flushProvider() {
            if (inProvider && providerNameIsLlamacpp) {
                if (apiBaseIndex >= 0) {
                    lines[apiBaseIndex] = """api_base = "$endpoint""""
                } else if (providerStart >= 0) {
                    lines.add(providerStart + 1, """api_base = "$endpoint"""")
                }
            }
            inProvider = false
            providerStart = -1
            providerNameIsLlamacpp = false
            apiBaseIndex = -1
        }

        lines.indices.forEach { i ->
            val line = lines[i].trim()
            if (line == "[[providers]]") {
                flushProvider()
                inProvider = true
                providerStart = i
            } else if (line.startsWith("[[") && line != "[[providers]]") {
                flushProvider()
            } else if (inProvider && line == """name = "llamacpp"""") {
                providerNameIsLlamacpp = true
            } else if (inProvider && line.startsWith("api_base")) {
                apiBaseIndex = i
            }
        }
        flushProvider()
        return lines.joinToString("\n")
    }

    private fun agentEndpoint(): String =
        System.getenv("VIBECODER_AGENT_ENDPOINT")?.takeIf { it.isNotBlank() }
            ?: "http://10.89.0.3:8080/v1"

    private fun agentModelAlias(): String =
        System.getenv("VIBECODER_AGENT_MODEL_ALIAS")?.takeIf { it.isNotBlank() }
            ?: "qwen36"

    private fun agentModelName(): String =
        System.getenv("VIBECODER_AGENT_MODEL_NAME")?.takeIf { it.isNotBlank() }
            ?: "qwen3.6-A3B-spec"
}
