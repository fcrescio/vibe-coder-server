package com.siamakerlab.vibecoder.server.artifacts

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.Sha256
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.ArtifactRow
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ArtifactDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

class ArtifactService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val repo: ArtifactRepository,
    private val buildRepo: BuildRepository,
    private val clock: Clock,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun storeDebugApk(
        projectId: String,
        buildId: String,
        sourceApk: Path,
    ): ArtifactRow {
        val targetDir = workspace.debugArtifactDir(projectId, buildId)
        val targetApk = targetDir.resolve(sourceApk.fileName.toString())
        Files.copy(sourceApk, targetApk, StandardCopyOption.REPLACE_EXISTING)
        val sha = Sha256.hashFile(targetApk)
        val size = targetApk.fileSize()
        val artifact = repo.create(
            id = Ids.artifactId(),
            projectId = projectId,
            buildId = buildId,
            type = "debug-apk",
            fileName = targetApk.fileName.toString(),
            filePath = targetApk.toString(),
            sizeBytes = size,
            sha256 = sha,
        )
        val metadata = ArtifactMetadata(
            artifactId = artifact.id,
            projectId = projectId,
            buildId = buildId,
            variant = "debug",
            status = "success",
            fileName = artifact.fileName,
            sizeBytes = size,
            sha256 = sha,
            createdAt = clock.nowIso(),
        )
        targetDir.resolve("metadata.json").writeText(json.encodeToString(ArtifactMetadata.serializer(), metadata))

        // Best-effort: keep only the N most recent artifacts per project on disk.
        runCatching { pruneOldArtifacts(projectId, config.workspace.artifactKeepCount) }
            .onFailure { log.warn(it) { "[$projectId] artifact prune raised; latest store unaffected" } }

        return artifact
    }

    /**
     * Keep only the [keepCount] newest artifacts for [projectId]; delete the rest.
     *
     * For each pruned artifact this removes:
     *   - the artifact's enclosing directory on disk (APK + metadata.json), if it lives under the workspace root
     *   - any `Builds.artifactId` reference (set to null) so the build row remains as history
     *   - the `Artifacts` row itself
     *
     * Per-artifact failures are logged and skipped; one bad row doesn't abort the rest.
     * `keepCount <= 0` is treated as "no limit" (no pruning).
     *
     * Returns the number of artifacts actually removed.
     */
    fun pruneOldArtifacts(projectId: String, keepCount: Int): Int {
        if (keepCount <= 0) return 0
        val all = repo.listForProjectAll(projectId)
        if (all.size <= keepCount) return 0
        val toPrune = all.drop(keepCount)
        var removed = 0
        for (row in toPrune) {
            runCatching { deleteArtifactFiles(row) }
                .onFailure { log.warn(it) { "[$projectId] failed to delete files for artifact ${row.id} at ${row.filePath}" } }
            runCatching { buildRepo.detachArtifact(row.id) }
                .onFailure { log.warn(it) { "[$projectId] failed to detach artifact ${row.id} from builds" } }
            val n = runCatching { repo.delete(row.id) }
                .onFailure { log.warn(it) { "[$projectId] failed to delete artifact row ${row.id}" } }
                .getOrDefault(0)
            if (n > 0) removed++
        }
        if (removed > 0) {
            log.info { "[$projectId] pruned $removed old artifact(s); kept ${all.size - removed}" }
        }
        return removed
    }

    /**
     * Delete the artifact's enclosing directory on disk
     * (`.vibecoder/<projectId>/artifacts/debug/<buildId>/`).
     * Validates the path stays under the workspace root before touching anything.
     */
    private fun deleteArtifactFiles(row: ArtifactRow) {
        val file = Path.of(row.filePath)
        workspace.ensureUnderWorkspace(file)
        val dir = file.parent ?: return
        workspace.ensureUnderWorkspace(dir)
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .forEach { p -> runCatching { Files.deleteIfExists(p) } }
        }
    }

    fun toDto(row: ArtifactRow): ArtifactDto = ArtifactDto(
        id = row.id, projectId = row.projectId, buildId = row.buildId, type = row.type,
        fileName = row.fileName, sizeBytes = row.sizeBytes, sha256 = row.sha256,
        downloadUrl = ApiPath.artifactDownload(row.projectId, row.id),
        createdAt = row.createdAt,
    )

    @Serializable
    private data class ArtifactMetadata(
        val artifactId: String,
        val projectId: String,
        val buildId: String,
        val variant: String,
        val status: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val createdAt: String,
    )
}
