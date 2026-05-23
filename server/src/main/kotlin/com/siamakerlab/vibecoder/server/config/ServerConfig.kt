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
    val database: DatabaseSection = DatabaseSection(),
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

/**
 * v0.14.0 — PostgreSQL 연결 설정.
 *
 * 기본값은 docker compose 의 postgres 서비스에 맞춤 (host=postgres, port=5432).
 * 환경변수 override 우선순위:
 *   - VIBECODER_DB_URL          전체 JDBC URL (jdbc:postgresql://host:port/dbname)
 *   - VIBECODER_DB_HOST         host 만 (다른 값과 조합)
 *   - VIBECODER_DB_PORT         port
 *   - VIBECODER_DB_NAME         database 이름
 *   - VIBECODER_DB_USER         계정
 *   - VIBECODER_DB_PASSWORD     비밀번호 (직접)
 *   - VIBECODER_DB_PASSWORD_FILE  비밀번호가 들어있는 파일 경로 (Docker secret).
 *                                  존재하면 _PASSWORD 보다 우선.
 *   - VIBECODER_DB_MAX_POOL     Hikari maximumPoolSize (기본 10)
 *
 * 비밀번호 누락 시 startup 실패 (명시적 오류) — production 환경에서 빈 비밀번호로
 * 우연 connect 되는 일을 막음.
 */
@Serializable
data class DatabaseSection(
    val host: String = "postgres",
    val port: Int = 5432,
    val name: String = "vibecoder",
    val user: String = "vibecoder",
    val password: String = "",
    val passwordFile: String = "",
    val maxPoolSize: Int = 10,
    /** SSL 모드 — disable / prefer / require / verify-ca / verify-full. */
    val sslMode: String = "disable",
)
