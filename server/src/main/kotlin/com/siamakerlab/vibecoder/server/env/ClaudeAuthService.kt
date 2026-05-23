package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * Claude CLI 인증 자격증명 — 웹 UI 에서 관리.
 *
 * v0.7.0 — 컨테이너 터미널 접근이 불가능한 환경(원격 호스팅 / 모바일 운영 등)을
 * 위한 두 가지 우회 경로를 제공한다. raw-shell UI (CLAUDE.md §3) 는 거치지 않는다.
 *
 *   B) [uploadCredentials] — 다른 머신에서 `claude login` 후 받은 `.credentials.json`
 *      을 그대로 업로드. 100% 동작 보장 (CLI 의 OAuth 흐름 자체를 우회).
 *   C) [registerApiKey]    — OAuth 대신 `ANTHROPIC_API_KEY` 환경변수 모드.
 *      [ClaudeProcessEnv.applyApiKey] 가 모든 claude 자식 프로세스 spawn 시
 *      이 키를 환경변수로 주입한다.
 *
 * 반자동 웹 OAuth (옵션 A) 는 v0.7.1 에서 ClaudeLoginService 로 별도 추가 예정.
 */
class ClaudeAuthService(
    private val clock: Clock,
) {

    fun configDir(): Path = claudeConfigDir()

    fun credentialsPath(): Path = configDir().resolve(".credentials.json")

    /** API 키 모드를 위한 vibe 홈 내 시크릿 파일. entrypoint 의존 없이 서버가 자체 관리. */
    fun apiKeyPath(): Path = configDir().resolve(".env.api-key")

    /** API 키가 등록되어 있으면 그 값을 반환. OAuth 모드면 null. */
    fun loadApiKey(): String? = ClaudeProcessEnv.readApiKey(apiKeyPath())

    fun isApiKeyMode(): Boolean = apiKeyPath().exists()

    fun deleteApiKey() {
        Files.deleteIfExists(apiKeyPath())
    }

    /**
     * 사용자가 업로드한 `.credentials.json` 을 검증 후 vibe 홈에 배치.
     * 기존 파일은 `.credentials.json.bak.<ts>` 로 백업.
     * Posix 환경에선 권한을 0600 으로 좁힌다 (실패는 경고만).
     *
     * @throws ApiException 비정상 크기/형식/만료/IO 실패.
     */
    fun uploadCredentials(bytes: ByteArray): UploadResult {
        if (bytes.isEmpty()) {
            throw ApiException(400, "empty", "빈 파일입니다.")
        }
        if (bytes.size > 64 * 1024) {
            throw ApiException(
                413, "too_large",
                ".credentials.json 이 비정상적으로 큽니다 (${bytes.size} bytes). 올바른 파일인지 확인하세요.",
            )
        }
        val text = try {
            bytes.toString(Charsets.UTF_8)
        } catch (e: Throwable) {
            throw ApiException(400, "encoding", "UTF-8 디코딩 실패: ${e.message}")
        }

        val root: JsonObject = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw ApiException(400, "json_shape", "최상위가 JSON object 가 아닙니다.")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ApiException(400, "json_parse", "JSON 파싱 실패: ${e.message}")
        }
        val oauth = root["claudeAiOauth"] as? JsonObject
            ?: throw ApiException(
                400, "shape",
                "claudeAiOauth 필드가 없습니다 — 올바른 .credentials.json 인지 확인하세요.",
            )
        val expiresAt = (oauth["expiresAt"] as? JsonPrimitive)?.longOrNull
            ?: throw ApiException(
                400, "shape",
                "claudeAiOauth.expiresAt 가 없거나 정수가 아닙니다.",
            )
        val nowMs = System.currentTimeMillis()
        if (expiresAt <= nowMs) {
            throw ApiException(
                400, "expired",
                "토큰이 이미 만료되어 있습니다 (expiresAt=$expiresAt). 발급 머신에서 " +
                    "`claude login` 을 다시 실행한 뒤 새 .credentials.json 을 업로드하세요.",
            )
        }

        val cfg = configDir()
        try {
            Files.createDirectories(cfg)
        } catch (e: Throwable) {
            throw ApiException(
                500, "io",
                "Claude 설정 디렉토리 생성 실패: $cfg — ${e.message}",
            )
        }

        val target = credentialsPath()
        val backup: Path? = if (target.exists()) {
            val ts = clock.nowIso().replace(":", "").replace("-", "")
            val bk = cfg.resolve(".credentials.json.bak.$ts")
            try {
                Files.copy(target, bk, StandardCopyOption.REPLACE_EXISTING)
                bk
            } catch (e: Throwable) {
                log.warn { "기존 자격증명 백업 실패: ${e.message}" }
                null
            }
        } else {
            null
        }

        val tmp = cfg.resolve(".credentials.json.tmp")
        try {
            Files.writeString(
                tmp, text, Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw ApiException(500, "io", "자격증명 파일 쓰기 실패: ${e.message}")
        }
        tightenPermissions(target)

        log.info { "uploaded credentials → $target (expiresAt=$expiresAt, backup=$backup)" }
        return UploadResult(
            targetPath = target.toString(),
            backup = backup?.toString(),
            expiresAt = expiresAt,
        )
    }

    /**
     * Anthropic API 키를 등록. 형식은 헐겁게 검증 (`sk-` 접두 필수).
     * 저장 후엔 모든 claude 자식 프로세스가 [ClaudeProcessEnv.applyApiKey] 로 자동 픽업.
     */
    fun registerApiKey(rawKey: String) {
        val k = rawKey.trim()
        if (k.isEmpty()) {
            throw ApiException(400, "empty", "API 키가 비어 있습니다.")
        }
        if (!k.startsWith("sk-")) {
            throw ApiException(
                400, "shape",
                "Anthropic API 키는 일반적으로 'sk-ant-...' 형식입니다. 다시 확인하세요.",
            )
        }
        if (k.length < 20 || k.length > 256) {
            throw ApiException(400, "shape", "API 키 길이가 비정상적입니다 (${k.length} 문자).")
        }

        val cfg = configDir()
        try {
            Files.createDirectories(cfg)
        } catch (e: Throwable) {
            throw ApiException(500, "io", "Claude 설정 디렉토리 생성 실패: $cfg — ${e.message}")
        }
        val path = apiKeyPath()
        Files.writeString(
            path, "ANTHROPIC_API_KEY=$k\n", Charsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        )
        tightenPermissions(path)
        log.info { "registered API key → $path (length=${k.length})" }
    }

    data class UploadResult(
        val targetPath: String,
        val backup: String?,
        val expiresAt: Long,
    )

    private fun tightenPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }.onFailure {
            log.debug { "Posix 권한 설정 skip ($path): ${it.message}" }
        }
    }

    private fun claudeConfigDir(): Path {
        val explicit = System.getenv("CLAUDE_CONFIG_DIR")?.trim()
        if (!explicit.isNullOrBlank()) return Path.of(explicit)
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: System.getenv("USERPROFILE")
            ?: "."
        return Path.of(home).resolve(".claude")
    }
}

