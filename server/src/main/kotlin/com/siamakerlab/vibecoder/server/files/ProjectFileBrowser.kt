package com.siamakerlab.vibecoder.server.files

import com.siamakerlab.vibecoder.server.core.PathSafety
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val log = KotlinLogging.logger {}

/**
 * 프로젝트 소스 트리의 read-only 디렉토리 listing + 가벼운 텍스트 파일 view/edit.
 * v0.13.0.
 *
 * 보안:
 *  - 모든 path 는 [PathSafety.normalizeAndCheck] 로 traversal 차단.
 *  - 심볼릭 링크는 따라가지 않고 [LinkOption.NOFOLLOW_LINKS] — 외부 escape 방지.
 *  - 텍스트 파일만 view/edit. 이진/큰 파일은 차단.
 *
 * NOTE: 신택스 하이라이트는 후속 사이클. 본 cycle 은 plain `<pre>` + `<textarea>`.
 */
class ProjectFileBrowser(
    private val workspace: WorkspacePath,
) {

    data class Entry(
        val name: String,
        val relPath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedAt: String,
    )

    data class FileView(
        val relPath: String,
        val sizeBytes: Long,
        val content: String,
        val truncated: Boolean,
        val mimeGuess: String,
    )

    /**
     * 프로젝트 폴더 내부의 디렉토리 listing.
     * @param subPath 프로젝트 root 기준 상대 경로 (빈 문자열이면 root).
     */
    fun list(projectId: String, subPath: String): List<Entry> {
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) {
            throw ApiException.localized(404, "project_root_not_found", messageKey = "api.fileBrowser.projectRootNotFound")
        }
        val target = if (subPath.isBlank()) projectRoot else PathSafety.normalizeAndCheck(projectRoot, subPath)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.fileBrowser.pathNotFound", args = listOf(subPath))
        }
        if (!target.isDirectory()) {
            throw ApiException.localized(400, "not_a_directory", messageKey = "api.fileBrowser.notADirectory", args = listOf(subPath))
        }
        return Files.list(target).use { stream ->
            stream
                .filter { skipHidden(it.fileName.toString()).not() }
                .map { p ->
                    val attrs = runCatching {
                        Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    }.getOrNull()
                    Entry(
                        name = p.fileName.toString(),
                        relPath = projectRoot.relativize(p).toString().replace('\\', '/'),
                        isDirectory = attrs?.isDirectory ?: false,
                        sizeBytes = attrs?.size() ?: 0L,
                        modifiedAt = attrs?.lastModifiedTime()?.toString() ?: "-",
                    )
                }
                .toList()
                .sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    /**
     * 텍스트 파일 read. 너무 큰 파일 / 이진 파일은 차단.
     */
    fun read(projectId: String, relPath: String): FileView {
        val projectRoot = workspace.projectRoot(projectId)
        if (relPath.isBlank()) {
            throw ApiException.localized(400, "empty_path", messageKey = "api.fileBrowser.emptyPath")
        }
        val target = PathSafety.normalizeAndCheck(projectRoot, relPath)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "file_not_found", messageKey = "api.fileBrowser.fileNotFound", args = listOf(relPath))
        }
        if (Files.isSymbolicLink(target)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedView")
        }
        if (!target.isRegularFile()) {
            throw ApiException.localized(400, "not_a_file", messageKey = "api.fileBrowser.notAFile")
        }
        val size = Files.size(target)
        if (size > MAX_VIEW_BYTES) {
            throw ApiException.localized(413, "file_too_large",
                messageKey = "api.fileBrowser.fileTooLarge", args = listOf(size, MAX_VIEW_BYTES))
        }
        val bytes = Files.readAllBytes(target)
        if (looksBinary(bytes)) {
            throw ApiException.localized(415, "binary_file", messageKey = "api.fileBrowser.binaryFile")
        }
        val content = String(bytes, Charsets.UTF_8)
        val mime = guessMime(relPath)
        return FileView(relPath, size, content, truncated = false, mimeGuess = mime)
    }

    /**
     * 텍스트 파일 write. 상위 디렉토리는 반드시 사전 존재해야 함 (UI 에서 신규 파일 생성은
     * 별도 endpoint 로 분리 — 본 cycle 미구현).
     */
    fun write(projectId: String, relPath: String, content: String) {
        val projectRoot = workspace.projectRoot(projectId)
        if (relPath.isBlank()) {
            throw ApiException.localized(400, "empty_path", messageKey = "api.fileBrowser.emptyPath")
        }
        if (content.length > MAX_VIEW_BYTES) {
            throw ApiException.localized(413, "content_too_large",
                messageKey = "api.fileBrowser.contentTooLarge", args = listOf(content.length))
        }
        val target = PathSafety.normalizeAndCheck(projectRoot, relPath)
        if (Files.isSymbolicLink(target)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedEdit")
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !target.isRegularFile()) {
            throw ApiException.localized(400, "not_a_file", messageKey = "api.fileBrowser.notAFile")
        }
        // 상위 디렉토리는 존재해야 함 (신규 디렉토리 생성은 본 endpoint 가 안 함)
        val parent = target.parent ?: throw ApiException.localized(400, "bad_path", messageKey = "api.fileBrowser.badPath")
        if (!Files.exists(parent)) {
            throw ApiException.localized(400, "parent_missing", messageKey = "api.fileBrowser.parentMissing", args = listOf(parent.toString()))
        }
        // atomic-ish write: tmp → move
        val tmp = target.resolveSibling("${target.fileName}.editing.tmp")
        Files.writeString(tmp, content, Charsets.UTF_8)
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        log.info { "file edited: $projectId :: $relPath (${content.length} chars)" }
    }

    private fun skipHidden(name: String): Boolean =
        // .vibecoder 등 서버 내부 메타데이터, .git 등 도구 메타데이터는 listing 노출 안 함.
        name == ".vibecoder" || name == ".gradle" || name == "build" || name == "node_modules"

    private fun looksBinary(bytes: ByteArray): Boolean {
        // 처음 4KB 안에 NUL(0x00) 이 있으면 이진으로 간주.
        val sample = if (bytes.size <= 4096) bytes else bytes.copyOf(4096)
        return sample.any { it.toInt() == 0 }
    }

    private fun guessMime(path: String): String = when {
        path.endsWith(".kt", true) -> "text/x-kotlin"
        path.endsWith(".kts", true) -> "text/x-kotlin"
        path.endsWith(".java", true) -> "text/x-java"
        path.endsWith(".xml", true) -> "text/xml"
        path.endsWith(".json", true) -> "application/json"
        path.endsWith(".yml", true) || path.endsWith(".yaml", true) -> "text/yaml"
        path.endsWith(".md", true) -> "text/markdown"
        path.endsWith(".gradle", true) -> "text/x-gradle"
        path.endsWith(".properties", true) -> "text/x-properties"
        path.endsWith(".sh", true) -> "text/x-shellscript"
        else -> "text/plain"
    }

    companion object {
        /** UI 에서 열거 가능한 최대 텍스트 파일 크기 — 1 MB. */
        const val MAX_VIEW_BYTES = 1024L * 1024
    }
}
