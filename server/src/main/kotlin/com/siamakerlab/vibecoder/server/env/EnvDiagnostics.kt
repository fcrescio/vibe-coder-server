package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.shared.dto.CheckItemDto
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto
import java.nio.file.Files
import java.nio.file.Path
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
     * Claude CLI 로그인 상태 — `~/.claude/.credentials.json` (또는
     * `CLAUDE_CONFIG_DIR` env 가 가리키는 곳) 존재 여부.
     *
     * v0.5.4 까지는 vibe-doctor 와 같이 `.credentials.json` 또는 `config.json`
     * 중 하나만 있어도 OK 로 판정했으나, 그건 false positive 였다. claude CLI
     * 는 첫 실행 시 빈 `config.json` 을 항상 만들어두기 때문에 "config.json
     * 존재 = 로그인됨" 이 아니다. 실제 인증 토큰은 `.credentials.json` 에만
     * 저장되므로 그 파일만 본다.
     *
     * - CLI 미설치 → ERROR.
     * - `.credentials.json` 있음 → OK.
     * - 없음 → ERROR + `claude login` 가이드.
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
        return if (credentials.exists()) {
            CheckItemDto(
                CheckStatus.OK, "Claude Auth", "로그인됨",
                detail = credentials.toString(),
            )
        } else {
            CheckItemDto(
                CheckStatus.ERROR, "Claude Auth",
                "Claude CLI 로그인이 필요합니다.",
                detail = buildClaudeAuthHelp(cfg),
            )
        }
    }

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
