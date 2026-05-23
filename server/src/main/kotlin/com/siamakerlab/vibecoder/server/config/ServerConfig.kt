package com.siamakerlab.vibecoder.server.config

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val server: ServerSection,
    val workspace: WorkspaceSection,
    val security: SecuritySection,
    val claude: ClaudeSection,
    val build: BuildSection,
    val git: GitSection,
    val cors: CorsSection = CorsSection(),
)

@Serializable
data class ServerSection(
    val name: String = "Vibe Coder Server",
    val host: String = "0.0.0.0",
    val port: Int = 17880,
    val version: String = "0.1.0",
)

@Serializable
data class WorkspaceSection(
    val root: String = "./workspace",
    val maxUploadSizeMb: Long = 100,
    val artifactKeepCount: Int = 20,
    val uploadDeniedExtensions: List<String> = listOf("exe", "bat", "cmd", "ps1", "sh"),
)

@Serializable
data class SecuritySection(
    val pairingEnabled: Boolean = true,
    val pairingCodeExpireMinutes: Int = 10,
    val allowRawShell: Boolean = false,
)

@Serializable
data class ClaudeSection(
    val enabled: Boolean = true,
    val path: String = "auto",
    val timeoutMinutes: Int = 60,
    val autoBuildAfterTask: Boolean = false,
)

@Serializable
data class BuildSection(
    val timeoutMinutes: Int = 30,
    val defaultDebugTask: String = "assembleDebug",
)

@Serializable
data class GitSection(
    val enabled: Boolean = true,
    val path: String = "auto",
)

/**
 * v0.12.0 — CORS 정책.
 *
 * 기본값 `["*"]` 는 anyHost — LAN 격리 환경 가정. 외부 origin 에서 호출하는
 * web 앱이 있다면 그 origin 만 명시. `*` 가 포함되면 다른 entries 는 무시되고
 * anyHost. 그 외엔 entries 만 명시적 허용 (Ktor allowHost — wildcard
 * subdomain `*.example.com` 패턴 지원).
 *
 * 환경변수 `VIBECODER_CORS_ALLOWED_HOSTS` (콤마 구분) 가 있으면 server.yml
 * 값을 override — compose.yml 에서 server.yml 수정 없이 바꿀 때 사용.
 *
 * 보안 경고: 외부 IP 노출 환경에서 `*` 는 CSRF 위험. 신뢰 origin 만 명시.
 */
@Serializable
data class CorsSection(
    val allowedHosts: List<String> = listOf("*"),
    val allowCredentials: Boolean = false,
)
