plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.siamakerlab.vibecoder.server.ServerMainKt")
    // Pass --no-daemon-friendly JVM args; not strictly needed but stabilises shutdown.
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(project(":shared"))

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.serialization.kotlinx.json)

    // DB
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    // v0.14.0 — PostgreSQL 전환. SQLite 는 P6 (legacy migration) 시 추가.
    implementation(libs.postgresql.jdbc)
    implementation(libs.hikari)

    // Config
    implementation(libs.kaml)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging.jvm)

    // Security
    implementation(libs.bcrypt)

    // Email (v0.17.0+) — SMTP via Jakarta Mail + Angus implementation.
    implementation(libs.jakarta.mail.api)
    implementation(libs.jakarta.mail.impl)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnit()
}
