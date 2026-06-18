package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import java.nio.file.Files
import java.nio.file.Path

/**
 * v1.128.0 — Detects applicationId / app module / project type from a cloned
 * Android project without running Gradle.
 *
 * Detection rules (priority):
 *  1. `com.android.application` plugin's `defaultConfig.applicationId` (release base).
 *  2. App module's `namespace` as fallback.
 *  3. If no app module found, scan all gradle files for `applicationId` only
 *     (avoids library module namespace false positives).
 */
internal object PackageNameDetector {

    private val APP_PLUGIN = Regex("""com\.android\.application""")
    private val MODULE_PATH = Regex("""["']:([a-zA-Z0-9_.:\-]+)["']""")
    private val APPLICATION_ID = Regex("""applicationId\s*=?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""")
    private val NAMESPACE = Regex("""namespace\s*=?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""")

    private val GRADLE_FILES = listOf("build.gradle.kts", "build.gradle")

    fun detectAppModule(root: Path): String? {
        if (!Files.isDirectory(root)) return null
        for (mod in readIncludes(root)) {
            val dir = root.resolve(mod.replace(':', '/'))
            if (readGradle(dir)?.let { APP_PLUGIN.containsMatchIn(it) } == true) return mod
        }
        return null
    }

    fun detectApplicationId(root: Path): String? {
        if (!Files.isDirectory(root)) return null

        detectAppModule(root)?.let { appMod ->
            readGradle(root.resolve(appMod.replace(':', '/')))?.let { text ->
                APPLICATION_ID.find(text)?.groupValues?.get(1)?.let { return it }
                NAMESPACE.find(text)?.groupValues?.get(1)?.let { return it }
            }
        }

        return runCatching {
            Files.walk(root, 5).use { stream ->
                for (path in stream) {
                    if (Files.isDirectory(path)) continue
                    val n = path.fileName?.toString() ?: continue
                    if (n !in GRADLE_FILES) continue
                    val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
                    APPLICATION_ID.find(text)?.groupValues?.get(1)?.let { if (it.contains('.')) return@use it }
                }
                null
            }
        }.getOrNull()
    }

    fun detectProjectType(root: Path): String? {
        if (!Files.isDirectory(root)) return null
        if (Files.isRegularFile(root.resolve("pubspec.yaml"))) return ProjectTypes.FLUTTER
        val hasGradle = (GRADLE_FILES + listOf("settings.gradle.kts", "settings.gradle"))
            .any { Files.isRegularFile(root.resolve(it)) }
        return if (hasGradle) ProjectTypes.KOTLIN else null
    }

    private fun readIncludes(root: Path): List<String> {
        for (name in GRADLE_FILES.map { it.replace("build", "settings") }) {
            val f = root.resolve(name)
            if (!Files.isRegularFile(f)) continue
            val text = runCatching { Files.readString(f) }.getOrNull() ?: continue
            return MODULE_PATH.findAll(text)
                .mapNotNull { m -> m.groupValues[1].trim().ifEmpty { null } }
                .toList()
        }
        return emptyList()
    }

    private fun readGradle(moduleDir: Path): String? {
        for (g in GRADLE_FILES) {
            val gf = moduleDir.resolve(g)
            if (!Files.isRegularFile(gf)) continue
            return runCatching { Files.readString(gf) }.getOrNull()
        }
        return null
    }
}
