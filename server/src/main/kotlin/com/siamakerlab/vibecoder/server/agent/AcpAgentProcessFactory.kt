package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.claude.ClaudeEvent
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.projects.ProjectScaffolder
import com.siamakerlab.vibecoder.server.adb.AdbService
import com.siamakerlab.vibecoder.server.devices.DeviceService
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
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}
private const val MAX_AGENT_INSTRUCTIONS_CHARS = 32 * 1024
private const val DEFAULT_ASSISTANT_BUFFER = "__default__"
private const val VIBE_CONTEXT_TOO_LONG_CODE = -31004

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
    private val adbService: AdbService? = null,
    private val deviceService: DeviceService? = null,
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
        val policy = SubAgentToolPolicy.forAgent(agentName)

        val cmd = System.getenv("VIBECODER_AGENT_COMMAND")?.takeIf { it.isNotBlank() }
            ?: config.agent.command
        val vibeHome = prepareRuntimeVibeHome(agentName, policy)

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
                        put("readTextFile", policy.allowFsRead)
                        put("writeTextFile", policy.allowFsWrite)
                    }
                    put("terminal", policy.allowTerminal)
                    putJsonObject("device") {
                        val enabled = policy.allowDevice && deviceService != null
                        put("screencap", enabled)
                        put("analyzeScreenshot", enabled)
                        put("tap", enabled)
                        put("swipe", enabled)
                        put("launchApp", enabled)
                    }
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
                agentName = agentName,
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
        if (idElement != null && handleDeviceRequest(process, idElement, obj)) return emptyList()

        return parseLine(line)
    }

    override fun parseLine(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return emptyList()

        val id = obj["id"]
        if (id != null && (obj["result"] != null || obj["error"] != null)) {
            return parseResponse(obj)
        }

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
                val text = textContent(update["content"]) ?: return out
                val messageId = update["messageId"]?.jsonPrimitive?.contentOrNull
                    ?: update["message_id"]?.jsonPrimitive?.contentOrNull
                    ?: DEFAULT_ASSISTANT_BUFFER
                assistantBuffers.computeIfAbsent(messageId) { StringBuilder() }.append(text)
                out += ClaudeEvent.AssistantMessage(text = text, isPartial = true)
            }

            "agent_thought_chunk" -> {
                val text = textContent(update["content"]) ?: return out
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

    private fun parseResponse(obj: JsonObject): List<ClaudeEvent> {
        obj["error"]?.jsonObject?.let { error ->
            val rawCode = error["code"]?.jsonPrimitive?.intOrNull
            val code = error["code"]?.jsonPrimitive?.contentOrNull ?: "acp_error"
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "ACP request failed"
            val normalizedCode = if (
                rawCode == VIBE_CONTEXT_TOO_LONG_CODE ||
                message.contains("context too long", ignoreCase = true) ||
                message.contains("maximum context", ignoreCase = true)
            ) {
                "context_too_long"
            } else {
                code
            }
            return listOf(ClaudeEvent.ErrorEvent(code = normalizedCode, message = message))
        }

        val result = obj["result"]?.jsonObject ?: return emptyList()
        val stopReason = result["stopReason"]?.jsonPrimitive?.contentOrNull
            ?: result["stop_reason"]?.jsonPrimitive?.contentOrNull
            ?: return emptyList()
        val out = mutableListOf<ClaudeEvent>()
        val text = flushAssistantBuffers()
        if (text.isNotBlank()) {
            out += ClaudeEvent.AssistantMessage(text = text, isPartial = false)
        }
        out += ClaudeEvent.Done(reason = stopReason)
        return out
    }

    private fun flushAssistantBuffers(): String {
        if (assistantBuffers.isEmpty()) return ""
        val text = assistantBuffers.keys.sorted().joinToString("") { key ->
            assistantBuffers.remove(key)?.toString().orEmpty()
        }
        return text
    }

    private fun textContent(element: JsonElement?): String? {
        val obj = runCatching { element?.jsonObject }.getOrNull()
            ?: return runCatching { element?.jsonPrimitive?.contentOrNull }.getOrNull()
        return obj["text"]?.jsonPrimitive?.contentOrNull
            ?: obj["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: obj["content"]?.jsonPrimitive?.contentOrNull
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
            val policy = SubAgentToolPolicy.forAgent(agentName)
            val adbInfo = (deviceService?.deviceSummary() ?: adbService?.deviceSummary())?.let { "\nADB devices: $it" } ?: ""
            buildString {
                append("You are running as the `")
                append(agentName)
                append("` sub-agent inside Vibe Coder Server.\n")
                if (policy.extraInstructions.isNotBlank()) {
                    append("\n")
                    append(policy.extraInstructions)
                    append("\n")
                }
                if (!instructions.isNullOrBlank()) {
                    append("\nSub-agent instructions:\n")
                    append(instructions.trim())
                    append("\n")
                }
                append("\nUse only the tools enabled for this sub-agent. ")
                append("Do not invent file contents or command output.\n")
                if (adbInfo.isNotBlank()) {
                    append(adbInfo)
                    append("\n")
                }
                append("\n")
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
                val policy = SubAgentToolPolicy.forAgent(process.agentName)
                if (!policy.allowFsRead) {
                    respondRequestError(process, id, method, "filesystem reads are disabled for ${process.agentName}")
                    return true
                }
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
                val policy = SubAgentToolPolicy.forAgent(process.agentName)
                if (!policy.allowFsWrite) {
                    respondRequestError(process, id, method, "filesystem writes are disabled for ${process.agentName}")
                    return true
                }
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
        val policy = SubAgentToolPolicy.forAgent(process.agentName)
        return when (val method = obj["method"]?.jsonPrimitive?.contentOrNull) {
            "terminal/create" -> {
                if (!policy.allowTerminal) {
                    respondRequestError(process, id, method, "terminal/shell commands are disabled for ${process.agentName}; use permitted device tools or stop with a blocked trace")
                    return true
                }
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
                if (!policy.allowTerminal) {
                    respondRequestError(process, id, method, "terminal/shell commands are disabled for ${process.agentName}")
                    return true
                }
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
                if (!policy.allowTerminal) {
                    respondRequestError(process, id, method, "terminal/shell commands are disabled for ${process.agentName}")
                    return true
                }
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
                if (!policy.allowTerminal) {
                    respondRequestError(process, id, method, "terminal/shell commands are disabled for ${process.agentName}")
                    return true
                }
                terminals.remove(terminalId(obj))?.let { terminal ->
                    if (terminal.process.isAlive) terminal.process.destroy()
                    terminal.readerJob?.cancel()
                }
                write(process, result(id) {})
                true
            }
            "terminal/kill" -> {
                if (!policy.allowTerminal) {
                    respondRequestError(process, id, method, "terminal/shell commands are disabled for ${process.agentName}")
                    return true
                }
                terminals[terminalId(obj)]?.process?.destroyForcibly()
                write(process, result(id) {})
                true
            }
            else -> false
        }
    }

    private suspend fun handleDeviceRequest(process: AgentProcess, id: JsonElement, obj: JsonObject): Boolean {
        val rawMethod = obj["method"]?.jsonPrimitive?.contentOrNull ?: return false
        val method = if (rawMethod.startsWith("_")) rawMethod.substring(1) else rawMethod
        if (!method.startsWith("device/")) return false

        val policy = SubAgentToolPolicy.forAgent(process.agentName)
        if (!policy.allowDevice) {
            respondRequestError(process, id, method, "device tools are disabled for ${process.agentName}")
            return true
        }
        val svc = deviceService ?: run {
            respondRequestError(process, id, method, "device tools are not configured for sub-agents")
            return true
        }

        return when (method) {
            "device/screencap" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val serial = params["serial"]?.jsonPrimitive?.contentOrNull
                        ?: svc.listDevices().firstOrNull()?.serial
                    if (serial == null) {
                        respondRequestError(process, id, method, "no device serial provided and no device connected")
                        return@runCatching
                    }
                    val b64 = svc.screencapBase64(serial)
                    if (b64 == null) {
                        respondRequestError(process, id, method, "screencap failed")
                        return@runCatching
                    }
                    write(process, result(id) {
                        put("serial", serial)
                        put("mimeType", "image/png")
                        put("data", b64)
                    })
                }.onFailure {
                    respondRequestError(process, id, method, it.message ?: "screencap failed")
                }
                true
            }
            "device/tap" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val serial = params["serial"]?.jsonPrimitive?.contentOrNull
                        ?: svc.listDevices().firstOrNull()?.serial
                    val x = params["x"]?.jsonPrimitive?.intOrNull
                    val y = params["y"]?.jsonPrimitive?.intOrNull
                    if (serial == null) {
                        respondRequestError(process, id, method, "no device serial provided and no device connected")
                    } else if (x == null || y == null) {
                        respondRequestError(process, id, method, "x and y are required")
                    } else {
                        val tap = svc.tap(serial, x, y)
                        if (!tap.success) {
                            respondRequestError(process, id, method, tap.error ?: tap.output.ifBlank { "tap failed" })
                        } else {
                            write(process, result(id) {
                                put("ok", true)
                                put("message", "Tapped $x,$y.")
                                if (tap.output.isNotBlank()) put("output", tap.output.take(2000))
                            })
                        }
                    }
                }.onFailure {
                    respondRequestError(process, id, method, it.message ?: "tap failed")
                }
                true
            }
            "device/swipe" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val serial = params["serial"]?.jsonPrimitive?.contentOrNull
                        ?: svc.listDevices().firstOrNull()?.serial
                    val x1 = params["x1"]?.jsonPrimitive?.intOrNull
                    val y1 = params["y1"]?.jsonPrimitive?.intOrNull
                    val x2 = params["x2"]?.jsonPrimitive?.intOrNull
                    val y2 = params["y2"]?.jsonPrimitive?.intOrNull
                    val duration = params["duration"]?.jsonPrimitive?.intOrNull ?: 300
                    if (serial == null) {
                        respondRequestError(process, id, method, "no device serial provided and no device connected")
                    } else if (x1 == null || y1 == null || x2 == null || y2 == null) {
                        respondRequestError(process, id, method, "x1, y1, x2, y2 are required")
                    } else {
                        val swipe = svc.swipe(serial, x1, y1, x2, y2, duration)
                        if (!swipe.success) {
                            respondRequestError(process, id, method, swipe.error ?: swipe.output.ifBlank { "swipe failed" })
                        } else {
                            write(process, result(id) {
                                put("ok", true)
                                put("message", "Swiped $x1,$y1 to $x2,$y2.")
                                if (swipe.output.isNotBlank()) put("output", swipe.output.take(2000))
                            })
                        }
                    }
                }.onFailure {
                    respondRequestError(process, id, method, it.message ?: "swipe failed")
                }
                true
            }
            "device/launch_app" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val serial = params["serial"]?.jsonPrimitive?.contentOrNull
                        ?: svc.listDevices().firstOrNull()?.serial
                    val packageName = params["packageName"]?.jsonPrimitive?.contentOrNull
                        ?: params["package_name"]?.jsonPrimitive?.contentOrNull
                    val activity = params["activity"]?.jsonPrimitive?.contentOrNull
                    if (serial == null) {
                        respondRequestError(process, id, method, "no device serial provided and no device connected")
                    } else if (packageName.isNullOrBlank()) {
                        respondRequestError(process, id, method, "packageName is required")
                    } else {
                        val launch = svc.launchApp(serial, packageName, activity)
                        if (!launch.success) {
                            respondRequestError(process, id, method, launch.error ?: launch.output.ifBlank { "launch failed" })
                        } else {
                            Thread.sleep(800)
                            write(process, result(id) {
                                put("ok", true)
                                put("serial", serial)
                                put("packageName", packageName)
                                if (!activity.isNullOrBlank()) put("activity", activity)
                                put("message", "Launched $packageName${activity?.let { "/$it" } ?: ""} on $serial.")
                                put("output", launch.output.take(2000))
                            })
                        }
                    }
                }.onFailure {
                    respondRequestError(process, id, method, it.message ?: "launch failed")
                }
                true
            }
            "device/analyze_screenshot" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val serial = params["serial"]?.jsonPrimitive?.contentOrNull
                        ?: svc.listDevices().firstOrNull()?.serial
                    val question = params["question"]?.jsonPrimitive?.contentOrNull
                        ?: "Describe the current Android device screen and mention visible UI issues."
                    if (serial == null) {
                        respondRequestError(process, id, method, "no device serial provided and no device connected")
                        return@runCatching
                    }
                    val b64 = svc.screencapBase64(serial)
                    if (b64 == null) {
                        respondRequestError(process, id, method, "screencap failed")
                        return@runCatching
                    }
                    val prepared = prepareScreenshotForVision(b64)
                    val rawAnswer = analyzeImageWithEndpoint(
                        question = prepared.navigationQuestion(question),
                        base64Image = prepared.base64,
                        mimeType = "image/png",
                    )
                    val answer = convertCoordinatesInAnswer(rawAnswer, prepared)
                    val adbCoords = extractAdbCoordinates(rawAnswer, prepared)
                    write(process, result(id) {
                        put("serial", serial)
                        put("mimeType", "image/png")
                        put("data", b64)
                        putJsonObject("visionPreview") {
                            put("width", prepared.width)
                            put("height", prepared.height)
                            put("originalWidth", prepared.originalWidth)
                            put("originalHeight", prepared.originalHeight)
                            put("offsetX", prepared.offsetX)
                            put("offsetY", prepared.offsetY)
                            put("scale", prepared.scale)
                            put("coordinateHint", prepared.coordinateHint)
                        }
                        if (adbCoords != null) {
                            put("adbCoordinates", adbCoords)
                        }
                        put("answer", answer)
                    })
                }.onFailure {
                    respondRequestError(process, id, method, it.message ?: "visual analysis failed")
                }
                true
            }
            else -> false
        }
    }

    private fun prepareScreenshotForVision(base64Image: String): VisionPreparedImage {
        val sourceBytes = Base64.getDecoder().decode(base64Image)
        val source = ImageIO.read(ByteArrayInputStream(sourceBytes))
            ?: return VisionPreparedImage(
                base64 = base64Image,
                width = 0,
                height = 0,
                originalWidth = 0,
                originalHeight = 0,
                offsetX = 0,
                offsetY = 0,
                scale = 1.0,
            )
        val target = 1000
        val canvas = BufferedImage(target, target, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(source, 0, 0, target, target, null)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(canvas, "png", out)
        val scaleX = target.toDouble() / source.width
        val scaleY = target.toDouble() / source.height
        return VisionPreparedImage(
            base64 = Base64.getEncoder().encodeToString(out.toByteArray()),
            width = target,
            height = target,
            originalWidth = source.width,
            originalHeight = source.height,
            offsetX = 0,
            offsetY = 0,
            scale = scaleX,
            scaleY = scaleY,
        )
    }

    /**
     * Post-processes the LLM answer to convert any preview coordinates (1000x1000)
     * to real ADB screenshot coordinates. Handles patterns like:
     *   (x, y), (x,y)  →  (adb_x, adb_y)
     *   x=123, y=456  →  x=adb_x, y=adb_y
     */
    private fun convertCoordinatesInAnswer(answer: String, prepared: VisionPreparedImage): String {
        if (prepared.originalWidth == 0 || prepared.originalHeight == 0) return answer
        val ow = prepared.originalWidth
        val oh = prepared.originalHeight
        val pw = prepared.width  // 1000

        fun convertPreviewToAdb(px: Int, py: Int): Pair<Int, Int> {
            val adbX = (px * ow / pw).coerceIn(0, ow - 1)
            val adbY = (py * oh / pw).coerceIn(0, oh - 1)
            return adbX to adbY
        }

        // Pattern 1: JSON array [digits, digits]
        val jsonArrayPattern = Regex("""\[(\d{1,4})\s*,\s*(\d{1,4})\]""")
        var result = jsonArrayPattern.replace(answer) { match ->
            val px = match.groupValues[1].toInt()
            val py = match.groupValues[2].toInt()
            if (px in 0..1000 && py in 0..1000) {
                val (ax, ay) = convertPreviewToAdb(px, py)
                "[$ax, $ay]"
            } else {
                match.value
            }
        }

        // Pattern 2: (digits, digits) or (digits,digits)
        val parenPattern = Regex("""\((\d{1,4})\s*,\s*(\d{1,4})\)""")
        result = parenPattern.replace(result) { match ->
            val px = match.groupValues[1].toInt()
            val py = match.groupValues[2].toInt()
            if (px in 0..1000 && py in 0..1000) {
                val (ax, ay) = convertPreviewToAdb(px, py)
                "($ax, $ay)"
            } else {
                match.value
            }
        }

        // Pattern 3: x=digits, y=digits or x=digits y=digits
        val xyPattern = Regex("""x\s*=\s*(\d{1,4})\s*[,;]?\s*y\s*=\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
        result = xyPattern.replace(result) { match ->
            val px = match.groupValues[1].toInt()
            val py = match.groupValues[2].toInt()
            if (px in 0..1000 && py in 0..1000) {
                val (ax, ay) = convertPreviewToAdb(px, py)
                "x=$ax, y=$ay"
            } else {
                match.value
            }
        }

        return result
    }

    /**
     * Extracts semantic preview coordinates from the LLM answer and converts them
     * to real ADB coordinates. Any top-level JSON-style `"key": [x, y]` pair is
     * preserved with the same key in the returned object.
     */
    private fun extractAdbCoordinates(answer: String, prepared: VisionPreparedImage): JsonObject? {
        if (prepared.originalWidth == 0 || prepared.originalHeight == 0) return null
        val ow = prepared.originalWidth
        val oh = prepared.originalHeight
        val pw = prepared.width

        val coordPattern = Regex(""""(\w+)"\s*:\s*\[(\d{1,4})\s*,\s*(\d{1,4})\]""")
        val matches = coordPattern.findAll(answer).toList()
        if (matches.isEmpty()) return null

        var found = false
        return buildJsonObject {
            for (m in matches) {
                val key = m.groupValues[1]
                val x = m.groupValues[2].toIntOrNull() ?: continue
                val y = m.groupValues[3].toIntOrNull() ?: continue
                if (x !in 0..prepared.width || y !in 0..prepared.height) continue
                val adbX = (x * ow / pw).coerceIn(0, ow - 1)
                val adbY = (y * oh / prepared.height).coerceIn(0, oh - 1)
                putJsonArray(key) {
                    add(adbX)
                    add(adbY)
                }
                found = true
            }
        }.takeIf { found }
    }

    private suspend fun analyzeImageWithEndpoint(question: String, base64Image: String, mimeType: String): String =
        withContext(Dispatchers.IO) {
            val endpoint = visionEndpoint().trimEnd('/')
            val url = if (endpoint.endsWith("/chat/completions")) endpoint else "$endpoint/chat/completions"
            val payload = buildJsonObject {
                put("model", visionModelName())
                put("max_tokens", 1200)
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", question)
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:$mimeType;base64,$base64Image")
                                }
                            })
                        }
                    })
                }
            }.toString()
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build()
            val response = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) {
                throw IOException("vision endpoint returned HTTP ${response.statusCode()}: ${response.body().take(300)}")
            }
            val root = json.parseToJsonElement(response.body()).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
                ?: root["content"]?.jsonPrimitive?.contentOrNull
                ?: response.body().take(2000)
        }

    private fun visionEndpoint(): String =
        System.getenv("VIBECODER_VISION_ENDPOINT")?.takeIf { it.isNotBlank() }
            ?: System.getenv("VIBECODER_AGENT_ENDPOINT")?.takeIf { it.isNotBlank() }
            ?: "http://10.89.0.3:8080/v1"

    private fun visionModelName(): String =
        System.getenv("VIBECODER_VISION_MODEL_NAME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("VIBECODER_AGENT_MODEL_NAME")?.takeIf { it.isNotBlank() }
            ?: "qwen3.6-A3B-spec"

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

    private data class VisionPreparedImage(
        val base64: String,
        val width: Int,
        val height: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val offsetX: Int,
        val offsetY: Int,
        val scale: Double,
        val scaleY: Double = scale,
    ) {
        val coordinateHint: String
            get() = "Vision image is ${width}x${height}. Original ADB screenshot is " +
                "${originalWidth}x${originalHeight}. Stretched to fill preview. " +
                "adb_x=vision_x*${originalWidth}/$width, adb_y=vision_y*${originalHeight}/$height."

        fun navigationQuestion(question: String): String =
            """
            $question

            The image is a ${width}x${height} stretched version of the real device screen (${originalWidth}x${originalHeight}).
            First describe where elements are (top, middle, bottom).
            If the question asks for tap targets or if you recommend an action, include a compact JSON object with semantic target names and preview coordinates, for example:
            {"playButton": [preview_x, preview_y], "settingsButton": [preview_x, preview_y]}
            Only include coordinates for real visible UI targets. Omit JSON coordinates when no actionable target is visible.
            preview_x and preview_y must be integers between 0 and $width/$height in the stretched preview.
            Do NOT convert to device coordinates — the server does that automatically.
            """.trimIndent()
    }

    private fun prepareRuntimeVibeHome(agentName: String, policy: SubAgentToolPolicy): Path {
        val sourceHome = Path.of(
            System.getenv("VIBE_HOME")?.takeIf { it.isNotBlank() }
                ?: config.agent.home
        )
        val runtimeHome = workspace.root
            .resolve(".vibecoder/agent/vibe-home-${agentName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            .toAbsolutePath()
            .normalize()
        Files.createDirectories(runtimeHome)
        Files.createDirectories(runtimeHome.resolve("logs/session"))

        val sourceConfig = sourceHome.resolve("config.toml")
        val targetConfig = runtimeHome.resolve("config.toml")
        if (sourceConfig.exists()) {
            val text = sourceConfig.readText()
            val sanitized = sanitizeConfigToml(text, runtimeHome, policy)
            if (targetConfig.notExists() || targetConfig.readText() != sanitized) {
                targetConfig.writeText(sanitized)
            }
        } else if (targetConfig.notExists()) {
            targetConfig.writeText(applyToolPolicy(ensureDeviceToolPaths(defaultLocalConfig(runtimeHome)), policy))
        }

        val sourceEnv = sourceHome.resolve(".env")
        if (sourceEnv.exists()) {
            Files.copy(sourceEnv, runtimeHome.resolve(".env"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return runtimeHome
    }

    private fun sanitizeConfigToml(text: String, runtimeHome: Path, policy: SubAgentToolPolicy): String {
        val sessionDir = runtimeHome.resolve("logs/session").toString().replace("\\", "\\\\")
        val saveDirLine = Regex("""(?m)^save_dir\s*=\s*".*"$""")
        val replaced = if (saveDirLine.containsMatchIn(text)) {
            text.replace(saveDirLine, """save_dir = "$sessionDir"""")
        } else {
            text.trimEnd() + "\n\n[session_logging]\nsave_dir = \"$sessionDir\"\nsession_prefix = \"session\"\nenabled = true\n"
        }
        val withDeviceTools = ensureDeviceToolPaths(replaced)
        return applyToolPolicy(forceLocalAgentConfig(withDeviceTools), policy)
            .replace(Regex("""(?m)^enable_telemetry\s*=\s*true\s*$"""), "enable_telemetry = false")
            .replace(Regex("""(?m)^enable_update_checks\s*=\s*true\s*$"""), "enable_update_checks = false")
            .replace(Regex("""(?m)^enable_auto_update\s*=\s*true\s*$"""), "enable_auto_update = false")
    }

    private fun ensureDeviceToolPaths(text: String): String {
        val deviceToolPath = "/opt/vibe-coder/vibe-tools/device.py"
        val toolPathsLine = Regex("""(?m)^tool_paths\s*=\s*\[(.*)]$""")
        val match = toolPathsLine.find(text)
        if (match == null) return text.trimEnd() + "\ntool_paths = [\"$deviceToolPath\"]\n"
        val existing = match.groupValues[1].trim()
        if (existing.contains(deviceToolPath, ignoreCase = true)) return text
        val updated = if (existing.isBlank()) {
            "tool_paths = [\"$deviceToolPath\"]"
        } else {
            "tool_paths = [$existing, \"$deviceToolPath\"]"
        }
        return text.replace(match.value, updated)
    }

    private fun applyToolPolicy(text: String, policy: SubAgentToolPolicy): String {
        val enabled = when {
            policy.allowDevice && !policy.allowTerminal && !policy.allowFsWrite ->
                listOf("device_launch_app", "device_screencap", "device_analyze_screenshot", "device_tap", "device_swipe")
            else -> emptyList()
        }
        val disabled = buildSet {
            if (!policy.allowTerminal) add("bash")
            if (!policy.allowFsRead) {
                add("read")
                add("grep")
            }
            if (!policy.allowFsWrite) {
                add("write_file")
                add("edit")
            }
            if (!policy.allowDevice) {
                add("device_screencap")
                add("device_analyze_screenshot")
                add("device_launch_app")
                add("device_tap")
                add("device_swipe")
            }
            if (!policy.allowTerminal && !policy.allowFsRead && !policy.allowFsWrite && !policy.allowDevice) {
                addAll(listOf("task", "web_fetch", "web_search", "todo", "skill", "ask_user_question", "exit_plan_mode"))
            }
        }.toList()

        fun tomlList(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        fun replaceListLine(input: String, key: String, values: List<String>): String {
            val line = Regex("""(?m)^$key\s*=\s*\[.*]$""")
            val replacement = "$key = ${tomlList(values)}"
            return if (line.containsMatchIn(input)) input.replace(line, replacement)
            else input.trimEnd() + "\n$replacement\n"
        }

        return replaceListLine(
            replaceListLine(text, "enabled_tools", enabled),
            "disabled_tools",
            disabled,
        )
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
