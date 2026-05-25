package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.67.0 — Phase 46. Group B (admin/운영 JSON API) 의 모든 DTO 묶음.
 *
 * 카테고리:
 *  B1. Multi-user CRUD       — AdminUserDto / UserCreateRequestDto / UserRoleUpdateRequestDto
 *  B2. Build automation      — BuildScheduleDto / BuildScheduleCreateRequestDto /
 *                              BuildSecretDto / AutomationConfigDto
 *  B3. Backup                — AutoBackupEntryDto / BackupListResponseDto
 *  B4. Audit log             — AuditLogEntryDto / AuditLogPageDto
 *  B5. Admin info (read)     — LogSearchHit/CodeSearchHit/CodeStats/DependencyAudit/
 *                              EnvFile/GradleWrapperInfo + 응답 wrapper
 *
 * 단일 파일 묶음 — Group A 의 각 DTO 가 도메인별 분리된 것과 대비. Group B 는 모두 admin
 * 전용 + 응답 shape 단순 → 운영 편의를 위해 한 파일로 grep 가능하게 모음.
 */

// ─────────────────────────────────────────────────────────────────────
// B1. Multi-user (admin only)
// ─────────────────────────────────────────────────────────────────────

@Serializable
data class AdminUserDto(
    val id: String,
    val username: String,
    /** "admin" / "member" / "viewer". */
    val role: String,
    val createdAt: String,
    val lastLoginAt: String? = null,
    val passwordChangedAt: String,
    val totpEnabled: Boolean = false,
    val passwordlessOnly: Boolean = false,
)

@Serializable
data class UsersResponseDto(
    val users: List<AdminUserDto> = emptyList(),
)

@Serializable
data class UserCreateRequestDto(
    val username: String,
    val password: String,
    /** "admin" / "member" / "viewer". default "member". */
    val role: String = "member",
)

@Serializable
data class UserRoleUpdateRequestDto(
    val role: String,
)

// ─────────────────────────────────────────────────────────────────────
// B2. Build automation
// ─────────────────────────────────────────────────────────────────────

@Serializable
data class BuildScheduleDto(
    val id: String,
    val projectId: String,
    val cronExpr: String,
    val variant: String,
    val enabled: Boolean,
    val createdAt: String,
    val lastFiredAt: String? = null,
    val description: String? = null,
)

@Serializable
data class BuildSchedulesResponseDto(
    val schedules: List<BuildScheduleDto> = emptyList(),
)

@Serializable
data class BuildScheduleCreateRequestDto(
    val cronExpr: String,
    val variant: String = "debug",
    val description: String? = null,
)

@Serializable
data class BuildScheduleToggleRequestDto(
    val enabled: Boolean,
)

// ─────────────────────────────────────────────────────────────────────
// B3. Backup
// ─────────────────────────────────────────────────────────────────────

@Serializable
data class AutoBackupEntryDto(
    val fileName: String,
    val sizeBytes: Long,
    /** Epoch millis. */
    val createdAtMs: Long,
)

@Serializable
data class BackupListResponseDto(
    /** 수동 download 시 사용할 파일명 (서버가 timestamp 부착). */
    val manualFileName: String,
    /** 스케줄러가 만든 자동 backup 목록 (최신순). */
    val autoBackups: List<AutoBackupEntryDto> = emptyList(),
)

@Serializable
data class BackupRunNowResponseDto(
    val fileName: String,
    val sizeBytes: Long,
)

// ─────────────────────────────────────────────────────────────────────
// B4. Audit log
// ─────────────────────────────────────────────────────────────────────

@Serializable
data class AuditLogEntryDto(
    val id: String,
    val ts: String,
    val userId: String? = null,
    val deviceId: String? = null,
    val ip: String? = null,
    val action: String,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val result: String,
    val detail: String? = null,
)

@Serializable
data class AuditLogPageDto(
    val entries: List<AuditLogEntryDto> = emptyList(),
    /** 다음 페이지 cursor (offset string). null = 마지막. */
    val nextCursor: String? = null,
    val total: Long = 0,
)

// ─────────────────────────────────────────────────────────────────────
// B5. Admin info (read-only)
// ─────────────────────────────────────────────────────────────────────

// Log search
@Serializable
data class LogSearchHitDto(
    val projectId: String,
    val buildId: String,
    val lineNumber: Int,
    val line: String,
)

@Serializable
data class LogSearchResponseDto(
    val hits: List<LogSearchHitDto> = emptyList(),
)

// Code search
@Serializable
data class CodeSearchHitDto(
    val projectId: String,
    val relPath: String,
    val lineNumber: Int,
    val line: String,
)

@Serializable
data class CodeSearchResponseDto(
    val hits: List<CodeSearchHitDto> = emptyList(),
)

// Code stats
@Serializable
data class CodeLanguageStatDto(
    val language: String,
    val files: Int,
    val lines: Long,
    val bytes: Long,
)

@Serializable
data class CodeStatsResponseDto(
    val projectId: String,
    val totalFiles: Int = 0,
    val totalLines: Long = 0,
    val totalBytes: Long = 0,
    val durationMs: Long = 0,
    val byLanguage: List<CodeLanguageStatDto> = emptyList(),
    val errorMessage: String? = null,
)

// Dependency audit
@Serializable
data class DependencyCoordinateDto(
    val group: String,
    val name: String,
    val version: String,
)

@Serializable
data class DependencyAuditResponseDto(
    val ok: Boolean = false,
    val moduleName: String = "",
    val configuration: String = "",
    val durationMs: Long = 0,
    val coordinates: List<DependencyCoordinateDto> = emptyList(),
    val rawOutput: String? = null,
    val errorMessage: String? = null,
)

// Env files
@Serializable
data class EnvFileDto(
    val rel: String,
    val exists: Boolean,
    val sizeBytes: Long = 0,
    /** 파일 본문 (text). secret 가능 — 표시 시 사용자 경고 권장. */
    val body: String = "",
)

@Serializable
data class EnvFilesResponseDto(
    val files: List<EnvFileDto> = emptyList(),
)

@Serializable
data class EnvFileSaveRequestDto(
    /** 저장할 상대 경로 (서버가 화이트리스트 검증). */
    val rel: String,
    val body: String,
)

// Gradle wrapper
@Serializable
data class GradleWrapperInfoDto(
    val present: Boolean,
    val currentVersion: String? = null,
    /** "bin" / "all" / null. */
    val distributionType: String? = null,
    val distributionUrl: String? = null,
    val propertiesPath: String,
)

@Serializable
data class GradleWrapperUpdateRequestDto(
    val newVersion: String,
    /** "bin" / "all". default "bin". */
    val distributionType: String = "bin",
)
