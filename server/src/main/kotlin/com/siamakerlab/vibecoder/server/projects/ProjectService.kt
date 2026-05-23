package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRow
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

class ProjectService(
    private val workspace: WorkspacePath,
    private val repo: ProjectRepository,
    private val buildRepo: BuildRepository,
    private val keystoreGen: KeystoreGenerator,
) {

    /**
     * Project creation (v0.3):
     *   - Client supplies projectId / appName / packageName / optional keystore.
     *   - Server creates `<workspace>/<projectId>/` (empty is fine — Claude scaffolds later).
     *   - Server writes `CLAUDE.md` template inside the project folder.
     *   - If a keystore is requested, server generates it under
     *     `<workspace>/.vibecoder/keystores/<projectId>/` (OUTSIDE the project folder).
     *   - moduleName defaults to "app", debugTask to "assembleDebug".
     */
    fun register(body: RegisterProjectRequestDto): ProjectDto {
        require(body.projectId.isNotBlank()) { "projectId required" }
        require(!body.projectId.contains('/') && !body.projectId.contains('\\') && !body.projectId.contains("..")) {
            "projectId must not contain path separators"
        }
        require(body.appName.isNotBlank()) { "appName required" }
        require(body.packageName.isNotBlank()) { "packageName required" }

        if (repo.findById(body.projectId) != null) {
            throw ApiException(409, "project_already_registered", "${body.projectId} already exists")
        }

        // Keystore generation runs FIRST so a validation failure (weak password etc.)
        // doesn't leave behind an orphaned project folder on disk.
        val keystoreSummary = body.keystore?.let { ksReq ->
            val res = keystoreGen.generate(body.projectId, body.appName, ksReq)
            "alias=${res.alias} file=${res.keystoreFile.fileName}"
        }

        val srcRoot = workspace.projectRoot(body.projectId)
        if (srcRoot.notExists()) {
            srcRoot.createDirectories()
            log.info { "created empty project folder $srcRoot" }
        }

        val claudeMd = srcRoot.resolve("CLAUDE.md")
        if (claudeMd.notExists()) {
            Files.writeString(claudeMd, ClaudeMdTemplate.CONTENT)
        }

        // v0.7.0 — .claude/settings.json: vibe-coder 비인터랙티브 환경 권장 정책.
        // bypassPermissions + 인터랙티브 도구 deny + 비대화형 env 강제.
        val claudeDir = srcRoot.resolve(".claude")
        if (claudeDir.notExists()) claudeDir.createDirectories()
        val settingsJson = claudeDir.resolve("settings.json")
        if (settingsJson.notExists()) {
            Files.writeString(settingsJson, ClaudeSettingsTemplate.CONTENT)
        }

        val vibeDir = workspace.vibecoderDir(body.projectId)
        val projectYml = vibeDir.resolve("project.yml")
        if (projectYml.notExists()) {
            projectYml.writeText(buildProjectYml(body, srcRoot, keystoreSummary))
        }

        val row = repo.insert(
            id = body.projectId,
            name = body.appName,
            packageName = body.packageName,
            sourcePath = srcRoot.toString(),
            moduleName = DEFAULT_MODULE,
            debugTask = DEFAULT_DEBUG_TASK,
        )
        return row.toDto(hasGitChanges = false, lastBuildStatus = null)
    }

    fun list(): List<ProjectDto> {
        val rows = repo.list()
        return rows.map { row ->
            val last = buildRepo.lastForProject(row.id)
            row.toDto(false, last?.status?.name)
        }
    }

    fun get(id: String): ProjectDto {
        val row = repo.findById(id)
            ?: throw ApiException(404, "project_not_found", "$id not registered")
        val last = buildRepo.lastForProject(id)
        return row.toDto(false, last?.status?.name)
    }

    fun sourcePathOrThrow(id: String): Path {
        val row = repo.findById(id) ?: throw ApiException(404, "project_not_found", id)
        return Path.of(row.sourcePath)
    }

    fun rowOrThrow(id: String): ProjectRow =
        repo.findById(id) ?: throw ApiException(404, "project_not_found", id)

    fun delete(id: String): Boolean = repo.delete(id) > 0

    private fun buildProjectYml(
        req: RegisterProjectRequestDto,
        absSource: Path,
        keystoreSummary: String?,
    ): String = """
        |# Vibe Coder project metadata
        |id: ${req.projectId}
        |appName: ${req.appName}
        |packageName: ${req.packageName}
        |sourcePath: $absSource
        |moduleName: $DEFAULT_MODULE
        |debugTask: $DEFAULT_DEBUG_TASK
        |keystore: ${keystoreSummary ?: "none"}
    """.trimMargin()

    private fun ProjectRow.toDto(hasGitChanges: Boolean, lastBuildStatus: String?): ProjectDto =
        ProjectDto(
            id = id, name = name, packageName = packageName,
            sourcePath = sourcePath, moduleName = moduleName, debugTask = debugTask,
            lastBuildStatus = lastBuildStatus, hasGitChanges = hasGitChanges,
            updatedAt = updatedAt,
        )

    companion object {
        const val DEFAULT_MODULE = "app"
        const val DEFAULT_DEBUG_TASK = "assembleDebug"
    }
}
