package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.shared.dto.CheckItemDto
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

class EnvDiagnostics(private val config: ServerConfig) {

    fun run(): EnvironmentCheckDto {
        val cli = checkClaude()
        return EnvironmentCheckDto(
            java = checkJava(),
            androidSdk = checkAndroidSdk(),
            git = checkGit(),
            claude = cli,
            workspace = checkWorkspace(),
            claudeAuth = checkClaudeAuth(cli),
        )
    }

    private fun checkJava(): CheckItemDto {
        val version = runtimeCommand(listOf("java", "-version"))
        return if (version.exitCode == 0) {
            CheckItemDto(CheckStatus.OK, "JDK", "java is installed", detail = version.combined.take(200))
        } else {
            CheckItemDto(CheckStatus.ERROR, "JDK", "java not found", detail = version.combined.take(200))
        }
    }

    private fun checkAndroidSdk(): CheckItemDto {
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        return when {
            sdkRoot.isNullOrBlank() ->
                CheckItemDto(
                    CheckStatus.WARNING, "Android SDK",
                    "ANDROID_HOME and ANDROID_SDK_ROOT are both unset",
                    detail = "Gradle builds will fail unless local.properties sdk.dir is set per project."
                )
            !Path.of(sdkRoot).exists() ->
                CheckItemDto(
                    CheckStatus.ERROR, "Android SDK",
                    "$sdkRoot does not exist", detail = null
                )
            else ->
                CheckItemDto(CheckStatus.OK, "Android SDK", "ANDROID_HOME=$sdkRoot", detail = null)
        }
    }

    private fun checkGit(): CheckItemDto {
        val v = runtimeCommand(listOf("git", "--version"))
        return if (v.exitCode == 0)
            CheckItemDto(CheckStatus.OK, "Git", v.combined.trim().ifBlank { "git installed" }, detail = null)
        else
            CheckItemDto(CheckStatus.ERROR, "Git", "git CLI not found", detail = v.combined.take(200))
    }

    private fun checkClaude(): CheckItemDto {
        if (!config.claude.enabled)
            return CheckItemDto(CheckStatus.WARNING, "Claude Code", "claude.enabled is false")
        val cmd = resolveClaudeCmd()
        val v = runtimeCommand(listOf(cmd, "--version"))
        return if (v.exitCode == 0)
            CheckItemDto(CheckStatus.OK, "Claude Code", v.combined.trim().ifBlank { "claude installed" }, detail = "cmd=$cmd")
        else
            CheckItemDto(CheckStatus.ERROR, "Claude Code", "claude CLI not found", detail = "tried `$cmd --version` (set CLAUDE_CMD env)")
    }

    /**
     * Claude CLI 로그인 상태 진단.
     *
     * 판정:
     * - CLI 미설치 → ERROR.
     * - `~/.claude/.credentials.json` (또는 `CLAUDE_CONFIG_DIR/.credentials.json`)
     *   없음 → ERROR + `claude login` 가이드.
     * - 파일은 있지만 안의 `claudeAiOauth.expiresAt` (epoch ms) 가 현재보다
     *   과거 → ERROR ("토큰 만료"). 사용자가 콘솔에서 "Not logged in" 을 받는
     *   가장 흔한 원인.
     * - 만료 시각 6시간 이내 → WARNING (곧 만료 예정 안내).
     * - 그 외 정상 → OK.
     *
     * v0.6.2 변경: 단순 파일 존재 → expiresAt 까지 파싱. v0.5.4 ~ v0.6.1 에서
     * 만료된 자격증명을 들고 콘솔에서 "Not logged in" 을 받는데도 빌드환경
     * 페이지에서 "로그인됨" 으로 표시되던 false positive 해결.
     */
    private fun checkClaudeAuth(cli: CheckItemDto): CheckItemDto {
        if (!config.claude.enabled) {
            return CheckItemDto(CheckStatus.WARNING, "Claude Auth", "claude.enabled is false")
        }
        if (cli.status != CheckStatus.OK) {
            return CheckItemDto(
                CheckStatus.ERROR, "Claude Auth",
                "Claude CLI 가 먼저 설치되어야 인증을 확인할 수 있습니다.",
                detail = "vibe-doctor 또는 'npm i -g @anthropic-ai/claude-code' 로 CLI 설치 후 다시 시도하세요.",
            )
        }

        val cfg = claudeConfigDir()
        val credentials = cfg.resolve(".credentials.json")
        if (!credentials.exists()) {
            return CheckItemDto(
                CheckStatus.ERROR, "Claude Auth",
                "Claude CLI 로그인이 필요합니다.",
                detail = buildClaudeAuthHelp(cfg),
            )
        }

        val expiresAt = readOauthExpiresAt(credentials)
        if (expiresAt == null) {
            // 파일은 있는데 형식 변경 등으로 파싱 실패 → 신중하게 WARNING.
            return CheckItemDto(
                CheckStatus.WARNING, "Claude Auth",
                "자격증명 파일이 있으나 만료 시각을 확인할 수 없습니다.",
                detail = "$credentials\n콘솔에서 'Not logged in' 이 뜨면 'docker exec -it vibe-coder claude login' 으로 재로그인하세요.",
            )
        }

        val nowMs = System.currentTimeMillis()
        val expiryStr = formatInstant(expiresAt)
        return when {
            expiresAt <= nowMs -> CheckItemDto(
                CheckStatus.ERROR, "Claude Auth",
                "토큰이 만료되었습니다 (${expiryStr}). 재로그인이 필요합니다.",
                detail = buildClaudeAuthHelp(cfg) + "\n\n만료된 파일: $credentials",
            )
            expiresAt - nowMs < 6 * 3600 * 1000L -> CheckItemDto(
                CheckStatus.WARNING, "Claude Auth",
                "토큰이 곧 만료됩니다 (만료: $expiryStr)",
                detail = "필요하면 'docker exec -it vibe-coder claude login' 으로 재발급하세요.",
            )
            else -> CheckItemDto(
                CheckStatus.OK, "Claude Auth",
                "로그인됨 (만료: $expiryStr)",
                detail = credentials.toString(),
            )
        }
    }

