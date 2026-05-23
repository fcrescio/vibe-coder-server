package com.siamakerlab.vibecoder.server.db

import org.jetbrains.exposed.sql.Table

object Devices : Table("devices") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val tokenHash = varchar("token_hash", 128)
    val createdAt = varchar("created_at", 64)
    val lastSeenAt = varchar("last_seen_at", 64).nullable()
    // v0.4.0+: admin 사용자와 채널 연결 (마이그레이션 시 nullable / default 로 추가)
    val userId = varchar("user_id", 64).nullable()
    val channel = varchar("channel", 16).default("app")  // "app" | "web"
    override val primaryKey = PrimaryKey(id)
}

object AdminUsers : Table("admin_users") {
    val id = varchar("id", 64)
    val username = varchar("username", 32).uniqueIndex()
    val passwordHash = varchar("password_hash", 96)
    val createdAt = varchar("created_at", 64)
    val lastLoginAt = varchar("last_login_at", 64).nullable()
    val passwordChangedAt = varchar("password_changed_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object Projects : Table("projects") {
    val id = varchar("id", 64)
    val name = varchar("name", 256)
    val packageName = varchar("package_name", 256)
    val sourcePath = text("source_path")
    val moduleName = varchar("module_name", 128)
    val debugTask = varchar("debug_task", 128)
    val createdAt = varchar("created_at", 64)
    val updatedAt = varchar("updated_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object Builds : Table("builds") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val variant = varchar("variant", 32)
    val status = varchar("status", 32)
    val logPath = text("log_path").nullable()
    val artifactId = varchar("artifact_id", 64).nullable()
    val errorMessage = text("error_message").nullable()
    val startedAt = varchar("started_at", 64).nullable()
    val finishedAt = varchar("finished_at", 64).nullable()
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object Artifacts : Table("artifacts") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val buildId = varchar("build_id", 64)
    val type = varchar("type", 32)
    val fileName = varchar("file_name", 256)
    val filePath = text("file_path")
    val sizeBytes = long("size_bytes")
    val sha256 = varchar("sha256", 128)
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object UploadedFiles : Table("uploaded_files") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val originalName = text("original_name")
    val filePath = text("file_path")
    val mimeType = varchar("mime_type", 128).nullable()
    val sizeBytes = long("size_bytes")
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

/**
 * v0.15.0 — 운영 audit log.
 *
 * 보존 대상: 로그인 / 비번 변경 / 디바이스 revoke / 프로젝트 create-delete /
 * 빌드 enqueue-cancel / MCP install / settings 변경 / git 토큰 / claude 콘솔
 * new/cancel 등 운영 정책상 추적 가치가 있는 사건만. 모든 API 호출을 적재하지는
 * 않음 (request log 가 아님 — IAM-level audit).
 *
 * 인덱스 정책:
 *  - 기본 PK + ts 내림차순 — `/audit` 페이지의 최근순 listing.
 *  - action / userId 별 필터를 자주 쓰면 추가 인덱스 권장 (v0.15.x 후속).
 *
 * Rotation 정책: 별도. 1인 LAN 도구라 무한 누적해도 수년 단위 안전. 정말 커지면
 * `DELETE FROM audit_log WHERE ts < now() - interval '90 days'` 등으로 별도 정리.
 */
object AuditLog : Table("audit_log") {
    val id = varchar("id", 64)
    val ts = varchar("ts", 64)
    val userId = varchar("user_id", 64).nullable()
    val deviceId = varchar("device_id", 64).nullable()
    val ip = varchar("ip", 64).nullable()
    val action = varchar("action", 64)
    val resourceType = varchar("resource_type", 64).nullable()
    val resourceId = varchar("resource_id", 256).nullable()
    val result = varchar("result", 16)   // OK / FAIL / DENIED
    val detail = text("detail").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(ts))
        index(isUnique = false, columns = arrayOf(action))
    }
}

val AllTables = arrayOf(AdminUsers, Devices, Projects, Builds, Artifacts, UploadedFiles, AuditLog)
