pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// Auto-provision JDK 17 toolchain via Foojay (Adoptium) when not present locally.
// 프로젝트 CLAUDE.md §6: JDK 17 (전역 매트릭스 21이지만 로컬 환경 제약).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vibe-coder-server"

include(":shared")
include(":server")
