package com.siamakerlab.vibecoder.server.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Flat workspace layout (v0.2):
 *
 *   <root>/
 *     <projectId>/                ← user's Android project root (gradlew, settings, app/, ...)
 *     <projectId>/CLAUDE.md       ← auto-written if missing
 *     .vibecoder/
 *       <projectId>/
 *         project.yml             ← registration metadata
 *         tasks/                  ← claude task state
 *         builds/
 *         artifacts/
 *         uploads/
 *         logs/<id>.log
 *         patches/<taskId>.patch
 *       keystores/
 *         <projectId>/            ← signing keystores stored OUTSIDE project folder
 *           <appName>.keystore
 *           <appName>-keystore.properties
 *
 * All filesystem-touching services MUST funnel through this class so path
 * traversal defense ([PathSafety]) and directory creation are consistent.
 */
class WorkspacePath(val root: Path) {

    init {
        root.createDirectories()
    }

    /** `<root>/<projectId>` — NOT auto-created; the user places their project here. */
    fun projectRoot(projectId: String): Path =
        PathSafety.normalizeAndCheck(root, projectId)

    /** `<root>/.vibecoder/<projectId>` — auto-created (server-owned metadata sidecar). */
    fun vibecoderDir(projectId: String): Path {
        val metaRoot = root.resolve(".vibecoder").also { it.createDirectories() }
        val safe = PathSafety.normalizeAndCheck(metaRoot, projectId)
        if (Files.notExists(safe)) safe.createDirectories()
        return safe
    }

    fun tasksDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("tasks").also { it.createDirectories() }

    fun buildsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("builds").also { it.createDirectories() }

    fun artifactsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("artifacts").also { it.createDirectories() }

    fun uploadsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("uploads").also { it.createDirectories() }

    fun logsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("logs").also { it.createDirectories() }

    fun patchesDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("patches").also { it.createDirectories() }

    fun taskLogFile(projectId: String, taskId: String): Path =
        logsDir(projectId).resolve("$taskId.log")

    fun buildLogFile(projectId: String, buildId: String): Path =
        logsDir(projectId).resolve("$buildId.log")

    fun debugArtifactDir(projectId: String, buildId: String): Path =
        artifactsDir(projectId).resolve("debug").resolve(buildId).also { it.createDirectories() }

    /** `<root>/.vibecoder/keystores/<projectId>` — signing keystores live OUTSIDE the project folder. */
    fun keystoresDir(projectId: String): Path {
        val ksRoot = root.resolve(".vibecoder").resolve("keystores").also { it.createDirectories() }
        val safe = PathSafety.normalizeAndCheck(ksRoot, projectId)
        if (Files.notExists(safe)) safe.createDirectories()
        return safe
    }

    /** Defense-in-depth: reject paths from DB rows / uploads pointing outside the workspace. */
    fun ensureUnderWorkspace(absolute: Path): Path =
        PathSafety.checkAbsoluteIsInsideWorkspace(root, absolute)
}
