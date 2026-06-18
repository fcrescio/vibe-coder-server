package com.siamakerlab.vibecoder.server.projects

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PackageNameDetectorTest {

    @Test
    fun `detectApplicationId from app module build gradle kts`(@TempDir tempDir: Path) {
        val appDir = tempDir.resolve("app")
        Files.createDirectories(appDir)
        Files.writeString(appDir.resolve("build.gradle.kts"), """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "com.example.testapp"
                defaultConfig {
                    applicationId = "com.example.testapp"
                }
            }
        """.trimIndent())
        Files.writeString(tempDir.resolve("settings.gradle.kts"), """include(":app")""")

        val result = PackageNameDetector.detectApplicationId(tempDir)
        assertEquals("com.example.testapp", result)
    }

    @Test
    fun `detectApplicationId falls back to namespace when no applicationId`(@TempDir tempDir: Path) {
        val appDir = tempDir.resolve("app")
        Files.createDirectories(appDir)
        Files.writeString(appDir.resolve("build.gradle.kts"), """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "com.example.namespace"
            }
        """.trimIndent())
        Files.writeString(tempDir.resolve("settings.gradle.kts"), """include(":app")""")

        val result = PackageNameDetector.detectApplicationId(tempDir)
        assertEquals("com.example.namespace", result)
    }

    @Test
    fun `detectApplicationId ignores library module namespace`(@TempDir tempDir: Path) {
        val appDir = tempDir.resolve("app")
        Files.createDirectories(appDir)
        Files.writeString(appDir.resolve("build.gradle.kts"), """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "com.example.app"
                defaultConfig {
                    applicationId = "com.example.app"
                }
            }
        """.trimIndent())
        val libDir = tempDir.resolve("library")
        Files.createDirectories(libDir)
        Files.writeString(libDir.resolve("build.gradle.kts"), """
            plugins {
                id("com.android.library")
            }
            android {
                namespace = "com.example.library"
            }
        """.trimIndent())
        Files.writeString(tempDir.resolve("settings.gradle.kts"), """include(":app", ":library")""")

        val result = PackageNameDetector.detectApplicationId(tempDir)
        assertEquals("com.example.app", result)
    }

    @Test
    fun `detectProjectType returns kotlin for gradle project`(@TempDir tempDir: Path) {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), """rootProject.name = "test"""")
        val result = PackageNameDetector.detectProjectType(tempDir)
        assertEquals("kotlin", result)
    }

    @Test
    fun `detectProjectType returns flutter for pubspec project`(@TempDir tempDir: Path) {
        Files.writeString(tempDir.resolve("pubspec.yaml"), "name: test_app")
        val result = PackageNameDetector.detectProjectType(tempDir)
        assertEquals("flutter", result)
    }

    @Test
    fun `detectProjectType returns null for empty directory`(@TempDir tempDir: Path) {
        val result = PackageNameDetector.detectProjectType(tempDir)
        assertNull(result)
    }

    @Test
    fun `detectAppModule finds app module`(@TempDir tempDir: Path) {
        val appDir = tempDir.resolve("app")
        Files.createDirectories(appDir)
        Files.writeString(appDir.resolve("build.gradle.kts"), """
            plugins { id("com.android.application") }
        """.trimIndent())
        Files.writeString(tempDir.resolve("settings.gradle.kts"), """include(":app")""")

        val result = PackageNameDetector.detectAppModule(tempDir)
        assertEquals("app", result)
    }

    @Test
    fun `detectAppModule returns null when no app plugin`(@TempDir tempDir: Path) {
        val libDir = tempDir.resolve("lib")
        Files.createDirectories(libDir)
        Files.writeString(libDir.resolve("build.gradle.kts"), """
            plugins { id("com.android.library") }
        """.trimIndent())
        Files.writeString(tempDir.resolve("settings.gradle.kts"), """include(":lib")""")

        val result = PackageNameDetector.detectAppModule(tempDir)
        assertNull(result)
    }
}
