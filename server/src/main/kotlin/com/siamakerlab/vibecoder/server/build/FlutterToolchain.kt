package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.ProcessRunner
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

class FlutterToolchain(private val timeoutMinutes: Int) : BuildToolchain {
    constructor(config: ServerConfig) : this(config.build.timeoutMinutes)

    override suspend fun runBuild(
        source: Path,
        moduleName: String,
        variant: BuildVariant,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
        signing: SigningCredentials?,
    ): Int {
        if (!isFlutterAvailable()) {
            logger.line(
                "ERROR",
                "flutter command not found. Install Flutter from /env-setup or run: docker exec -it vibe-coder-server vibe-doctor flutter",
            )
            return 127
        }

        val command = listOf(flutterBin(), "build", "apk", "--debug")
        logger.info("Flutter build command: ${command.joinToString(" ")}")
        val result = ProcessRunner(workdir = source).run(
            command = command,
            timeout = timeoutMinutes.minutes,
            cancellation = cancellation,
        ) { level, line -> logger.line(level, line) }

        logger.info("Flutter exited code=${result.exitCode} timedOut=${result.timedOut} duration=${result.durationMs}ms")
        return result.exitCode
    }

    override fun findArtifact(source: Path, moduleName: String, variant: BuildVariant): Path? {
        val apk = source.resolve("build/app/outputs/flutter-apk/app-debug.apk")
        return apk.takeIf { Files.isRegularFile(it) }
    }

    private fun isFlutterAvailable(): Boolean = try {
        val p = ProcessBuilder(flutterBin(), "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.exitValue() == 0
        else {
            p.destroyForcibly()
            false
        }
    } catch (_: Throwable) {
        false
    }

    private fun flutterBin(): String {
        System.getenv("FLUTTER_CMD")?.ifBlank { null }?.let { return it }
        val candidates = listOf(
            Path.of("/home/vibe/.local/bin/flutter"),
            Path.of("/home/vibe/.local/flutter/bin/flutter"),
        )
        return candidates.firstOrNull { Files.isExecutable(it) }?.toString() ?: "flutter"
    }
}
