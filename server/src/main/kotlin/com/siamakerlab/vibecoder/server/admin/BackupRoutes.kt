package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/**
 * v0.34.0 — `/backup` — 워크스페이스 전체 tar.gz 백업.
 *
 *   GET /backup           — 페이지 (디렉토리 별 size + 다운로드 버튼)
 *   GET /backup/download  — tar.gz stream
 *
 * 큰 트리도 메모리 폭발 없이 stream. PostgreSQL 데이터는 함께 들어가지
 * 않음 (running PG 의 raw data dir 을 tar 로 떠도 동기화 문제 발생) —
 * 대신 페이지 안에서 `pg_dump` 명령 가이드.
 *
 * 제외:
 *   - `.vibecoder/<projectId>/logs/` — 빌드 로그는 보통 거대.
 *   - `dev-tools/gradle/caches/` — 재다운로드 가능.
 *   - `dev-tools/npm-cache/`, `dev-tools/playwright/` 도 동일 이유.
 *
 * 위 정책 덕분에 일반 백업이 GB → 수십 MB 로 줄어든다.
 */
fun Routing.backupRoutes(
    authDeps: AdminRoutesDeps,
    workspace: WorkspacePath,
    /** v0.60.0 — Phase 39 BackupService (수동 download + 자동 목록 + rotation). */
    service: BackupService,
) {
    get("/backup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val sizes = measureSubdirs(workspace.root)
        val autoBackups = service.listAutoBackups()
        call.respondText(
            renderPage(sess.username, sess.csrf, sizes, autoBackups, authDeps.config.backup),
            ContentType.Text.Html,
        )
    }

    get("/backup/download") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${service.downloadFileName()}\"")
        call.respondOutputStream(ContentType.parse("application/gzip")) {
            runCatching { service.streamTarGz(this) }
                .onFailure { log.warn(it) { "backup stream failed: ${it.message}" } }
            log.info { "workspace backup downloaded by ${sess.username}" }
        }
    }

    // v0.60.0 — Phase 39 자동 backup 파일 다운로드.
    get("/backup/auto/{name}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val name = call.parameters["name"]
            ?: return@get call.respondText("missing name", status = io.ktor.http.HttpStatusCode.BadRequest)
        val path = service.resolveAutoBackupForDownload(name)
            ?: return@get call.respondText("not found", status = io.ktor.http.HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
        call.respondFile(path.toFile())
    }

    // v0.60.0 — Phase 39 자동 backup 삭제.
    post("/backup/auto/{name}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        com.siamakerlab.vibecoder.server.auth.CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val name = call.parameters["name"]
            ?: return@post call.respondRedirect("/backup?err=missing_name")
        val ok = service.deleteAutoBackup(name)
        call.respondRedirect("/backup?${if (ok) "ok=deleted" else "err=not_found"}")
    }

    // v0.60.0 — Phase 39 수동 즉시 백업 트리거 (스케줄 없이 한 번).
    post("/backup/auto/run-now") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        com.siamakerlab.vibecoder.server.auth.CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val ok = runCatching {
            service.createScheduled()
            service.deleteOldestOverRetention(authDeps.config.backup.retentionCount.coerceAtLeast(1))
        }.isSuccess
        call.respondRedirect("/backup?${if (ok) "ok=created" else "err=failed"}")
    }
}

// v0.60.0 — Phase 39 walk / exclusion 로직은 BackupService 로 이전됨.

private data class SubdirSize(val name: String, val bytes: Long)

private fun measureSubdirs(root: Path): List<SubdirSize> {
    if (!Files.isDirectory(root)) return emptyList()
    return Files.list(root).use { stream ->
        stream.filter { Files.isDirectory(it) }.map { dir ->
            val size = runCatching {
                Files.walk(dir).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
                }
            }.getOrDefault(0L)
            SubdirSize(dir.name, size)
        }.toList().sortedByDescending { it.bytes }
    }
}

