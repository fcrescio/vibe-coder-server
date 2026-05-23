package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Side-channel snapshot of Claude's `/status`-equivalent output.
 *
 * Strategy: run `claude /status` as a one-shot subprocess (NOT in the persistent
 * stream session) with a small timeout, parse a few well-known fields out of the
 * plaintext output, and cache the result per project for 60s.
 *
 * Output formats change between Claude releases; we extract best-effort and leave
 * unknowns as null so the UI gracefully degrades.
 */
class ClaudeStatusService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val sessionManager: ClaudeSessionManager,
    private val ttl: Duration = Duration.ofSeconds(60),
) {

    private data class Cached(val dto: ClaudeStatusDto, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun snapshot(projectId: String): ClaudeStatusDto {
        val cached = cache[projectId]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) return cached.dto

        val sessionId = sessionManager.currentSessionId(projectId)
        val alive = sessionManager.isAlive(projectId)
        val parsed = runCatching { runStatusCommand(projectId) }
            .getOrElse {
                log.debug(it) { "[$projectId] /status invocation failed; falling back to session-manager state" }
                ParsedStatus(model = null, plan = null, quotaRemaining = null)
            }
        val dto = ClaudeStatusDto(
            sessionId = sessionId,
            processAlive = alive,
            model = parsed.model,
            plan = parsed.plan,
            quotaRemaining = parsed.quotaRemaining,
            updatedAt = Instant.now().toString(),
        )
        cache[projectId] = Cached(dto, Instant.now().plus(ttl))
        return dto
    }

    private suspend fun runStatusCommand(projectId: String): ParsedStatus = withContext(Dispatchers.IO) {
        val cmd = resolveClaudeCmd()
        val projectRoot = workspace.projectRoot(projectId)
        // v0.12.2 — status 호출도 같은 권한 정책 (status 자체는 read-only 이지만
        // claude CLI 가 init 단계에서 .claude/settings.json 을 평가하므로 일관성 유지).
        val pb = ProcessBuilder(cmd, "--print", "--dangerously-skip-permissions", "/status")
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
        // v0.7.0 — API 키 모드면 ANTHROPIC_API_KEY 주입.
        com.siamakerlab.vibecoder.server.env.ClaudeProcessEnv.applyApiKey(pb.environment())
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor(10, TimeUnit.SECONDS)
        if (proc.isAlive) {
            proc.destroyForcibly()
        }
        parseOutput(output)
    }

    private data class ParsedStatus(
        val model: String?,
        val plan: String?,
        val quotaRemaining: String?,
    )

    private fun parseOutput(raw: String): ParsedStatus {
        val lines = raw.lines()
        var model: String? = null
        var plan: String? = null
        var quota: String? = null
        for (line in lines) {
            val lower = line.lowercase()
            if (model == null && lower.contains("model")) model = line.substringAfter(":", "").trim().ifBlank { null }
            if (plan == null && lower.contains("plan")) plan = line.substringAfter(":", "").trim().ifBlank { null }
            if (quota == null && (lower.contains("quota") || lower.contains("remaining") || lower.contains("usage"))) {
                quota = line.trim().ifBlank { null }
            }
        }
        return ParsedStatus(model, plan, quota)
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }
}