/**
 * Claude 자식 프로세스 spawn 시 환경변수를 주입하는 helper.
 *
 * DI 없이 두 spawn 지점([com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager],
 * [com.siamakerlab.vibecoder.server.claude.ClaudeStatusService])에서 한 줄로 호출하기
 * 위해 object 로 분리. 키 파일이 없거나 비어 있으면 no-op.
 */
object ClaudeProcessEnv {

    /** ProcessBuilder.environment() 에 ANTHROPIC_API_KEY 를 주입. */
    fun applyApiKey(env: MutableMap<String, String>) {
        val keyFile = defaultApiKeyPath()
        val key = readApiKey(keyFile) ?: return
        env["ANTHROPIC_API_KEY"] = key
    }

    fun defaultApiKeyPath(): Path {
        val explicit = System.getenv("CLAUDE_CONFIG_DIR")?.trim()
        val base = if (!explicit.isNullOrBlank()) Path.of(explicit)
        else {
            val home = System.getProperty("user.home")
                ?: System.getenv("HOME")
                ?: System.getenv("USERPROFILE")
                ?: "."
            Path.of(home).resolve(".claude")
        }
        return base.resolve(".env.api-key")
    }

    fun readApiKey(path: Path): String? {
        if (!path.exists()) return null
        return runCatching {
            Files.readString(path, Charsets.UTF_8)
                .lineSequence()
                .firstOrNull { it.startsWith("ANTHROPIC_API_KEY=") }
                ?.removePrefix("ANTHROPIC_API_KEY=")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
