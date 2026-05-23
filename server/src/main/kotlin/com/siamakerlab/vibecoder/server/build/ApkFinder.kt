package com.siamakerlab.vibecoder.server.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

object ApkFinder {

    /**
     * Returns the most-recently-modified `.apk` file located in
     * `{source}/{module}/build/outputs/apk/debug/`.
     */
    fun findLatestDebug(source: Path, moduleName: String): Path? {
        val dir = source.resolve(moduleName).resolve("build/outputs/apk/debug")
        if (!dir.exists()) return null
        return Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".apk", ignoreCase = true) }
                .toList()
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        }
    }
}