    /**
     * `.credentials.json` 안의 `claudeAiOauth.expiresAt` (epoch ms) 추출.
     * 파일 없음 / 형식 변경 등 어떤 실패에도 null 반환 — 호출자가 처리.
     */
    private fun readOauthExpiresAt(file: Path): Long? = try {
        val text = Files.readString(file, Charsets.UTF_8)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val oauth = root["claudeAiOauth"]?.jsonObject ?: return null
        oauth["expiresAt"]?.jsonPrimitive?.longOrNull
    } catch (_: Throwable) {
        null
    }

    private fun formatInstant(epochMs: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    /**
     * `CLAUDE_CONFIG_DIR` env 우선, 없으면 OS 별 기본 (`~/.claude`).
     * 도커 컨테이너에선 entrypoint 가 `CLAUDE_CONFIG_DIR=/home/vibe/.claude` 를 export.
     */
    private fun claudeConfigDir(): Path {
        val explicit = System.getenv("CLAUDE_CONFIG_DIR")?.trim()
        if (!explicit.isNullOrBlank()) return Path.of(explicit)
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: System.getenv("USERPROFILE")
            ?: "."
        return Path.of(home).resolve(".claude")
    }

    private fun buildClaudeAuthHelp(cfg: Path): String = buildString {
        appendLine("자격증명 파일이 없습니다: $cfg/.credentials.json (또는 config.json)")
        appendLine()
        appendLine("도커 환경:")
        appendLine("  docker exec -it vibe-coder claude login")
        appendLine()
        appendLine("호스트 환경:")
        appendLine("  claude login")
        appendLine()
        append("로그인 완료 후 이 페이지를 새로고침하세요.")
    }

    private fun checkWorkspace(): CheckItemDto {
        val root = Path.of(config.workspace.root)
        return try {
            if (!root.exists()) Files.createDirectories(root)
            val probe = Files.createTempFile(root, "probe-", ".tmp")
            Files.deleteIfExists(probe)
            CheckItemDto(CheckStatus.OK, "Workspace", "read/write OK", detail = root.toAbsolutePath().toString())
        } catch (e: Throwable) {
            CheckItemDto(CheckStatus.ERROR, "Workspace", "cannot write to ${root.toAbsolutePath()}", detail = e.message)
        }
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }

    private data class Captured(val exitCode: Int, val combined: String)

    private fun runtimeCommand(cmd: List<String>): Captured =
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) {
                // give destroyForcibly a moment to land so exitValue() doesn't
                // throw IllegalThreadStateException on a still-alive process.
                p.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            val exit = runCatching { p.exitValue() }.getOrDefault(-1)
            Captured(exit, out)
        } catch (e: Throwable) {
            Captured(-1, e.message ?: e.javaClass.simpleName)
        }
}
