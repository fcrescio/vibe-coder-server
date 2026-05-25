package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private val log = KotlinLogging.logger {}

/**
 * v0.73.0 stub → v0.74.0 — Phase 57 #7 Kotlin LSP 정밀 통합.
 *
 * `KOTLIN_LSP_PATH` 환경 변수로 kotlin-language-server binary detect.
 * 활성화 시 JSON-RPC stdio 로 spawn + `workspace/symbol` 호출 → 정확한 symbol 위치.
 *
 * Architecture: per-project LSP process (workspace rootUri 가 프로젝트 별로 다름).
 * 첫 lookup 때 spawn + initialize (indexing 시간 발생, 큰 프로젝트는 30초+).
 * 이후 같은 프로젝트는 cache hit.
 *
 * Fallback: LSP 미설정 / spawn 실패 / timeout → 호출자가 [SymbolFinder] regex 사용.
 *
 * Threading: 각 process 는 single-threaded — `ReentrantLock` 으로 request/response 순차 처리.
 * stderr 는 background thread 로 drain (block 방지).
 */
class KotlinLspService(private val workspace: WorkspacePath) {

    private val lspPath: String? = System.getenv("KOTLIN_LSP_PATH")?.ifBlank { null }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** projectId → LSP process. 첫 호출 시 lazy spawn. */
    private val instances = ConcurrentHashMap<String, LspInstance>()

    val isAvailable: Boolean by lazy {
        val p = lspPath ?: return@lazy false
        val path = Path.of(p)
        val ok = Files.exists(path) && Files.isExecutable(path)
        if (!ok) log.warn { "KOTLIN_LSP_PATH set but not executable: $p" }
        else log.info { "Kotlin LSP detected: $p (per-project spawn on first lookup)" }
        ok
    }

    data class DefinitionHit(
        val relPath: String,
        val lineNumber: Int,
        val kind: String,
        val line: String,
    )

    /**
     * Symbol name 으로 workspace 전체 검색 (`workspace/symbol`). 결과는 SymbolFinder.Hit
     * 와 호환 모양. 첫 호출은 LSP indexing 시간 만큼 block (큰 프로젝트는 30s+).
     */
    fun definition(projectId: String, symbolName: String): List<DefinitionHit> {
        if (!isAvailable || symbolName.isBlank()) return emptyList()
        val projectRoot = workspace.projectRoot(projectId)
        if (!Files.isDirectory(projectRoot)) return emptyList()
        val instance = instances.getOrPut(projectId) {
            LspInstance(lspPath!!, projectRoot).also { it.initialize() }
        }
        return runCatching { instance.workspaceSymbol(symbolName, projectRoot) }
            .onFailure { log.warn(it) { "LSP workspace/symbol failed for $projectId: ${it.message}" } }
            .getOrDefault(emptyList())
    }

    /** Shutdown 모든 LSP process (server stop 시). */
    fun shutdown() {
        instances.values.forEach { runCatching { it.shutdown() } }
        instances.clear()
    }

    // ─────────────────────────────────────────────────────────────────────
    // LspInstance — per-project LSP process.
    // ─────────────────────────────────────────────────────────────────────

