package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.9.0 — Git global identity (`user.name` / `user.email`) 관리.
 *
 * 첫 설치 시 사용자가 빌드환경 페이지 ("Git Identity" 카드) 또는 온보딩 배너를
 * 통해 입력. 영속 위치는 [Dockerfile] 의 `GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config`
 * 환경변수가 결정 — 컨테이너 안의 git CLI (clone / commit / log / push) 가 자식
 * 프로세스 어디서든 동일 파일을 global config 로 인식한다.
 *
 * 보안: name / email 은 평문이라 평소 의미의 비밀이 아니지만, 본 서버는 단일
 * 사용자 LAN 도구라 admin 인증으로 보호하면 충분.
 *
 * 정책: 자동 추측 / default 주입 금지. 사용자가 입력하지 않으면 unset 으로 두고
 * 빌드/콘솔 화면에 "Git identity 미설정" 배너를 띄운다 (잘못된 author 로 commit
 * 되는 회귀를 막기 위함).
 */
class GitConfigService(
    /** `git` 바이너리. 보통 PATH 에 있어 "git" 그대로. 테스트 시 절대경로 override 가능. */
    private val gitBinary: String = "git",
) {

    /**
     * 현재 등록된 global identity 를 읽어온다. 미설정 (key 없음) 시 해당 필드 null.
     * 자식 프로세스의 환경변수가 부모와 동일하므로 `GIT_CONFIG_GLOBAL` 영속 path
     * 가 자동 적용.
     */
    fun get(): GitIdentity {
        val name = readKey("user.name")
        val email = readKey("user.email")
        return GitIdentity(name = name, email = email)
    }

    /** 둘 다 비어있지 않으면 true. 온보딩 배너 표시 여부 판정에 사용. */
    fun isConfigured(): Boolean {
        val id = get()
        return !id.name.isNullOrBlank() && !id.email.isNullOrBlank()
    }

    /**
     * Set name + email. 둘 중 하나라도 blank 면 [ApiException] (호출자가 form
     * validation 으로 1차 차단하지만 서버 측 방어).
     *
     * Email 은 단순 정규식 (RFC 5322 의 ASCII 부분집합) 으로만 검증 — 사용자가
     * 잘못된 이메일로 commit 해서 GitHub 가 author 매칭 못 하는 회귀를 막기 위함.
     */
    fun set(name: String, email: String) {
        val trimmedName = name.trim()
        val trimmedEmail = email.trim()
        if (trimmedName.isBlank()) throw ApiException.localized(400, "name_required",
            messageKey = "api.gitConfig.nameRequired")
        if (trimmedEmail.isBlank()) throw ApiException.localized(400, "email_required",
            messageKey = "api.gitConfig.emailRequired")
        if (!EMAIL_REGEX.matches(trimmedEmail)) throw ApiException.localized(400, "invalid_email",
            messageKey = "api.gitConfig.invalidEmail", args = listOf(trimmedEmail))
        // 길이 제한 — 비정상적 long 입력 차단. github 의 username 화면 표시 길이도 39 정도.
        if (trimmedName.length > 100) throw ApiException.localized(400, "name_too_long",
            messageKey = "api.gitConfig.nameTooLong")
        if (trimmedEmail.length > 200) throw ApiException.localized(400, "email_too_long",
            messageKey = "api.gitConfig.emailTooLong")
        runGit("config", "--global", "user.name", trimmedName)
        runGit("config", "--global", "user.email", trimmedEmail)
        log.info { "git global identity updated: name=$trimmedName email=$trimmedEmail" }
    }

    /** 명시적 해제 — admin 이 잘못 입력 후 초기화 원할 때. */
    fun clear() {
        // `--unset` 은 key 가 없으면 exit 5. graceful 처리.
        runCatching { runGit("config", "--global", "--unset", "user.name") }
        runCatching { runGit("config", "--global", "--unset", "user.email") }
        log.info { "git global identity cleared" }
    }

    private fun readKey(key: String): String? {
        return try {
            val out = runGitCapture("config", "--global", "--get", key)
            out?.trim()?.ifBlank { null }
        } catch (e: Throwable) {
            log.debug(e) { "git config read failed for $key" }
            null
        }
    }

    private fun runGit(vararg args: String) {
        val cmd = listOf(gitBinary) + args.toList()
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        if (!proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw ApiException.localized(500, "git_timeout", messageKey = "api.gitConfig.gitTimeout")
        }
        val exit = proc.exitValue()
        if (exit != 0) {
            val output = proc.inputStream.bufferedReader().readText().take(2_000)
            throw ApiException.localized(500, "git_failed",
                messageKey = "api.gitConfig.gitFailed", args = listOf(exit, output))
        }
    }

    /** Returns stdout text or null on non-zero exit (key not present). */
    private fun runGitCapture(vararg args: String): String? {
        val cmd = listOf(gitBinary) + args.toList()
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        if (!proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null
        return proc.inputStream.bufferedReader().readText()
    }

    companion object {
        const val GIT_TIMEOUT_SECONDS = 5L
        // RFC 5322 의 보수적 subset — 일상적인 user@host.tld 만 통과.
        private val EMAIL_REGEX = Regex("""^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""")
    }
}

/**
 * v1.9.0 — JSON wire shape 그대로 사용. [name] / [email] 미설정 시 null.
 */
data class GitIdentity(
    val name: String?,
    val email: String?,
) {
    val isConfigured: Boolean get() = !name.isNullOrBlank() && !email.isNullOrBlank()
}
