package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.claude.ClaudeEvent
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.projects.ProjectScaffolder
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}
private const val MAX_AGENT_INSTRUCTIONS_CHARS = 32 * 1024

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
    private val requestIds = AtomicLong(1)
    private val nextTerminalId = AtomicLong(1)
    private val terminals = ConcurrentHashMap<String, TerminalProcess>()

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

        try {
            // Initialize
            request(stdin, stdout, "initialize", buildJsonObject {
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
                    request(stdin, stdout, "session/load", buildJsonObject {
                        put("cwd", projectRoot.toString())
                        put("sessionId", savedSessionId)
                        putJsonArray("mcp_servers") {}
                    }, timeoutMs = 30_000)
                }.onFailure {
                    log.info { "[sub-agent:$agentName] failed to load ACP session $savedSessionId; starting new (${it.message})" }
                }.getOrNull()
            } else null

            val finalResult = sessionResult ?: request(stdin, stdout, "session/new", buildJsonObject {
                put("cwd", projectRoot.toString())
                putJsonArray("mcp_servers") {}
            }, timeoutMs = 20_000)

            val sessionId = finalResult["sessionId"]?.jsonPrimitive?.contentOrNull
                ?: finalResult["session_id"]?.jsonPrimitive?.contentOrNull
                ?: savedSessionId
                ?: throw IllegalStateException("vibe-acp session did not return session_id")

            return AgentProcess(
                process = proc,
                stdin = stdin,
                stdout = stdout,
                stderr = stderr,
                sessionId = sessionId,
                projectRoot = projectRoot.toAbsolutePath().normalize(),
            )
        } catch (e: Exception) {
            proc.destroy()
            throw e
        }
    }

    override suspend fun handleLine(process: AgentProcess, line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return emptyList()
        val idElement = obj["id"]
        val method = obj["method"]?.jsonPrimitive?.contentOrNull

        if (idElement != null && method == "session/request_permission") {
            respondPermission(process, idElement)
            return emptyList()
        }
        if (idElement != null && handleFsRequest(process, idElement, obj)) return emptyList()
        if (idElement != null && handleTerminalRequest(process, idElement, obj)) return emptyList()

        return parseLine(line)
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
            val instructions = readAgentInstructions(agentName)
            buildString {
                append("You are running as the `")
                append(agentName)
                append("` sub-agent inside Vibe Coder Server.\n")
                if (!instructions.isNullOrBlank()) {
                    append("\nSub-agent instructions:\n")
                    append(instructions.trim())
                    append("\n")
                }
                append("\nUse the available filesystem and terminal tools when project inspection is needed. ")
                append("Do not invent file contents or command output.\n\n")
                append(text)
            }
        } else text

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestIds.getAndIncrement().toString())
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

    private fun readAgentInstructions(agentName: String): String? {
        if (!agentName.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        if (agentName.startsWith('.')) return null
        val claudeConfig = System.getenv("CLAUDE_CONFIG_DIR")?.ifBlank { null }
            ?: (System.getProperty("user.home") + "/.claude")
        val root = Path.of(claudeConfig, "agents").toAbsolutePath().normalize()
        val path = root.resolve("$agentName.md").toAbsolutePath().normalize()
        if (!path.startsWith(root) || !Files.isRegularFile(path)) return null
        return runCatching {
            Files.readString(path, StandardCharsets.UTF_8).take(MAX_AGENT_INSTRUCTIONS_CHARS)
        }.getOrNull()
    }

    private suspend fun request(
        stdin: BufferedWriter,
        stdout: BufferedReader,
        method: String,
        params: JsonObject,
        timeoutMs: Long,
    ): JsonObject {
        val id = requestIds.getAndIncrement().toString()
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
        val response = withTimeoutOrNull(timeoutMs) {
            readResponse(stdout, id, method)
        }
            ?: throw IOException("ACP $method timed out after ${timeoutMs}ms")
        response["error"]?.jsonObject?.let {
            val msg = it["message"]?.jsonPrimitive?.contentOrNull ?: "ACP request failed"
            throw IOException("ACP $method error: $msg")
        }
        return response["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun readResponse(stdout: BufferedReader, expectedId: String, method: String): JsonObject {
        while (true) {
            val line = withContext(Dispatchers.IO) { stdout.readLine() }
                ?: throw IOException("ACP $method stream closed before response")
            if (line.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            val idElement = obj["id"]
            val id = idElement?.jsonPrimitive?.contentOrNull
                ?: idElement?.jsonPrimitive?.intOrNull?.toString()
            if (id == expectedId && (obj["result"] != null || obj["error"] != null)) {
                return obj
            }
            log.debug { "Ignoring ACP handshake side message while waiting for $method: $line" }
        }
    }

    private suspend fun respondPermission(process: AgentProcess, id: JsonElement) {
        write(process, result(id) {
            putJsonObject("outcome") {
                put("outcome", "selected")
                put("optionId", "allow_once")
            }
        })
    }

    private suspend fun handleFsRequest(process: AgentProcess, id: JsonElement, obj: JsonObject): Boolean {
        return when (val method = obj["method"]?.jsonPrimitive?.contentOrNull) {
            "fs/read_text_file" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val path = safeProjectPath(process, params["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    val line = params["line"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: 1
                    val limit = params["limit"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
                    val content = withContext(Dispatchers.IO) {
                        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
                        val selected = lines.asSequence()
                            .drop((line - 1).coerceAtLeast(0))
                            .let { seq -> if (limit != null) seq.take(limit) else seq }
                            .joinToString("\n")
                        if (selected.isEmpty()) selected else "$selected\n"
                    }
                    write(process, result(id) { put("content", content) })
                }.onFailure {
                    respondRequestError(process, id, method, it.message ?: "read failed")
                }
                true
            }
            "fs/write_text_file" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val path = safeProjectPath(process, params["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    val content = params["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    withContext(Dispatchers.IO) {
                        path.parent?.let { Files.createDirectories(it) }
                        Files.writeString(
                            path,
                            content,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        )
                    }
                    write(process, result(id) {})
                }.onFailure {
                    respondRequestError(process, id, method, it.message ?: "write failed")
                }
                true
            }
            else -> false
        }
    }

    private fun safeProjectPath(process: AgentProcess, rawPath: String): Path {
        require(rawPath.isNotBlank()) { "path is required" }
        val candidate = Path.of(rawPath).let {
            if (it.isAbsolute) it else process.projectRoot.resolve(it)
        }.toAbsolutePath().normalize()
        if (!candidate.startsWith(process.projectRoot)) {
            throw IllegalArgumentException("path outside project workspace: $candidate")
        }
        return candidate
    }

    private suspend fun handleTerminalRequest(process: AgentProcess, id: JsonElement, obj: JsonObject): Boolean {
        return when (val method = obj["method"]?.jsonPrimitive?.contentOrNull) {
            "terminal/create" -> {
                val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                val command = params["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull
                    ?.let { Path.of(it).toAbsolutePath().normalize() }
                    ?: process.projectRoot
                require(cwd.startsWith(process.projectRoot)) { "cwd outside project workspace: $cwd" }
                val maxBytes = params["outputByteLimit"]?.jsonPrimitive?.intOrNull
                    ?: params["output_byte_limit"]?.jsonPrimitive?.intOrNull
                    ?: 200_000
                val terminalId = "subagent-terminal-${nextTerminalId.getAndIncrement()}"
                val child = ProcessBuilder("/bin/sh", "-lc", command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .also { pb ->
                        pb.environment()["ANDROID_HOME"] = System.getenv("ANDROID_HOME") ?: "/opt/android-sdk"
                        pb.environment()["ANDROID_SDK_ROOT"] = System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk"
                        pb.environment()["PATH"] = System.getenv("PATH").orEmpty()
                    }
                    .start()
                val terminal = TerminalProcess(child, maxBytes.coerceAtLeast(4096))
                terminals[terminalId] = terminal
                terminal.readerJob = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    child.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = withContext(Dispatchers.IO) { input.read(buffer) }
                            if (n <= 0) break
                            terminal.append(buffer, n)
                        }
                    }
                }
                write(process, result(id) { put("terminalId", terminalId) })
                true
            }
            "terminal/wait_for_exit" -> {
                val terminal = terminals[terminalId(obj)]
                var exit: Int? = null
                val child = terminal?.process
                if (child != null) {
                    while (exit == null) {
                        val done = withContext(Dispatchers.IO) { child.waitFor(1, TimeUnit.SECONDS) }
                        if (done) exit = child.exitValue()
                    }
                }
                terminal?.readerJob?.join()
                write(process, result(id) {
                    if (exit != null) put("exitCode", exit)
                })
                true
            }
            "terminal/output" -> {
                val terminal = terminals[terminalId(obj)]
                write(process, result(id) {
                    put("output", terminal?.output().orEmpty())
                    put("truncated", terminal?.truncated == true)
                    terminal?.process?.takeIf { !it.isAlive }?.let { child ->
                        putJsonObject("exitStatus") {
                            put("exitCode", child.exitValue())
                        }
                    }
                })
                true
            }
            "terminal/release" -> {
                terminals.remove(terminalId(obj))?.let { terminal ->
                    if (terminal.process.isAlive) terminal.process.destroy()
                    terminal.readerJob?.cancel()
                }
                write(process, result(id) {})
                true
            }
            "terminal/kill" -> {
                terminals[terminalId(obj)]?.process?.destroyForcibly()
                write(process, result(id) {})
                true
            }
            else -> false
        }
    }

    private fun terminalId(obj: JsonObject): String =
        obj["params"]?.jsonObject?.get("terminalId")?.jsonPrimitive?.contentOrNull
            ?: obj["params"]?.jsonObject?.get("terminal_id")?.jsonPrimitive?.contentOrNull
            ?: ""

    private suspend fun respondRequestError(process: AgentProcess, id: JsonElement, method: String, message: String) {
        write(process, buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", -32000)
                put("message", "$method failed: $message")
            }
        })
    }

    private fun result(id: JsonElement, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("result", block)
        }

    private suspend fun write(process: AgentProcess, obj: JsonObject) {
        withContext(Dispatchers.IO) {
            process.stdin.write(obj.toString())
            process.stdin.newLine()
            process.stdin.flush()
        }
    }

    private class TerminalProcess(
        val process: Process,
        private val maxBytes: Int,
    ) {
        private val bytes = ByteArrayOutputStream()
        @Volatile var truncated: Boolean = false
        @Volatile var readerJob: Job? = null

        @Synchronized
        fun append(buffer: ByteArray, length: Int) {
            val available = maxBytes - bytes.size()
            if (available <= 0) {
                truncated = true
                return
            }
            val toWrite = minOf(length, available)
            bytes.write(buffer, 0, toWrite)
            if (toWrite < length) truncated = true
        }

        @Synchronized
        fun output(): String = bytes.toString(StandardCharsets.UTF_8)
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