    private inner class LspInstance(
        private val binaryPath: String,
        private val rootPath: Path,
    ) {
        private val process: Process by lazy {
            ProcessBuilder(binaryPath)
                .directory(rootPath.toFile())
                .redirectErrorStream(false)
                .start()
        }
        private val out: OutputStream by lazy { process.outputStream }
        private val reader: BufferedReader by lazy {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        }
        private val nextId = AtomicInteger(1)
        private val ioLock = ReentrantLock()
        @Volatile private var initialized = false
        @Volatile private var stderrDrainer: Thread? = null

        fun initialize() {
            if (initialized) return
            ioLock.withLock {
                if (initialized) return@withLock
                // background drain stderr — block 방지.
                stderrDrainer = thread(start = true, isDaemon = true, name = "lsp-stderr-$rootPath") {
                    runCatching {
                        process.errorStream.bufferedReader().forEachLine { line ->
                            if (log.isDebugEnabled()) log.debug { "lsp-stderr: $line" }
                        }
                    }
                }
                val initParams = buildJsonObject {
                    put("processId", JsonPrimitive(ProcessHandle.current().pid().toInt()))
                    put("rootUri", JsonPrimitive("file://${rootPath.toAbsolutePath()}"))
                    put("capabilities", buildJsonObject {
                        put("workspace", buildJsonObject {
                            put("symbol", buildJsonObject {
                                put("dynamicRegistration", JsonPrimitive(false))
                            })
                        })
                    })
                }
                val resp = request("initialize", initParams, timeoutMs = 60_000)  // 큰 프로젝트는 indexing 시간 김.
                if (resp == null) {
                    log.warn { "LSP initialize failed (timeout) — killing process" }
                    runCatching { process.destroyForcibly() }
                    throw IOException("LSP initialize timeout")
                }
                notify("initialized", buildJsonObject {})
                initialized = true
                log.info { "LSP initialized for $rootPath" }
            }
        }

        fun workspaceSymbol(query: String, projectRoot: Path): List<DefinitionHit> {
            val params = buildJsonObject { put("query", JsonPrimitive(query)) }
            val resp = request("workspace/symbol", params, timeoutMs = 15_000) ?: return emptyList()
            val result = resp["result"] as? JsonArray ?: return emptyList()
            return result.take(100).mapNotNull { it.parseSymbol(projectRoot) }
        }

        private fun kotlinx.serialization.json.JsonElement.parseSymbol(projectRoot: Path): DefinitionHit? {
            val obj = this as? JsonObject ?: return null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
            val kindInt = (obj["kind"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val location = (obj["location"] as? JsonObject) ?: return null
            val uri = (location["uri"] as? JsonPrimitive)?.contentOrNull ?: return null
            val range = (location["range"] as? JsonObject) ?: return null
            val start = (range["start"] as? JsonObject) ?: return null
            val line = (start["line"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val filePath = runCatching { Path.of(java.net.URI.create(uri)) }.getOrNull() ?: return null
            if (!filePath.startsWith(projectRoot.toAbsolutePath())) return null
            val relPath = projectRoot.toAbsolutePath().relativize(filePath).toString().replace('\\', '/')
            val lineContent = runCatching {
                Files.lines(filePath).use { it.skip(line.toLong()).findFirst().orElse("") }
            }.getOrDefault("").take(200)
            return DefinitionHit(
                relPath = relPath,
                lineNumber = line + 1,  // LSP 는 0-based, UI 는 1-based
                kind = SYMBOL_KIND_NAMES[kindInt] ?: "symbol",
                line = lineContent,
            )
        }

        private fun request(method: String, params: JsonObject, timeoutMs: Long): JsonObject? = ioLock.withLock {
            val id = nextId.getAndIncrement()
            val req = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(id))
                put("method", JsonPrimitive(method))
                put("params", params)
            }
            writeMessage(req)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val msg = readMessage(deadline - System.currentTimeMillis()) ?: return@withLock null
                val msgId = (msg["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (msgId == id) return@withLock msg
                // notification / 다른 request — drain.
            }
            null
        }

        private fun notify(method: String, params: JsonObject) = ioLock.withLock {
            val n = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive(method))
                put("params", params)
            }
            writeMessage(n)
        }

        private fun writeMessage(msg: JsonObject) {
            val body = json.encodeToString(JsonObject.serializer(), msg).toByteArray(StandardCharsets.UTF_8)
            out.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            out.write(body)
            out.flush()
        }

        private fun readMessage(remainingMs: Long): JsonObject? {
            // Content-Length: N\r\n\r\n{json}
            val headerLines = mutableListOf<String>()
            val start = System.currentTimeMillis()
            while (true) {
                if (System.currentTimeMillis() - start > remainingMs) return null
                val line = readLineSafe() ?: return null
                if (line.isEmpty()) break
                headerLines += line
            }
            val contentLength = headerLines
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: return null
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) return null
                read += n
            }
            return runCatching { json.parseToJsonElement(String(buf)) as JsonObject }.getOrNull()
        }

        private fun readLineSafe(): String? = try { reader.readLine() } catch (e: IOException) { null }

        fun shutdown() {
            runCatching { request("shutdown", buildJsonObject {}, timeoutMs = 5_000) }
            runCatching { notify("exit", buildJsonObject {}) }
            runCatching {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    companion object {
        /** LSP SymbolKind enum (rough mapping for display). */
        private val SYMBOL_KIND_NAMES = mapOf(
            5 to "class", 6 to "method", 11 to "interface", 12 to "function",
            13 to "var", 14 to "constant", 23 to "object",
        )
    }
}
