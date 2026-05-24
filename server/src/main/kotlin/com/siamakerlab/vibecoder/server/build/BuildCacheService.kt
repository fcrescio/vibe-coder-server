package com.siamakerlab.vibecoder.server.build

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isDirectory

private val log = KotlinLogging.logger {}

/**
 * v0.28.0 — Gradle / Android SDK 빌드 캐시 관리.
 *
 * 컨테이너 안에서 시간이 지나면 가장 큰 디스크 소비자가 `~/.gradle/caches/` +
 * `~/.android/cache/`. 본 서비스는 디렉토리 크기 산출 + 안전한 selective cleanup.
 *
 * 안전 정책:
 *   - cleanup() 은 화이트리스트 상대 경로만 (~/.gradle 자체는 보존, caches 만).
 *   - 같은 vibe 사용자가 컨테이너 안에서 owning 한 파일만 (Files.delete 가 권한
 *     없으면 graceful skip).
 *   - 진행 중인 빌드와의 race 는 호출자 책임 — UI 는 빌드 0개일 때만 버튼 enable.
 *
 * 단일 admin 가정이라 잠금 없이 간단 구현. 같은 cleanup 두 번 동시 호출돼도
 * Files.walk 가 알아서 race-tolerant.
 */
class BuildCacheService(
    /** 일반적으로 /home/vibe. 컨테이너 안 사용자 홈. */
    private val userHome: Path = Path.of(System.getProperty("user.home")),
) {

    data class CacheSize(
        val gradleCachesBytes: Long,
        val gradleDaemonBytes: Long,
        val androidCacheBytes: Long,
        val npmCacheBytes: Long,
        val totalBytes: Long,
    )

    fun measure(): CacheSize {
        val gradleCaches = userHome.resolve(".gradle/caches")
        val gradleDaemon = userHome.resolve(".gradle/daemon")
        val androidCache = userHome.resolve(".android/cache")
        val npmCache = userHome.resolve(".npm/_cacache")
        val g = directorySize(gradleCaches)
        val d = directorySize(gradleDaemon)
        val a = directorySize(androidCache)
        val n = directorySize(npmCache)
        return CacheSize(g, d, a, n, g + d + a + n)
    }

    enum class Target { GRADLE_CACHES, GRADLE_DAEMON, ANDROID_CACHE, NPM_CACHE }

    data class CleanupResult(
        val target: Target,
        val deletedFiles: Long,
        val freedBytes: Long,
        val errorMessage: String?,
    )

    fun cleanup(target: Target): CleanupResult {
        val path = when (target) {
            Target.GRADLE_CACHES -> userHome.resolve(".gradle/caches")
            Target.GRADLE_DAEMON -> userHome.resolve(".gradle/daemon")
            Target.ANDROID_CACHE -> userHome.resolve(".android/cache")
            Target.NPM_CACHE -> userHome.resolve(".npm/_cacache")
        }
        if (!path.isDirectory()) {
            return CleanupResult(target, 0, 0, "디렉토리가 존재하지 않음: $path")
        }
        return try {
            val deletedFiles = AtomicLong(0)
            val freedBytes = AtomicLong(0)
            // bottom-up walk — 디렉토리 자체는 보존, 내용물만 삭제.
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    if (p == path) return@forEach
                    val size = runCatching { Files.size(p) }.getOrDefault(0L)
                    val ok = runCatching { Files.delete(p) }.isSuccess
                    if (ok) {
                        deletedFiles.incrementAndGet()
                        freedBytes.addAndGet(size)
                    }
                }
            }
            CleanupResult(target, deletedFiles.get(), freedBytes.get(), null)
        } catch (e: Throwable) {
            log.warn(e) { "cleanup $target failed" }
            CleanupResult(target, 0, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun directorySize(path: Path): Long {
        if (!path.isDirectory()) return 0L
        return try {
            Files.walk(path).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                    .sum()
            }
        } catch (e: Throwable) {
            log.debug(e) { "directorySize $path failed" }
            0L
        }
    }
}
