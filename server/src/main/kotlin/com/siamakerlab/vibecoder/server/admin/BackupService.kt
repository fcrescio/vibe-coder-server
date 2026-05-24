package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * v0.60.0 — Phase 39 backup operations (extracted from `BackupRoutes` for reuse by
 * [com.siamakerlab.vibecoder.server.admin.BackupScheduler]).
 *
 * Two responsibilities:
 *   1. [streamTarGz] — write a tar.gz of the workspace (with exclusions) to an arbitrary
 *      OutputStream. The on-demand `/backup/download` SSR route streams to the HTTP
 *      response; the scheduler streams to a file.
 *   2. [listAutoBackups], [deleteOldestOverRetention] — manage the scheduled-backup
 *      directory `<workspace>/.vibecoder/backups/`.
 */
class BackupService(
    private val workspace: WorkspacePath,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(zoneId)
    private val tsFileFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(zoneId)

    /** Stream a tar.gz of the workspace to [out]. Caller owns the stream lifecycle. */
    fun streamTarGz(out: OutputStream) {
        BufferedOutputStream(out).use { buf ->
            GZIPOutputStream(buf).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    walk(workspace.root, workspace.root, tar)
                }
            }
        }
    }

    /** Filename suggested for an HTTP download (manual button). */
    fun downloadFileName(now: Instant = Instant.now()): String =
        "vibe-workspace-${tsFmt.format(now)}.tar.gz"

    // region scheduled-backup directory management

    private fun backupsDir(): Path = workspace.root.resolve(".vibecoder/backups")

    /** Create one scheduled backup file. Returns the absolute path. */
    fun createScheduled(now: Instant = Instant.now()): Path {
        val dir = backupsDir().also { Files.createDirectories(it) }
        val file = dir.resolve("vibe-workspace-${tsFileFmt.format(now)}.tar.gz")
        Files.newOutputStream(file).use { streamTarGz(it) }
        log.info { "scheduled backup created: $file (${Files.size(file) / 1024} KB)" }
        return file
    }

    data class AutoBackupEntry(
        val fileName: String,
        val sizeBytes: Long,
        val createdAtMs: Long,
    )

    fun listAutoBackups(): List<AutoBackupEntry> {
        val dir = backupsDir()
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tar.gz") }
                .map { p ->
                    AutoBackupEntry(
                        fileName = p.fileName.toString(),
                        sizeBytes = runCatching { Files.size(p) }.getOrDefault(0L),
                        createdAtMs = runCatching {
                            Files.getLastModifiedTime(p).toMillis()
                        }.getOrDefault(0L),
                    )
                }
                .toList()
                .sortedByDescending { it.createdAtMs }
        }
    }

    /** Returns the number of files removed. */
    fun deleteOldestOverRetention(retain: Int): Int {
        val list = listAutoBackups()
        if (list.size <= retain) return 0
        val toRemove = list.drop(retain)
        var removed = 0
        for (e in toRemove) {
            val p = backupsDir().resolve(e.fileName)
            if (runCatching { Files.deleteIfExists(p) }.getOrDefault(false)) {
                removed++
            }
        }
        if (removed > 0) log.info { "auto-backup rotation: removed $removed file(s)" }
        return removed
    }

    fun deleteAutoBackup(fileName: String): Boolean {
        // Strict — only allow basenames inside the backups dir; no traversal.
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) return false
        val p = backupsDir().resolve(fileName)
        if (!p.toAbsolutePath().normalize().startsWith(backupsDir().toAbsolutePath().normalize())) return false
        return runCatching { Files.deleteIfExists(p) }.getOrDefault(false)
    }

    fun resolveAutoBackupForDownload(fileName: String): Path? {
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) return null
        val p = backupsDir().resolve(fileName)
        if (!p.exists() || !Files.isRegularFile(p)) return null
        if (!p.toAbsolutePath().normalize().startsWith(backupsDir().toAbsolutePath().normalize())) return null
        return p
    }

    // endregion

    // region tar walker (same exclusion list as v0.34.0 BackupRoutes)

    private fun walk(root: Path, base: Path, tar: TarArchiveOutputStream) {
        if (!Files.isDirectory(root)) return
        Files.walk(root).use { stream ->
            stream.forEach { p ->
                if (p == root) return@forEach
                val rel = base.relativize(p).toString().replace('\\', '/')
                if (shouldExclude(rel)) return@forEach
                // Exclude the backups dir itself — backup of backups is silly + grows fast.
                if (rel == ".vibecoder/backups" || rel.startsWith(".vibecoder/backups/")) return@forEach
                try {
                    val entry = TarArchiveEntry(p.toFile(), rel)
                    tar.putArchiveEntry(entry)
                    if (Files.isRegularFile(p)) Files.copy(p, tar)
                    tar.closeArchiveEntry()
                } catch (e: Throwable) {
                    log.debug(e) { "tar entry failed: $rel" }
                }
            }
        }
    }

    private val excludedSegments = setOf(".vibecoder/__scratch__")
    private val excludedRelPrefixes = listOf(
        "dev-tools/gradle/caches",
        "dev-tools/gradle/daemon",
        "dev-tools/npm-cache",
        "dev-tools/playwright",
        "postgres",
    )
    private val excludedBasenames = setOf(".DS_Store")

    private fun shouldExclude(rel: String): Boolean {
        if (excludedRelPrefixes.any { rel == it || rel.startsWith("$it/") }) return true
        if (rel.contains("/logs/") || rel.endsWith("/logs")) return true
        if (rel.split('/').lastOrNull() in excludedBasenames) return true
        if (excludedSegments.any { rel.startsWith(it) }) return true
        return false
    }

    // endregion
}
