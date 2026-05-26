package com.siamakerlab.vibecoder.server.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.2.0 — vibe 사용자의 SSH 키 (~/.ssh/id_ed25519) 관리.
 *
 *  - 첫 부팅 시 [docker/entrypoint.sh] 가 자동 생성 (외부 영속 볼륨).
 *  - 본 서비스는 **읽기 + 재생성** 만 담당. 생성 자체는 entrypoint 가 컨테이너
 *    레벨에서 처리 (Java process 가 ssh-keygen 호출하면 권한 / umask 이슈 발생).
 *  - 재생성: 기존 키쌍을 `id_ed25519.bak.<yyyymmdd-HHmmss>` 로 백업 후 신규 생성.
 *
 * 운영자 단일 사용자 도구 — 멀티 사용자 / RBAC 불필요.
 *
 * @param sshDir 기본 `/home/vibe/.ssh` (Docker), 로컬 dev 에선 사용자 home/.ssh.
 */
class SshKeyService(
    private val sshDir: Path = Path.of(System.getProperty("user.home"), ".ssh"),
) {

    /**
     * 현재 SSH 공개 키 스냅샷. 키가 없으면 null (entrypoint 미실행 / 로컬 dev 환경).
     */
    fun snapshot(): SshKeySnapshot? {
        val pub = sshDir.resolve("id_ed25519.pub")
        if (!Files.exists(pub)) return null
        val content = runCatching { Files.readString(pub).trim() }.getOrNull() ?: return null
        if (content.isBlank()) return null
        val (alg, key, comment) = parsePublicKey(content)
        val createdAt = runCatching { Files.getLastModifiedTime(pub).toInstant() }.getOrNull()
        val fingerprint = computeFingerprint(pub)
        return SshKeySnapshot(
            publicKey = content,
            algorithm = alg,
            comment = comment,
            fingerprint = fingerprint,
            createdAt = createdAt?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
        )
    }

    /**
     * 키 재생성. 기존 키쌍을 `.bak.<ts>` 로 백업 → ssh-keygen 으로 새 ED25519 키 생성.
     * 실패 시 IllegalStateException — 호출처가 사용자에게 친화 메시지 표시.
     */
    fun regenerate(): SshKeySnapshot {
        if (!Files.exists(sshDir)) {
            Files.createDirectories(sshDir)
            runCatching { setPerm(sshDir, "rwx------") }
        }
        val priv = sshDir.resolve("id_ed25519")
        val pub = sshDir.resolve("id_ed25519.pub")
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(Instant.now())
        if (Files.exists(priv)) {
            Files.move(priv, sshDir.resolve("id_ed25519.bak.$ts"))
        }
        if (Files.exists(pub)) {
            Files.move(pub, sshDir.resolve("id_ed25519.pub.bak.$ts"))
        }

        val hostname = runCatching {
            ProcessBuilder("hostname").redirectErrorStream(true).start().let { p ->
                p.inputStream.bufferedReader().readText().trim().ifBlank { "vibe-coder" }
            }
        }.getOrDefault("vibe-coder")
        val comment = "vibe-coder-server@$hostname-$ts"
        val proc = ProcessBuilder(
            "ssh-keygen", "-t", "ed25519",
            "-f", priv.toString(),
            "-N", "",
            "-C", comment,
        ).redirectErrorStream(true).start()
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("ssh-keygen timed out")
        }
        if (proc.exitValue() != 0) {
            val out = proc.inputStream.bufferedReader().readText()
            throw IllegalStateException("ssh-keygen failed (exit=${proc.exitValue()}): $out")
        }
        runCatching { setPerm(priv, "rw-------") }
        runCatching { setPerm(pub, "rw-r--r--") }
        log.info { "SSH key regenerated; backup at id_ed25519.bak.$ts" }
        return snapshot() ?: throw IllegalStateException("Key created but cannot be read back")
    }

    private fun setPerm(path: Path, mode: String) {
        val perms: Set<PosixFilePermission> = PosixFilePermissions.fromString(mode)
        Files.setPosixFilePermissions(path, perms)
    }

    /** `ssh-keygen -lf <pub>` 결과의 첫 토큰이 fingerprint (SHA256:base64). */
    private fun computeFingerprint(pub: Path): String? {
        val proc = runCatching {
            ProcessBuilder("ssh-keygen", "-lf", pub.toString())
                .redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val finished = proc.waitFor(5, TimeUnit.SECONDS)
        if (!finished || proc.exitValue() != 0) return null
        val line = proc.inputStream.bufferedReader().readText().trim()
        // 형식: "256 SHA256:xxxx... comment (ED25519)"
        val parts = line.split(" ")
        return parts.getOrNull(1)
    }

    /** "ssh-ed25519 AAA... comment" → (alg, key, comment). */
    private fun parsePublicKey(content: String): Triple<String, String, String?> {
        val parts = content.split(" ", limit = 3)
        val alg = parts.getOrNull(0) ?: ""
        val key = parts.getOrNull(1) ?: ""
        val comment = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        return Triple(alg, key, comment)
    }
}

data class SshKeySnapshot(
    val publicKey: String,
    val algorithm: String,
    val comment: String?,
    val fingerprint: String?,
    val createdAt: String?,
)
