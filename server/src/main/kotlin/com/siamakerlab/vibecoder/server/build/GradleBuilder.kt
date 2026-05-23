package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.ProcessRunner
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

class GradleBuilder(private val config: ServerConfig) {

    suspend fun runAssembleDebug(
        source: Path,
        moduleName: String,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
    ): Int {
        val os = OsType.detect()
        // Use `:module:assembleDebug` syntax which works on every OS without quoting.
        val fullTask = ":$moduleName:$debugTask"
        val tasks = listOf(fullTask, "--no-daemon", "--stacktrace")
        val command = os.gradleCommand(source, tasks)
        logger.info("OS=$os, gradle command: ${command.joinToString(" ")}")

        val runner = ProcessRunner(workdir = source)
        val result = runner.run(
            command = command,
            timeout = config.build.timeoutMinutes.minutes,
            cancellation = cancellation,
        ) { level, line -> logger.line(level, line) }

        logger.info("Gradle exited code=${result.exitCode} timedOut=${result.timedOut} duration=${result.durationMs}ms")
        return result.exitCode
    }
}
