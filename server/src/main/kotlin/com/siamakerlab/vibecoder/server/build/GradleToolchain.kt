package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

class GradleToolchain(private val builder: GradleBuilder) : BuildToolchain {
    override suspend fun runBuild(
        source: Path,
        moduleName: String,
        variant: BuildVariant,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
        signing: SigningCredentials?,
    ): Int =
        builder.runAssembleDebug(
            source = source,
            moduleName = moduleName,
            debugTask = debugTask,
            logger = logger,
            cancellation = cancellation,
            signing = signing,
        )

    override fun findArtifact(source: Path, moduleName: String, variant: BuildVariant): Path? =
        ApkFinder.findLatestDebug(source, moduleName)
}
