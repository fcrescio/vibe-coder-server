package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.claude.ConversationHistoryService
import com.siamakerlab.vibecoder.server.projects.ProjectScaffolder
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
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
import kotlin.io.path.deleteIfExists
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

class MistralVibeAcpSessionManager(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val hub: LogHub,
    private val history: ConversationHistoryService? = null,
) : AgentRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, AcpProjectSession>()
    private val spawnLocks = ConcurrentHashMap<String, Mutex>()
    private val busy = ConcurrentHashMap<String, Boolean>()
    private val assistantBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val terminals = ConcurrentHashMap<String, TerminalProcess>()
    private val nextTerminalId = AtomicLong(1)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun sendPrompt(projectId: String, text: String) {
        require(text.isNotBlank()) { "prompt text is required" }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        require(bytes <= AgentRuntime.MAX_PROMPT_BYTES) {
            "prompt too large ($bytes bytes UTF-8 > ${AgentRuntime.MAX_PROMPT_BYTES})"
        }
        val session = ensureSession(projectId)
        history?.userPrompt(projectId, session.sessionId.ifBlank { readSessionId(projectId) }, text)
        val promptText = if (session.environmentPreambleSent) {
            text
        } else {
            session.environmentPreambleSent = true
            environmentPreamble(projectId, session.projectRoot) + "\n\nUser request:\n" + text
        }
        session.stdinMutex.withLock {
            setBusy(projectId, true)
            val response = try {
                session.request(
                    "session/prompt",
                    buildJsonObject {
                        put("sessionId", session.sessionId)
                        putJsonArray("prompt") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", promptText)
                            })
                        }
                    },
                    timeoutMs = config.agent.timeoutMinutes.coerceAtLeast(1) * 60_000L,
                )
            } catch (e: Exception) {
                setBusy(projectId, false)
                if (e is AcpRequestException && e.isContextTooLong) {
                    flushAssistant(projectId)
                    emitSystem(
                        projectId,
                        "context_too_long",
                        e.message ?: "Context too long. Send /compact to summarize the conversation, then retry.",
                    )
                } else {
                    emitSystem(projectId, "agent_send_failed", e.message ?: "Mistral Vibe ACP prompt failed")
                    terminateSession(projectId)
                }
                throw e
            }
            val stopReason = response["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "end_turn"
            flushAssistant(projectId)
            history?.systemNotice(projectId, session.sessionId.ifBlank { readSessionId(projectId) }, "done", "Mistral Vibe ACP turn finished: $stopReason")
            setBusy(projectId, false)
            hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleDone(reason = stopReason, seq = seq) }
        }
    }

    override suspend fun startNew(projectId: String) {
        terminateSession(projectId)
        assistantBuffers.remove(projectId)
        runCatching { sessionIdFile(projectId).deleteIfExists() }
        hub.resetConsole(topic(projectId))
        emitSystem(projectId, "new_session_requested", "Agent session reset. The next prompt starts a fresh Mistral Vibe session.")
    }

    override suspend fun cancelTurn(projectId: String) {
        val existed = sessions[projectId]?.process?.isAlive == true
        if (!existed) {
            emitSystem(projectId, "cancel_noop", "No active agent turn is running.")
            return
        }
        terminateSession(projectId)
        emitSystem(projectId, "turn_cancelled", "Agent turn cancelled. The next prompt starts a fresh Mistral Vibe session.")
    }

    override fun isAlive(projectId: String): Boolean = sessions[projectId]?.process?.isAlive == true

    override fun currentSessionId(projectId: String): String? =
        sessions[projectId]?.sessionId?.takeIf { it.isNotBlank() } ?: readSessionId(projectId)

    override fun isBusy(projectId: String): Boolean = busy[projectId] == true

    override suspend fun shutdown() {
        sessions.keys.toList().forEach { terminateSession(it) }
        scope.cancel()
    }

    private suspend fun ensureSession(projectId: String): AcpProjectSession {
        sessions[projectId]?.takeIf { it.process.isAlive }?.let { return it }
        val lock = spawnLocks.computeIfAbsent(projectId) { Mutex() }
        return lock.withLock {
            sessions[projectId]?.takeIf { it.process.isAlive } ?: spawnSession(projectId)
        }
    }

    private suspend fun spawnSession(projectId: String): AcpProjectSession {
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) throw IllegalStateException("project root not found: $projectRoot")
        ProjectScaffolder.ensureClaudeFiles(projectRoot)
        val cmd = System.getenv("VIBECODER_AGENT_COMMAND")?.takeIf { it.isNotBlank() } ?: config.agent.command
        val vibeHome = prepareRuntimeVibeHome()
        val proc = ProcessBuilder(cmd)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
            .also { pb ->
                pb.environment()["VIBE_HOME"] = vibeHome.toString()
                pb.environment()["PYTHONUNBUFFERED"] = "1"
            }
            .start()

        val savedId = readSessionId(projectId)
        val session = AcpProjectSession(
            projectId = projectId,
            process = proc,
            stdin = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8)),
            stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)),
            stderr = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)),
            sessionId = savedId.orEmpty(),
            projectRoot = projectRoot,
        )
        sessions[projectId] = session
        session.readerJob = scope.launch { readStdout(session) }
        session.stderrJob = scope.launch { readStderr(session) }

        session.request(
            "initialize",
            buildJsonObject {
                put("protocol_version", 1)
                put("client_capabilities", acpClientCapabilities())
                putJsonObject("client_info") {
                    put("name", "vibe-coder-server")
                    put("title", "Vibe Coder Server")
                    put("version", config.server.version)
                }
            },
            timeoutMs = 10_000,
        )
        val sessionResult = if (savedId != null) {
            runCatching {
                session.request(
                    "session/load",
                    buildJsonObject {
                        put("cwd", projectRoot.toString())
                        put("sessionId", savedId)
                        putJsonArray("mcp_servers") {}
                    },
                    timeoutMs = 30_000,
                )
            }.onFailure {
                log.info { "[$projectId] failed to load ACP session $savedId; starting new session (${it.message})" }
                runCatching { sessionIdFile(projectId).deleteIfExists() }
            }.getOrNull()
        } else {
            null
        } ?: session.request(
            "session/new",
            buildJsonObject {
                put("cwd", projectRoot.toString())
                putJsonArray("mcp_servers") {}
            },
            timeoutMs = 20_000,
        )
        session.sessionId = sessionResult["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: sessionResult["session_id"]?.jsonPrimitive?.contentOrNull
            ?: savedId
            ?: throw IllegalStateException("vibe-acp session did not return session_id")
        runCatching { writeSessionId(projectId, session.sessionId) }
            .onFailure { log.warn(it) { "[$projectId] failed to persist ACP session-id" } }
        hub.emitConsole(topic(projectId)) { seq ->
            WsFrame.ConsoleSessionStarted(sessionId = session.sessionId, model = "Mistral Vibe ACP", cwd = projectRoot.toString(), seq = seq)
        }
        history?.systemNotice(
            projectId,
            session.sessionId,
            if (savedId != null && session.sessionId == savedId) "session_loaded" else "session_started",
            "Mistral Vibe ACP session ready in $projectRoot",
        )
        return session
    }

    private fun prepareRuntimeVibeHome(): Path {
        val sourceHome = Path.of(System.getenv("VIBE_HOME")?.takeIf { it.isNotBlank() } ?: config.agent.home)
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
            targetConfig.writeText(
                defaultLocalConfig(runtimeHome),
            )
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
        System.getenv("VIBECODER_AGENT_ENDPOINT")?.takeIf { it.isNotBlank() } ?: "http://10.89.0.3:8080/v1"

    private fun agentModelAlias(): String =
        System.getenv("VIBECODER_AGENT_MODEL_ALIAS")?.takeIf { it.isNotBlank() } ?: "qwen36"

    private fun agentModelName(): String =
        System.getenv("VIBECODER_AGENT_MODEL_NAME")?.takeIf { it.isNotBlank() } ?: "qwen3.6-A3B-spec"

    private suspend fun readStdout(session: AcpProjectSession) {
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) { session.stdout.readLine() } ?: break
                session.touch()
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val idElement = obj["id"]
                val id = idElement?.jsonPrimitive?.contentOrNull ?: idElement?.jsonPrimitive?.intOrNull?.toString()
                if (id != null && (obj["result"] != null || obj["error"] != null)) {
                    session.pending.remove(id)?.complete(obj)
                    continue
                }
                val method = obj["method"]?.jsonPrimitive?.contentOrNull
                if (method == "session/update") {
                    handleSessionUpdate(session.projectId, obj["params"]?.jsonObject ?: continue)
                    continue
                }
                if (id != null && method != null) {
                    log.info { "[${session.projectId}][vibe-acp] client request: $method id=$id" }
                }
                if (idElement != null && method == "session/request_permission") {
                    respondPermission(session, idElement)
                    continue
                }
                if (idElement != null && handleFsRequest(session, idElement, obj)) {
                    continue
                }
                if (idElement != null && handleTerminalRequest(session, idElement, obj)) {
                    continue
                }
                if (idElement != null && method != null) {
                    log.debug { "[${session.projectId}][vibe-acp] unsupported client request: $method" }
                    respondMethodNotFound(session, idElement, method)
                }
            }
        } finally {
            log.info { "[${session.projectId}][vibe-acp] stdout closed; pending=${session.pending.size}, alive=${session.process.isAlive}" }
            flushAssistant(session.projectId)
            session.pending.values.forEach {
                it.completeExceptionally(IllegalStateException("vibe-acp stdout closed"))
            }
            session.pending.clear()
            setBusy(session.projectId, false)
            sessions.remove(session.projectId, session)
            emitSystem(session.projectId, "agent_process_closed", "Mistral Vibe ACP stream closed.")
            hub.emitConsole(topic(session.projectId)) { seq -> WsFrame.ConsoleDone(reason = "process_closed", seq = seq) }
            if (session.process.isAlive) {
                session.process.destroy()
                withContext(Dispatchers.IO) {
                    if (!session.process.waitFor(2, TimeUnit.SECONDS)) session.process.destroyForcibly()
                }
            }
        }
    }

    private fun acpClientCapabilities(): JsonObject =
        buildJsonObject {
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

    private suspend fun handleFsRequest(session: AcpProjectSession, id: JsonElement, obj: JsonObject): Boolean {
        return when (val method = obj["method"]?.jsonPrimitive?.contentOrNull) {
            "fs/read_text_file" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val path = safeProjectPath(session, params["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
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
                    session.write(result(id) {
                        put("content", content)
                    })
                }.onFailure {
                    respondRequestError(session, id, method, it.message ?: "read failed")
                }
                true
            }
            "fs/write_text_file" -> {
                runCatching {
                    val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val path = safeProjectPath(session, params["path"]?.jsonPrimitive?.contentOrNull.orEmpty())
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
                    session.write(result(id) {})
                }.onFailure {
                    respondRequestError(session, id, method, it.message ?: "write failed")
                }
                true
            }
            else -> false
        }
    }

    private fun safeProjectPath(session: AcpProjectSession, rawPath: String): Path {
        require(rawPath.isNotBlank()) { "path is required" }
        val candidate = Path.of(rawPath).let {
            if (it.isAbsolute) it else session.projectRoot.resolve(it)
        }.toAbsolutePath().normalize()
        if (!candidate.startsWith(session.projectRoot)) {
            throw IllegalArgumentException("path outside project workspace: $candidate")
        }
        return candidate
    }

    private suspend fun handleTerminalRequest(session: AcpProjectSession, id: JsonElement, obj: JsonObject): Boolean {
        return when (val method = obj["method"]?.jsonPrimitive?.contentOrNull) {
            "terminal/create" -> {
                val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                val command = params["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull
                    ?.let { Path.of(it) }
                    ?: workspace.projectRoot(session.projectId)
                val maxBytes = params["outputByteLimit"]?.jsonPrimitive?.intOrNull
                    ?: params["output_byte_limit"]?.jsonPrimitive?.intOrNull
                    ?: 200_000
                val terminalId = "terminal-${nextTerminalId.getAndIncrement()}"
                val process = ProcessBuilder("/bin/sh", "-lc", command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .also { pb ->
                        pb.environment()["ANDROID_HOME"] = System.getenv("ANDROID_HOME") ?: "/opt/android-sdk"
                        pb.environment()["ANDROID_SDK_ROOT"] = System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk"
                        pb.environment()["PATH"] = System.getenv("PATH").orEmpty()
                    }
                    .start()
                val terminal = TerminalProcess(process, maxBytes.coerceAtLeast(4096))
                terminals[terminalId] = terminal
                terminal.readerJob = scope.launch {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = withContext(Dispatchers.IO) { input.read(buffer) }
                            if (n <= 0) break
                            terminal.append(buffer, n)
                            session.touch()
                        }
                    }
                }
                session.write(result(id) {
                    put("terminalId", terminalId)
                })
                true
            }
            "terminal/wait_for_exit" -> {
                val terminal = terminals[terminalId(obj)]
                var exit: Int? = null
                val process = terminal?.process
                if (process != null) {
                    while (exit == null) {
                        session.touch()
                        val done = withContext(Dispatchers.IO) { process.waitFor(1, TimeUnit.SECONDS) }
                        if (done) exit = process.exitValue()
                    }
                }
                terminal?.readerJob?.join()
                session.write(result(id) {
                    if (exit != null) put("exitCode", exit)
                })
                true
            }
            "terminal/output" -> {
                val terminal = terminals[terminalId(obj)]
                session.write(result(id) {
                    put("output", terminal?.output().orEmpty())
                    put("truncated", terminal?.truncated == true)
                    terminal?.process?.takeIf { !it.isAlive }?.let { process ->
                        putJsonObject("exitStatus") {
                            put("exitCode", process.exitValue())
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
                session.write(result(id) {})
                true
            }
            "terminal/kill" -> {
                terminals[terminalId(obj)]?.process?.destroyForcibly()
                session.write(result(id) {})
                true
            }
            else -> false
        }
    }

    private fun environmentPreamble(projectId: String, projectRoot: Path): String {
        val androidHome = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() } ?: "/opt/android-sdk"
        val gradleHint = "/home/vibe/.local/gradle/bin/gradle"
        return """
            Vibe Coder environment context:
            - You are editing Android project `$projectId` at `$projectRoot`.
            - Read `CLAUDE.md` before broad changes; it contains the project rules and Android toolchain policy.
            - This is a non-interactive web/mobile console. Do not use menus, REPLs, watch modes, or commands waiting for stdin.
            - Android SDK is available through ANDROID_HOME/ANDROID_SDK_ROOT, normally `$androidHome`.
            - Prefer `./gradlew :app:assembleDebug --no-daemon` when a wrapper exists.
            - If `gradlew` is missing, use installed Gradle on PATH or `$gradleHint` to create the wrapper; do not download toolchains manually.
            - Before coding, inspect the project briefly with file reads or short shell commands. Before finishing, run a targeted build when practical and report the result.
            - Keep responses concise: changed files, key decisions, build status, next blocker if any.
        """.trimIndent()
    }

    private fun terminalId(obj: JsonObject): String =
        obj["params"]?.jsonObject?.get("terminalId")?.jsonPrimitive?.contentOrNull
            ?: obj["params"]?.jsonObject?.get("terminal_id")?.jsonPrimitive?.contentOrNull
            ?: ""

    private fun result(id: JsonElement, block: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("result", block)
        }

    private suspend fun readStderr(session: AcpProjectSession) {
        runCatching {
            while (true) {
                val line = withContext(Dispatchers.IO) { session.stderr.readLine() } ?: break
                if (line.isNotBlank()) log.info { "[${session.projectId}][vibe-acp] $line" }
            }
        }.onFailure {
            if (session.process.isAlive) {
                log.debug(it) { "[${session.projectId}][vibe-acp] stderr reader stopped" }
            }
        }
    }

    private suspend fun handleSessionUpdate(projectId: String, params: JsonObject) {
        val update = params["update"]?.jsonObject ?: return
        when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull
            ?: update["session_update"]?.jsonPrimitive?.contentOrNull) {
            "agent_message_chunk" -> {
                val text = update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return
                assistantBuffers.compute(projectId) { _, existing ->
                    (existing ?: StringBuilder()).append(text)
                }
            }
            "agent_thought_chunk" -> {
                val text = update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleSystem(code = "thought", message = text, seq = seq) }
            }
            "tool_call" -> {
                flushAssistant(projectId)
                val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull
                    ?: update["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: return
                val name = acpToolName(update)
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleToolUse(toolName = name, input = update, toolUseId = id, seq = seq) }
                history?.toolUse(projectId, currentSessionId(projectId), name, id, update)
            }
            "tool_call_update" -> {
                flushAssistant(projectId)
                val id = update["toolCallId"]?.jsonPrimitive?.contentOrNull
                    ?: update["tool_call_id"]?.jsonPrimitive?.contentOrNull ?: return
                val isError = update["status"]?.jsonPrimitive?.contentOrNull == "failed"
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleToolResult(toolUseId = id, output = update, isError = isError, seq = seq) }
                history?.toolResult(projectId, currentSessionId(projectId), id, update, isError)
            }
            "usage_update" -> {
                val used = update["used"]?.jsonPrimitive?.contentOrNull
                val size = update["size"]?.jsonPrimitive?.contentOrNull
                val msg = listOfNotNull(used?.let { "used $it" }, size?.let { "size $it" }).joinToString(" · ")
                if (msg.isNotBlank()) hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleSystem(code = "usage", message = msg, seq = seq) }
            }
        }
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

    private suspend fun respondPermission(session: AcpProjectSession, id: JsonElement) {
        val mode = config.agent.permissionMode.trim().lowercase()
        val optionId = when (mode) {
            "reject_once", "reject", "deny", "deny_once" -> "reject_once"
            else -> "allow_once"
        }
        session.write(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("result") {
                putJsonObject("outcome") {
                    put("outcome", "selected")
                    put("option_id", optionId)
                }
            }
        })
    }

    private suspend fun respondMethodNotFound(session: AcpProjectSession, id: JsonElement, method: String) {
        respondRequestError(session, id, method, "Method not found: $method", code = -32601)
    }

    private suspend fun respondRequestError(session: AcpProjectSession, id: JsonElement, method: String, message: String, code: Int = -32000) {
        session.write(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", "$method failed: $message")
            }
        })
    }

    private suspend fun terminateSession(projectId: String) {
        val session = sessions.remove(projectId) ?: return
        assistantBuffers.remove(projectId)
        session.pending.values.forEach { it.completeExceptionally(IllegalStateException("session terminated")) }
        runCatching { session.stdin.close() }
        if (session.process.isAlive) {
            session.process.destroy()
            withContext(Dispatchers.IO) {
                if (!session.process.waitFor(5, TimeUnit.SECONDS)) session.process.destroyForcibly()
            }
        }
        session.readerJob?.cancel()
        session.stderrJob?.cancel()
        setBusy(projectId, false)
    }

    private suspend fun setBusy(projectId: String, value: Boolean) {
        val prev = busy.put(projectId, value)
        if (prev == value) return
        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleBusyState(busy = value, seq = seq) }
        hub.emitConsole(AgentRuntime.PROJECTS_TOPIC) { seq -> WsFrame.ProjectBusyChanged(projectId = projectId, busy = value, seq = seq) }
    }

    private suspend fun emitSystem(projectId: String, code: String, message: String) {
        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleSystem(code = code, message = message, seq = seq) }
        history?.systemNotice(projectId, currentSessionId(projectId), code, message)
    }

    private suspend fun flushAssistant(projectId: String) {
        val text = assistantBuffers.remove(projectId)?.toString()?.takeIf { it.isNotBlank() } ?: return
        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleAssistant(text = text, isPartial = false, seq = seq) }
        history?.assistantText(projectId, currentSessionId(projectId), text)
    }

    private fun topic(projectId: String) = LogHub.consoleTopic(projectId)

    private fun sessionIdFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("vibe-acp-session.id")

    private fun readSessionId(projectId: String): String? {
        val f = sessionIdFile(projectId)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    private fun writeSessionId(projectId: String, sessionId: String) {
        val f = sessionIdFile(projectId)
        Files.createDirectories(f.parent)
        f.writeText(sessionId)
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

    private inner class AcpProjectSession(
        val projectId: String,
        val process: Process,
        val stdin: BufferedWriter,
        val stdout: BufferedReader,
        val stderr: BufferedReader,
        val stdinMutex: Mutex = Mutex(),
        val pending: ConcurrentHashMap<String, CompletableDeferred<JsonObject>> = ConcurrentHashMap(),
        val projectRoot: Path,
        @Volatile var sessionId: String = "",
        @Volatile var environmentPreambleSent: Boolean = false,
        @Volatile var readerJob: Job? = null,
        @Volatile var stderrJob: Job? = null,
        @Volatile var lastActivityMs: Long = System.currentTimeMillis(),
    ) {
        private var nextId = 1L

        suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
            val id = (nextId++).toString()
            val deferred = CompletableDeferred<JsonObject>()
            pending[id] = deferred
            write(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
            val response = awaitWithIdleTimeout(deferred, method, timeoutMs)
            response["error"]?.jsonObject?.let {
                val msg = it["message"]?.jsonPrimitive?.contentOrNull ?: "ACP request failed"
                val code = it["code"]?.jsonPrimitive?.intOrNull
                val data = it["data"]?.jsonObject
                throw AcpRequestException(code = code, detail = msg, data = data)
            }
            return response["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        private suspend fun awaitWithIdleTimeout(
            deferred: CompletableDeferred<JsonObject>,
            method: String,
            timeoutMs: Long,
        ): JsonObject {
            while (true) {
                withTimeoutOrNull(1_000L) { deferred.await() }?.let { return it }
                if (deferred.isCompleted) return deferred.await()

                val idleMs = System.currentTimeMillis() - lastActivityMs
                if (idleMs > timeoutMs) {
                    pending.values.remove(deferred)
                    throw IllegalStateException(
                        "Timed out waiting for $method after ${idleMs / 1000}s without ACP activity",
                    )
                }
            }
        }

        fun touch() {
            lastActivityMs = System.currentTimeMillis()
        }

        suspend fun write(obj: JsonObject) {
            withContext(Dispatchers.IO) {
                stdin.write(obj.toString())
                stdin.newLine()
                stdin.flush()
            }
        }
    }

    private class AcpRequestException(
        val code: Int?,
        detail: String,
        val data: JsonObject?,
    ) : IllegalStateException(detail) {
        val isContextTooLong: Boolean
            get() = code == VIBE_CONTEXT_TOO_LONG_CODE ||
                message.orEmpty().contains("context too long", ignoreCase = true) ||
                message.orEmpty().contains("maximum context", ignoreCase = true)
    }

    companion object {
        private const val VIBE_CONTEXT_TOO_LONG_CODE = -31004
    }
}
