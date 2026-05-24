package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/**
 * v0.35.0 — 워크스페이스 source 트리 grep.
 *
 * 외부 ripgrep 의존성 없이 in-process line-by-line scan. 단순하지만 보통의
 * Android 프로젝트 (수천 파일) 에선 충분 (수 초).
 *
 * 안전:
 *   - workspace 외부 경로 불가 (WorkspacePath 안에서만).
 *   - 빈 쿼리 = 빈 결과 (대량 dump 방지).
 *   - 200 매치 hard cap.
 *   - 큰 파일 (5 MB+) skip.
 *   - 바이너리 확장자 skip.
 */
class CodeSearchService(private val workspace: WorkspacePath) {

    data class Match(
        val projectId: String,
        val relPath: String,
        val lineNumber: Int,
        val line: String,
    )

    fun search(
        q: String,
        projectFilter: String? = null,
        caseSensitive: Boolean = false,
    ): List<Match> {
        if (q.isBlank()) return emptyList()
        val needle = if (caseSensitive) q else q.lowercase()
        val results = mutableListOf<Match>()

        val workspaceRoot = workspace.root
        if (!workspaceRoot.isDirectory()) return emptyList()

        // 1) 워크스페이스 root 의 1-depth 가 프로젝트 dir.
        Files.list(workspaceRoot).use { topStream ->
            topStream.toList().forEach { projectDir ->
                if (results.size >= MAX_MATCHES) return@forEach
                if (!projectDir.isDirectory()) return@forEach
                val pid = projectDir.name
                if (pid.startsWith(".")) return@forEach  // .vibecoder 등 제외
                if (projectFilter != null && pid != projectFilter) return@forEach
                grepProject(projectDir, pid, needle, caseSensitive, results)
            }
        }
        return results.take(MAX_MATCHES)
    }

    private fun grepProject(
        projectDir: Path,
        pid: String,
        needle: String,
        caseSensitive: Boolean,
        out: MutableList<Match>,
    ) {
        runCatching {
            Files.walk(projectDir).use { stream ->
                stream.forEach { p ->
                    if (out.size >= MAX_MATCHES) return@forEach
                    if (!p.isRegularFile()) return@forEach
                    val rel = projectDir.relativize(p).toString().replace('\\', '/')
                    if (shouldExclude(rel)) return@forEach
                    val size = runCatching { Files.size(p) }.getOrDefault(0L)
                    if (size > MAX_FILE_BYTES) return@forEach
                    grepFile(p, pid, rel, needle, caseSensitive, out)
                }
            }
        }.onFailure { log.debug(it) { "grep walk failed: $projectDir" } }
    }

    private fun grepFile(
        path: Path,
        pid: String,
        rel: String,
        needle: String,
        caseSensitive: Boolean,
        out: MutableList<Match>,
    ) {
        runCatching {
            Files.newBufferedReader(path).use { reader ->
                var lineNo = 0
                while (out.size < MAX_MATCHES) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    val match = if (caseSensitive) line.contains(needle)
                    else line.lowercase().contains(needle)
                    if (match) {
                        out += Match(pid, rel, lineNo, line.take(400))
                    }
                }
            }
        }.onFailure { log.debug(it) { "grep file failed: $rel" } }
    }

    private fun shouldExclude(rel: String): Boolean {
        val top = rel.substringBefore('/')
        if (top in EXCLUDED_TOP_DIRS) return true
        if (rel.contains("/build/") || rel.contains("/.gradle/") ||
            rel.contains("/node_modules/") || rel.contains("/.idea/")) return true
        val base = rel.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_EXTENSIONS) return true
        return false
    }

    companion object {
        private const val MAX_MATCHES = 200
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024
        private val EXCLUDED_TOP_DIRS = setOf(".git", "build", ".gradle", "node_modules", ".idea")
        private val BINARY_EXTENSIONS = setOf(
            "apk", "aab", "jar", "war", "zip", "tar", "gz", "tgz", "7z",
            "png", "jpg", "jpeg", "gif", "webp", "ico", "svg",
            "mp3", "wav", "mp4", "webm", "mov", "ttf", "otf", "woff", "woff2",
            "pdf", "class", "so", "dll", "dylib", "exe", "bin",
        )
    }
}
