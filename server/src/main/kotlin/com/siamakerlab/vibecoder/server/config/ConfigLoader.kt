package com.siamakerlab.vibecoder.server.config

import com.charleskorn.kaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object ConfigLoader {

    private val yaml = Yaml.default

    /**
     * Load `server.yml` from (in order):
     *   1. $VIBECODER_CONFIG_DIR/server.yml
     *   2. ./config/server.yml          (working directory)
     *   3. classpath:config/server.yml  (packaged default)
     */
    fun load(): ServerConfig {
        val external = sequenceOf(
            System.getenv("VIBECODER_CONFIG_DIR")?.let { Path.of(it, "server.yml") },
            Path.of("config", "server.yml"),
        ).filterNotNull().firstOrNull { it.exists() }

        val text = if (external != null) {
            Files.readString(external)
        } else {
            requireNotNull(
                ConfigLoader::class.java.classLoader
                    .getResourceAsStream("config/server.yml")
            ) { "config/server.yml not found on classpath" }
                .bufferedReader().use { it.readText() }
        }

        val cfg = yaml.decodeFromString(ServerConfig.serializer(), text)
        return applyEnvironmentOverrides(cfg)
    }

    private fun applyEnvironmentOverrides(cfg: ServerConfig): ServerConfig {
        var current = cfg

        // workspace root
        System.getenv("VIBECODER_WORKSPACE_ROOT")?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(workspace = current.workspace.copy(root = it))
        }

        // v0.12.0 — CORS allowed hosts (콤마 구분)
        System.getenv("VIBECODER_CORS_ALLOWED_HOSTS")?.takeIf { it.isNotBlank() }?.let { raw ->
            val hosts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isNotEmpty()) {
                current = current.copy(cors = current.cors.copy(allowedHosts = hosts))
            }
        }
        System.getenv("VIBECODER_CORS_ALLOW_CREDENTIALS")?.takeIf { it.isNotBlank() }?.let {
            current = current.copy(cors = current.cors.copy(allowCredentials = it.equals("true", true)))
        }

        return current
    }
}
