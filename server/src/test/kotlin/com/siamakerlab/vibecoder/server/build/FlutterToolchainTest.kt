package com.siamakerlab.vibecoder.server.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlutterToolchainTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `finds flutter debug apk output`() {
        val apk = tempDir.resolve("build/app/outputs/flutter-apk/app-debug.apk")
        Files.createDirectories(apk.parent)
        Files.writeString(apk, "apk")

        assertEquals(
            apk,
            FlutterToolchain(timeoutMinutes = 1).findArtifact(tempDir, "ignored", BuildVariant.DEBUG),
        )
    }

    @Test
    fun `returns null when flutter debug apk is missing`() {
        assertNull(FlutterToolchain(timeoutMinutes = 1).findArtifact(tempDir, "ignored", BuildVariant.DEBUG))
    }
}