private fun humanBytes(b: Long): String {
    if (b < 1024) return "${b}B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) {
        v /= 1024.0
        i++
    }
    return "%.1f%s".format(v, units[i])
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private fun renderPage(
    username: String,
    csrf: String?,
    sizes: List<SubdirSize>,
    autoBackups: List<BackupService.AutoBackupEntry> = emptyList(),
    backupCfg: com.siamakerlab.vibecoder.server.config.BackupSection? = null,
): String {
    val total = sizes.sumOf { it.bytes }
    val rowsHtml = sizes.joinToString("") { s ->
        val excluded = s.name in setOf("postgres", "dev-tools") || s.name == ".vibecoder"
        val note = when (s.name) {
            "postgres" -> " <small class=\"dim\">(제외 — pg_dump 권장)</small>"
            "dev-tools" -> " <small class=\"dim\">(caches/daemon/npm-cache/playwright 제외)</small>"
            else -> ""
        }
        """<tr><td><code>${esc(s.name)}/</code>$note</td><td style="text-align:right">${humanBytes(s.bytes)}</td></tr>"""
    }
    return AdminTemplates.shell(
        title = "백업 / 복원",
        username = username,
        currentPath = "/backup",
        csrf = csrf,
        body = """
<header>
  <h1>워크스페이스 백업 <small class="dim" style="font-size:14px;font-weight:400">v0.34.0</small></h1>
</header>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">현재 크기 (총 ${humanBytes(total)})</h2>
  <table class="devices" style="margin:0">
    <thead><tr><th>디렉토리</th><th style="text-align:right">크기</th></tr></thead>
    <tbody>$rowsHtml</tbody>
  </table>
</div>

<div class="card">
  <h2 style="margin-top:0">tar.gz 다운로드</h2>
  <p>워크스페이스 전체를 한 파일로 stream 다운로드합니다.</p>
  <p><a href="/backup/download" class="primary" style="display:inline-block;padding:10px 18px;text-decoration:none">⬇ vibe-workspace-&lt;timestamp&gt;.tar.gz</a></p>
  <p class="hint" style="margin-top:8px">제외: <code>postgres/</code>, <code>dev-tools/gradle/caches</code>, <code>dev-tools/gradle/daemon</code>, <code>dev-tools/npm-cache</code>, <code>dev-tools/playwright</code>, 빌드 logs/. 일반 백업 크기는 위 표의 합보다 훨씬 작습니다.</p>
</div>

<div class="card" style="margin-top:14px">
  <h2 style="margin-top:0">자동 백업 (v0.60.0+)</h2>
  ${if (backupCfg == null || !backupCfg.enabled) """
  <p>현재 <strong class="dim">비활성</strong>. <code>server.yml</code> 의 <code>backup.enabled: true</code> 로 켜고 컨테이너 재기동.</p>
  <pre class="diff-block">backup:
  enabled: true
  cron: "03:00"        # 매일 새벽 3시
  retentionCount: 7    # 최근 7개 보관</pre>
  """ else """
  <p>현재 <strong class="ok">활성</strong> · cron <code>${esc(backupCfg.cron)}</code> · 최근 <strong>${backupCfg.retentionCount}</strong> 개 보관.</p>
  """}
  <form method="post" action="/backup/auto/run-now?_csrf=${esc(csrf ?: "")}" style="margin-bottom:10px">
    <button type="submit" class="chip chip-link" onclick="return confirm('지금 한 번 백업을 만들까요? rotation 도 적용됩니다.')">지금 백업 한 번 실행</button>
  </form>
  ${if (autoBackups.isEmpty()) """
  <p class="dim" style="font-size:12px">아직 자동 백업 파일이 없습니다.</p>
  """ else """
  <table class="devices" style="margin:0">
    <thead><tr><th>파일</th><th style="text-align:right">크기</th><th>시각</th><th></th></tr></thead>
    <tbody>
      ${autoBackups.joinToString("") { entry ->
        """<tr>
          <td><code>${esc(entry.fileName)}</code></td>
          <td style="text-align:right">${humanBytes(entry.sizeBytes)}</td>
          <td class="dim" style="font-size:11px">${esc(java.time.Instant.ofEpochMilli(entry.createdAtMs).toString())}</td>
          <td>
            <a href="/backup/auto/${esc(entry.fileName)}" class="chip chip-link" style="font-size:11px">⬇</a>
            <form method="post" action="/backup/auto/${esc(entry.fileName)}/delete?_csrf=${esc(csrf ?: "")}" style="display:inline" onsubmit="return confirm('이 백업 파일을 삭제할까요?')">
              <button type="submit" class="chip chip-danger" style="font-size:11px">삭제</button>
            </form>
          </td>
        </tr>"""
      }}
    </tbody>
  </table>
  """}
</div>

<div class="card" style="margin-top:14px;background:rgba(80,150,255,0.06)">
  <h2 style="margin-top:0">PostgreSQL 별도 백업</h2>
  <p>위 tar.gz 는 running PG 의 data dir 을 포함하지 않습니다 (raw page tear 위험).
    다음 명령으로 logical dump:</p>
  <pre class="diff-block">docker exec vibe-coder-postgres \\
  pg_dump -U vibecoder -F c vibecoder \\
  > vibe-pg-${'$'}(date +%F).pgdump</pre>
  <p class="hint">복원: <code>pg_restore -U vibecoder -d vibecoder vibe-pg-YYYY-MM-DD.pgdump</code></p>
</div>

<div class="card" style="margin-top:14px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
  <h2 style="margin-top:0">복원 절차</h2>
  <p>새 호스트에서:</p>
  <pre class="diff-block">mkdir -p vibe-coder/vibe-coder-data
cd vibe-coder
tar xzf vibe-workspace-YYYYMMDD-HHmm.tar.gz -C vibe-coder-data/
# .env / compose.yml / pg dump 별도 복원 후
docker compose up -d</pre>
  <p class="hint">tar 안의 path 는 workspace.root 기준 상대경로 — 다른 머신에서 같은 상대구조로 풀면 동작.</p>
</div>
"""
    )
}
