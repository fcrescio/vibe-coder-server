package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

private val log = KotlinLogging.logger {}

/**
 * v0.35.0 — Gradle wrapper 버전 관리.
 *
 * 프로젝트별 `gradle/wrapper/gradle-wrapper.properties` 의 `distributionUrl`
 * 파싱 → 현재 버전 표시. 사용자가 새 버전 선택 시 distributionUrl 만 atomic
 * write 로 교체 (다음 빌드가 새 wrapper 를 자동 download).
 *
 * 안전 정책:
 *   - 버전 문자열은 `\d+(\.\d+)*(-rc-\d+)?` 만 허용 (path injection 차단).
 *   - distributionType 은 `bin` 또는 `all` 만.
 *   - properties 파일 자체는 그대로 — distributionUrl 만 교체.
 */
class GradleWrapperService(private val workspace: WorkspacePath) {

    data class Info(
        val present: Boolean,
        val currentVersion: String?,
        val distributionType: String?,   // "bin" | "all"
        val distributionUrl: String?,
        val propertiesPath: String,
    )

    fun inspect(projectId: String): Info {
        val props = wrapperProperties(projectId)
        if (!props.isRegularFile()) {
            return Info(false, null, null, null, props.toString())
        }
        val content = runCatching { Files.readString(props) }.getOrNull()
            ?: return Info(true, null, null, null, props.toString())
        val urlLine = content.lineSequence()
            .firstOrNull { it.startsWith("distributionUrl=") }
            ?.removePrefix("distributionUrl=")
            ?.trim()
        val url = urlLine?.replace("\\:", ":")  // properties escaping
        val (version, type) = parseUrl(url)
        return Info(true, version, type, url, props.toString())
    }

    /**
     * distributionUrl 만 교체. 다른 properties (zipStoreBase, validateDistributionUrl 등)
     * 은 보존.
     */
    fun setVersion(projectId: String, newVersion: String, distributionType: String = "bin") {
        require(sanitizeVersion(newVersion)) {
            "invalid version (allowed: ^\\d+(\\.\\d+)*(-rc-\\d+)?$): $newVersion"
        }
        require(distributionType in setOf("bin", "all")) {
            "invalid distributionType (allowed: bin / all): $distributionType"
        }
        val props = wrapperProperties(projectId)
        require(props.isRegularFile()) { "gradle-wrapper.properties not found at $props" }
        val content = Files.readString(props)
        val newUrl = "https\\://services.gradle.org/distributions/gradle-$newVersion-$distributionType.zip"
        val replaced = content.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("distributionUrl=")) "distributionUrl=$newUrl"
            else line
        }
        // properties 끝 라인의 newline 유지
        val finalContent = if (content.endsWith("\n")) "$replaced\n" else replaced
        val tmp = props.resolveSibling(props.fileName.toString() + ".tmp")
        Files.writeString(tmp, finalContent)
        Files.move(tmp, props, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info { "wrapper version: $projectId → $newVersion ($distributionType)" }
    }

    private fun wrapperProperties(projectId: String): Path =
        workspace.projectRoot(projectId).resolve("gradle/wrapper/gradle-wrapper.properties")

    /**
     * distributionUrl 에서 버전 + 타입 추출.
     *   https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
     *   → ("9.5.1", "bin")
     */
    internal fun parseUrl(url: String?): Pair<String?, String?> {
        if (url == null) return null to null
        val regex = Regex("gradle-([0-9]+(?:\\.[0-9]+)*(?:-rc-[0-9]+)?)-(bin|all)\\.zip")
        val m = regex.find(url) ?: return null to null
        return m.groupValues[1] to m.groupValues[2]
    }

    private fun sanitizeVersion(v: String): Boolean =
        v.matches(Regex("^[0-9]+(\\.[0-9]+)*(-rc-[0-9]+)?$"))
}
