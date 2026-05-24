package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import kotlin.io.path.isRegularFile
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
fun Routing.backupRoutes(authDeps: AdminRoutesDeps, workspace: WorkspacePath) {
    get("/backup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val sizes = measureSubdirs(workspace.root)
        call.respondText(renderPage(sess.username, sess.csrf, sizes), ContentType.Text.Html)
    }

    get("/backup/download") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now())
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"vibe-workspace-${ts}.tar.gz\"")
        call.respondOutputStream(ContentType.parse("application/gzip")) {
            runCatching {
                BufferedOutputStream(this).use { buf ->
                    GZIPOutputStream(buf).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            walk(workspace.root, workspace.root, tar)
                        }
                    }
                }
            }.onFailure { log.warn(it) { "backup stream failed: ${it.message}" } }
            log.info { "workspace backup downloaded by ${sess.username}" }
        }
    }
}

private val EXCLUDED_SEGMENTS = setOf(
    ".vibecoder/__scratch__",   // ghost project 의 turn cache 는 백업 가치 낮음
)
private val EXCLUDED_REL_PREFIXES = listOf(
    "dev-tools/gradle/caches",
    "dev-tools/gradle/daemon",
    "dev-tools/npm-cache",
    "dev-tools/playwright",
    "postgres",   // PG data dir — 별도 pg_dump 권장
)
private val EXCLUDED_BASENAMES = setOf(".DS_Store")

private fun shouldExclude(rel: String): Boolean {
    if (EXCLUDED_REL_PREFIXES.any { rel == it || rel.startsWith("$it/") }) return true
    if (rel.contains("/logs/") || rel.endsWith("/logs")) return true
    if (rel.split('/').lastOrNull() in EXCLUDED_BASENAMES) return true
    if (EXCLUDED_SEGMENTS.any { rel.startsWith(it) }) return true
    return false
}

private fun walk(root: Path, base: Path, tar: TarArchiveOutputStream) {
    if (!Files.isDirectory(root)) return
    Files.walk(root).use { stream ->
        stream.forEach { p ->
            if (p == root) return@forEach
            val rel = base.relativize(p).toString().replace('\\', '/')
            if (shouldExclude(rel)) return@forEach
            try {
                val entry = TarArchiveEntry(p.toFile(), rel)
                tar.putArchiveEntry(entry)
                if (p.isRegularFile()) Files.copy(p, tar)
                tar.closeArchiveEntry()
            } catch (e: Throwable) {
                log.debug(e) { "backup skip $rel: ${e.message}" }
            }
        }
    }
}

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

private fun renderPage(username: String, csrf: String?, sizes: List<SubdirSize>): String {
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
