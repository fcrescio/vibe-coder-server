package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.BuildRow
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import kotlinx.coroutines.flow.MutableSharedFlow

class BuildService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val projects: ProjectService,
    private val buildRepo: BuildRepository,
    private val queue: TaskQueue,
    private val builder: GradleBuilder,
    private val artifactService: ArtifactService,
    private val clock: Clock,
) {

    /**
     * Enqueue a debug build. Returns the BuildRow immediately (status=PENDING).
     */
    fun enqueueDebug(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        val buildId = Ids.buildId()
        val logFile = workspace.buildLogFile(projectId, buildId)
        val build = buildRepo.create(buildId, projectId, "debug", logFile.toString())

        queue.submit(
            projectId = projectId, taskId = buildId,
            onStart = { buildRepo.setStatus(buildId, TaskStatus.RUNNING) },
            executor = { cancel ->
                val logger = TaskLogger(buildId, logFile, hub, clock)
                try {
                    val exit = builder.runAssembleDebug(
                        source = java.nio.file.Path.of(row.sourcePath),
                        moduleName = row.moduleName,
                        debugTask = row.debugTask,
                        logger = logger,
                        cancellation = cancel,
                    )
                    if (exit != 0) throw ApiException(500, "build_failed", "gradle exit $exit")
                    val apk = ApkFinder.findLatestDebug(java.nio.file.Path.of(row.sourcePath), row.moduleName)
                        ?: throw ApiException(500, "apk_not_found", "no apk under build/outputs/apk/debug")
                    logger.info("Found APK: $apk")
                    val artifact = artifactService.storeDebugApk(projectId, buildId, apk)
                    buildRepo.attachArtifact(buildId, artifact.id)
                    logger.info("Stored artifact ${artifact.id} (sha256=${artifact.sha256.take(12)}..., size=${artifact.sizeBytes} bytes)")
                } finally {
                    logger.close()
                }
            },
            onSuccess = {
                buildRepo.setStatus(buildId, TaskStatus.SUCCESS)
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.SUCCESS.name))
            },
            onFailure = { e ->
                buildRepo.setStatus(buildId, TaskStatus.FAILED, e.message)
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.FAILED.name, e.message))
            },
            onCancel = {
                buildRepo.setStatus(buildId, TaskStatus.CANCELED)
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.CANCELED.name))
            },
        )
        return build
    }

    /**
     * Inline build used by Claude task's autoBuild option (already inside a queued task).
     * Not enqueued — runs in-place.
     */
    suspend fun runDebug(projectId: String, hub: LogHub) {
        val row = projects.rowOrThrow(projectId)
        val buildId = Ids.buildId()
        val logFile = workspace.buildLogFile(projectId, buildId)
        val build = buildRepo.create(buildId, projectId, "debug", logFile.toString())
        buildRepo.setStatus(buildId, TaskStatus.RUNNING)
        val logger = TaskLogger(buildId, logFile, hub, clock)
        try {
            val cancel = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val exit = builder.runAssembleDebug(
                source = java.nio.file.Path.of(row.sourcePath),
                moduleName = row.moduleName, debugTask = row.debugTask,
                logger = logger, cancellation = cancel,
            )
            if (exit != 0) {
                buildRepo.setStatus(buildId, TaskStatus.FAILED, "gradle exit $exit")
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.FAILED.name, "gradle exit $exit"))
                return
            }
            val apk = ApkFinder.findLatestDebug(java.nio.file.Path.of(row.sourcePath), row.moduleName)
            if (apk == null) {
                buildRepo.setStatus(buildId, TaskStatus.FAILED, "apk not found")
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.FAILED.name, "apk not found"))
                return
            }
            val artifact = artifactService.storeDebugApk(projectId, buildId, apk)
            buildRepo.attachArtifact(buildId, artifact.id)
            buildRepo.setStatus(buildId, TaskStatus.SUCCESS)
            hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.SUCCESS.name))
        } finally {
            logger.close()
        }
    }

    fun cancel(buildId: String) {
        kotlinx.coroutines.runBlocking { queue.cancel(buildId) }
    }

    fun list(projectId: String): List<BuildDto> =
        buildRepo.listForProject(projectId).map { it.toDto() }

    fun get(projectId: String, buildId: String): BuildDto {
        val row = buildRepo.get(buildId) ?: throw ApiException(404, "build_not_found", buildId)
        if (row.projectId != projectId) throw ApiException(404, "build_not_found", buildId)
        return row.toDto()
    }

    private fun BuildRow.toDto() = BuildDto(
        id = id, projectId = projectId, variant = variant, status = status,
        startedAt = startedAt ?: createdAt, finishedAt = finishedAt,
        artifactId = artifactId, errorMessage = errorMessage,
    )
}
