package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val log = KotlinLogging.logger {}

/**
 * v0.35.0 — 워크스페이스 코드 통계.
 *
 * 외부 도구 (cloc, scc, tokei) 의존성 없이 in-process walk + 확장자 기반
 * 언어 분류 + 단순 LoC 카운트 (빈 줄 / 주석 구분 없음). cloc 수준의 정확도는
 * 아니지만 워크스페이스 규모 파악과 언어 mix 확인엔 충분.
 *
 * 제외 패턴:
 *   - .git / build / .gradle / node_modules / .idea
 *   - 바이너리 (확장자 기반)
 */
class CodeStatsService(private val workspace: WorkspacePath) {

    data class LanguageStat(
        val language: String,
        val files: Int,
        val lines: Long,
        val bytes: Long,
    )

    data class Result(
        val projectId: String,
        val totalFiles: Int,
        val totalLines: Long,
        val totalBytes: Long,
        val durationMs: Long,
        val byLanguage: List<LanguageStat>,
        val errorMessage: String?,
    )

    fun analyze(projectId: String): Result {
        val started = System.currentTimeMillis()
        val root = workspace.projectRoot(projectId)
        if (!root.isDirectory()) {
            return Result(projectId, 0, 0, 0, 0, emptyList(), "project root not found: $root")
        }
        val perLang = mutableMapOf<String, LanguageStat>()
        var totalFiles = 0
        var totalLines = 0L
        var totalBytes = 0L

        try {
            Files.walk(root).use { stream ->
                stream.forEach { p ->
                    if (!p.isRegularFile()) return@forEach
                    val rel = root.relativize(p).toString().replace('\\', '/')
                    if (shouldExclude(rel)) return@forEach
                    val lang = classify(p.fileName.toString())
                    val bytes = runCatching { Files.size(p) }.getOrDefault(0L)
                    if (bytes > MAX_FILE_BYTES) return@forEach   // skip giants (e.g. data dumps)
                    val lines = runCatching {
                        Files.lines(p).use { it.count() }
                    }.getOrDefault(0L)
                    totalFiles++
                    totalLines += lines
                    totalBytes += bytes
                    val prev = perLang[lang]
                    perLang[lang] = if (prev == null) {
                        LanguageStat(lang, 1, lines, bytes)
                    } else {
                        LanguageStat(lang, prev.files + 1, prev.lines + lines, prev.bytes + bytes)
                    }
                }
            }
        } catch (e: Throwable) {
            log.warn(e) { "code stats walk failed: $projectId" }
            return Result(projectId, totalFiles, totalLines, totalBytes,
                System.currentTimeMillis() - started, perLang.values.sortedByDescending { it.lines },
                e.message)
        }
        return Result(
            projectId = projectId,
            totalFiles = totalFiles,
            totalLines = totalLines,
            totalBytes = totalBytes,
            durationMs = System.currentTimeMillis() - started,
            byLanguage = perLang.values.sortedByDescending { it.lines },
            errorMessage = null,
        )
    }

    private fun shouldExclude(rel: String): Boolean {
        val top = rel.substringBefore('/')
        if (top in EXCLUDED_TOP_DIRS) return true
        if (rel.contains("/build/") || rel.contains("/.gradle/") ||
            rel.contains("/node_modules/") || rel.contains("/.idea/")) return true
        val base = rel.substringAfterLast('/')
        if (base in EXCLUDED_BASENAMES) return true
        if (base.startsWith(".")) return true
        // 바이너리 / 거대 자원
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext in BINARY_EXTENSIONS) return true
        return false
    }

    private fun classify(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return EXTENSION_TO_LANG[ext] ?: when {
            name == "Dockerfile" || name.startsWith("Dockerfile.") -> "Dockerfile"
            name == "Makefile" -> "Makefile"
            ext.isBlank() -> "Other"
            else -> "Other"
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024  // 5 MB per file

        private val EXCLUDED_TOP_DIRS = setOf(".git", "build", ".gradle", "node_modules", ".idea", ".vibecoder")
        private val EXCLUDED_BASENAMES = setOf(".DS_Store", "Thumbs.db")
        private val BINARY_EXTENSIONS = setOf(
            "apk", "aab", "jar", "war", "ear", "zip", "tar", "gz", "tgz", "bz2", "xz", "7z",
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tiff", "svg",
            "mp3", "wav", "ogg", "mp4", "webm", "mov", "avi",
            "ttf", "otf", "woff", "woff2", "eot",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "class", "so", "dll", "dylib", "exe", "bin",
            "pyc", "pyo", "pyd",
        )

        private val EXTENSION_TO_LANG = mapOf(
            "kt" to "Kotlin", "kts" to "Kotlin",
            "java" to "Java",
            "scala" to "Scala", "sc" to "Scala",
            "groovy" to "Groovy", "gradle" to "Groovy",
            "swift" to "Swift",
            "m" to "Objective-C", "mm" to "Objective-C++", "h" to "C/C++ Header",
            "c" to "C", "cc" to "C++", "cpp" to "C++", "cxx" to "C++", "hpp" to "C++ Header",
            "go" to "Go",
            "rs" to "Rust",
            "py" to "Python",
            "rb" to "Ruby",
            "js" to "JavaScript", "jsx" to "JavaScript", "mjs" to "JavaScript",
            "ts" to "TypeScript", "tsx" to "TypeScript",
            "vue" to "Vue", "svelte" to "Svelte",
            "html" to "HTML", "htm" to "HTML",
            "css" to "CSS", "scss" to "Sass", "sass" to "Sass", "less" to "Less",
            "xml" to "XML", "xsd" to "XML", "xsl" to "XML", "xslt" to "XML",
            "json" to "JSON", "json5" to "JSON",
            "yaml" to "YAML", "yml" to "YAML",
            "toml" to "TOML",
            "md" to "Markdown", "mdx" to "Markdown", "markdown" to "Markdown",
            "sh" to "Shell", "bash" to "Shell", "zsh" to "Shell", "fish" to "Shell",
            "ps1" to "PowerShell",
            "properties" to "Properties",
            "sql" to "SQL",
            "graphql" to "GraphQL", "gql" to "GraphQL",
            "proto" to "Protobuf",
            "lua" to "Lua",
            "dart" to "Dart",
            "tf" to "Terraform",
        )
    }
}
