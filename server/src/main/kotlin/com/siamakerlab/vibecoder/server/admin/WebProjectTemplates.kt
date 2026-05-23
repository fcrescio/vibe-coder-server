package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.repo.ArtifactRow
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.ProjectDto

/**
 * 프로젝트 / 콘솔 / 빌드 SSR 템플릿. v0.5.0 Phase 2 추가.
 *
 * 안드로이드 앱 없이도 브라우저만으로 프로젝트 등록 -> Claude 프롬프트 ->
 * Gradle 빌드 -> APK 다운로드까지 완결되도록 하는 화면들.
 *
 * AdminTemplates.kt 와 동일한 `shell()` 레이아웃 셸 + admin.css 를 공유한다.
 */
object WebProjectTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    // ────────────────────────────────────────────────────────────────────
    // /projects — 목록 + 등록 폼
    // ────────────────────────────────────────────────────────────────────

    fun projectsPage(
        username: String,
        projects: List<ProjectDto>,
        flashErr: String? = null,
        flashOk: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val rowsHtml = if (projects.isEmpty()) {
            """<tr><td colspan="4" class="dim">등록된 프로젝트가 없습니다. 오른쪽 폼으로 새로 만드세요.</td></tr>"""
        } else {
            projects.joinToString("\n") { p ->
                val statusBadge = when (p.lastBuildStatus) {
                    "SUCCESS" -> """<span class="ok">SUCCESS</span>"""
                    "FAILED", "TIMEOUT" -> """<span class="warn">${esc(p.lastBuildStatus)}</span>"""
                    "RUNNING", "PENDING" -> """<span>${esc(p.lastBuildStatus)}</span>"""
                    null -> """<span class="dim">-</span>"""
                    else -> """<span>${esc(p.lastBuildStatus)}</span>"""
                }
                """<tr>
                    <td><a href="/projects/${esc(p.id)}"><strong>${esc(p.name)}</strong><br><small class="dim">${esc(p.id)}</small></a></td>
                    <td><code>${esc(p.packageName)}</code></td>
                    <td>$statusBadge</td>
                    <td><a href="/projects/${esc(p.id)}/console" class="primary-link" style="width:auto;display:inline-block;padding:6px 12px">콘솔 열기</a></td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "프로젝트",
            username = username,
            currentPath = "/projects",
            body = """
<header><h1>프로젝트</h1></header>
$okHtml
$errHtml

<section class="grid" style="grid-template-columns: 2fr 1fr">
  <div class="card">
    <h2>등록된 프로젝트</h2>
    <table class="devices">
      <thead>
        <tr><th>이름 / ID</th><th>패키지</th><th>최근 빌드</th><th></th></tr>
      </thead>
      <tbody>
        $rowsHtml
      </tbody>
    </table>
  </div>

  <div class="card">
    <h2>새 프로젝트</h2>
    <form method="post" action="/projects">
      <label>프로젝트 ID (kebab-case)
        <input name="projectId" required pattern="[a-z0-9][a-z0-9._-]*" maxlength="64"
               placeholder="my-android-app">
      </label>
      <label>앱 이름 (사람이 읽는 이름)
        <input name="appName" required maxlength="80" placeholder="My Android App">
      </label>
      <label>패키지명 (applicationId)
        <input name="packageName" required pattern="[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+"
               placeholder="com.example.myapp">
      </label>
      <button type="submit" class="primary">생성</button>
      <p class="hint">서버가 워크스페이스에 빈 폴더 + <code>CLAUDE.md</code> 템플릿을 생성합니다.
      이후 콘솔에서 Claude 에게 "Android 앱을 만들어줘" 같은 프롬프트를 주면 됩니다.</p>
    </form>
  </div>
</section>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id} — 상세 (요약 + 하위 페이지 링크)
    // ────────────────────────────────────────────────────────────────────

    fun projectDetailPage(
        username: String,
        p: ProjectDto,
        recentBuilds: List<BuildDto>,
        flashErr: String? = null,
        flashOk: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val recentRows = if (recentBuilds.isEmpty()) {
            """<tr><td colspan="3" class="dim">아직 빌드 이력이 없습니다.</td></tr>"""
        } else {
            recentBuilds.joinToString("\n") { b ->
                """<tr>
                    <td><code>${esc(b.id.take(12))}</code></td>
                    <td>${esc(b.status.name)}</td>
                    <td>${esc(b.startedAt)}</td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = esc(p.name),
            username = username,
            currentPath = "/projects",
            body = """
<header>
  <h1>${esc(p.name)} <small class="dim" style="font-size:14px;font-weight:400">${esc(p.id)}</small></h1>
</header>
$okHtml
$errHtml

<section class="grid">
  <div class="card">
    <h2>요약</h2>
    <dl>
      <dt>패키지</dt><dd><code>${esc(p.packageName)}</code></dd>
      <dt>소스 경로</dt><dd><code>${esc(p.sourcePath)}</code></dd>
      <dt>모듈</dt><dd>${esc(p.moduleName)}</dd>
      <dt>Debug task</dt><dd><code>${esc(p.debugTask)}</code></dd>
      <dt>최근 빌드</dt><dd>${esc(p.lastBuildStatus ?: "-")}</dd>
      <dt>업데이트</dt><dd>${esc(p.updatedAt)}</dd>
    </dl>
  </div>

  <div class="card">
    <h2>작업</h2>
    <p><a href="/projects/${esc(p.id)}/console" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px">콘솔 / Claude 프롬프트 →</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/builds" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px">빌드 / APK →</a></p>
    <form method="post" action="/projects/${esc(p.id)}/delete" style="margin-top:24px"
          onsubmit="return confirm('정말 삭제하시겠습니까? 워크스페이스 폴더는 그대로 남고 DB 항목만 제거됩니다.')">
      <button type="submit" class="danger" style="width:100%">프로젝트 삭제 (메타데이터만)</button>
    </form>
  </div>

  <div class="card">
    <h2>최근 빌드 (5건)</h2>
    <table class="devices">
      <thead><tr><th>ID</th><th>상태</th><th>시작</th></tr></thead>
      <tbody>$recentRows</tbody>
    </table>
  </div>
</section>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/console — Claude 콘솔
    // ────────────────────────────────────────────────────────────────────

    fun consolePage(
        username: String,
        p: ProjectDto,
        sessionId: String?,
        isAlive: Boolean,
    ): String {
        val statusBadge = when {
            isAlive -> """<span class="ok">running</span>"""
            sessionId != null -> """<span class="dim">idle (will resume)</span>"""
            else -> """<span class="dim">no session</span>"""
        }
        val projectIdJs = esc(p.id)

        return AdminTemplates.shell(
            title = "${esc(p.name)} · 콘솔",
            username = username,
            currentPath = "/projects",
            body = """
<header>
  <h1>콘솔
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>세션:</strong> $statusBadge
      ${if (sessionId != null) """ <span class="dim">${esc(sessionId.take(12))}…</span>""" else ""}
    </div>
    <div style="display:flex;gap:8px">
      <a href="/projects/${esc(p.id)}" class="primary-link" style="width:auto;display:inline-block;padding:6px 12px;background:transparent;border:1px solid var(--border);color:var(--text-dim)">← 프로젝트로</a>
      <form method="post" action="/projects/${esc(p.id)}/console/new" style="display:inline"
            onsubmit="return confirm('현재 세션을 종료하고 새 대화를 시작할까요?')">
        <button type="submit" class="danger">새 세션 시작</button>
      </form>
    </div>
  </div>
</div>

<div id="console-log" class="console-log" aria-live="polite"></div>

<form id="prompt-form" class="prompt-form" autocomplete="off">
  <textarea id="prompt-input" rows="3" maxlength="65536" placeholder="Claude 에게 보낼 프롬프트를 입력하세요. Ctrl+Enter 로 전송.&#10;예) Android 빈 프로젝트를 생성하고 Compose 로 'Hello' 화면을 띄워줘." required></textarea>
  <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px">
    <small class="dim">전송: Ctrl+Enter · 줄바꿈: Enter</small>
    <button type="submit" class="primary" id="send-btn" style="width:auto;padding:8px 16px">전송</button>
  </div>
</form>

<script>
(function() {
  var projectId = "$projectIdJs";
  var logEl = document.getElementById('console-log');
  var form = document.getElementById('prompt-form');
  var input = document.getElementById('prompt-input');
  var sendBtn = document.getElementById('send-btn');

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function append(cls, label, body) {
    var atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 10;
    var row = document.createElement('div');
    row.className = 'log-line ' + cls;
    row.innerHTML = '<span class="log-label">' + escHtml(label) + '</span><span class="log-body">' + escHtml(body) + '</span>';
    logEl.appendChild(row);
    if (atBottom) logEl.scrollTop = logEl.scrollHeight;
  }

  function renderFrame(f) {
    var t = f.type;
    if (t === 'console_session_started') {
      append('sys', 'session', 'started ' + (f.sessionId || '').slice(0,12) + (f.model ? ' · ' + f.model : ''));
    } else if (t === 'console_assistant') {
      append('assistant', 'assistant', f.text || '');
    } else if (t === 'console_tool_use') {
      var inp = typeof f.input === 'string' ? f.input : JSON.stringify(f.input);
      append('tool', f.toolName || 'tool', inp.length > 500 ? inp.slice(0,500) + '…' : inp);
    } else if (t === 'console_tool_result') {
      var out = typeof f.output === 'string' ? f.output : JSON.stringify(f.output);
      append(f.isError ? 'tool-err' : 'tool-out', f.isError ? 'tool-err' : 'tool-out',
             out.length > 500 ? out.slice(0,500) + '…' : out);
    } else if (t === 'console_error') {
      append('err', 'error', (f.code || '') + ': ' + (f.message || ''));
    } else if (t === 'console_done') {
      append('sys', 'done', f.reason || 'end_turn');
    } else if (t === 'console_system') {
      append('sys', f.code || 'system', f.message || '');
    } else if (t === 'console_replay_begin') {
      append('sys', 'replay', 'history begin (' + f.fromSeq + ' → ' + f.toSeq + ')');
    } else if (t === 'console_replay_end') {
      append('sys', 'replay', 'history end — live frames follow');
    }
  }

  var ws = null;
  var wsAuthed = false;

  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/console/logs');

    ws.onopen = function() {
      append('sys', 'ws', 'connected; sending auth');
      // 인증: WS 첫 프레임으로 {type:"auth", token} 전송. 서버는 쿠키도 토큰 운반체로
      // 받아들이므로 같은 vibe_session 쿠키 값을 그대로 사용.
      var token = (document.cookie.match(/(?:^| )vibe_session=([^;]+)/) || [])[1] || '';
      ws.send(JSON.stringify({type: 'auth', token: token}));
    };

    ws.onmessage = function(ev) {
      try {
        var f = JSON.parse(ev.data);
        // 서버는 인증 성공 시 별도 응답 없이 바로 frame을 보낸다.
        // 실패 시엔 type=error + CloseReason 으로 응답 후 close.
        if (f.type === 'error') { append('err', 'ws', (f.code || '') + ': ' + (f.message || '')); return; }
        renderFrame(f);
      } catch (e) {
        append('err', 'parse', String(e));
      }
    };

    ws.onclose = function(ev) {
      append('sys', 'ws', 'closed (code ' + ev.code + '); 재연결 5초 후');
      setTimeout(connect, 5000);
    };

    ws.onerror = function() {
      append('err', 'ws', 'error');
    };
  }

  connect();

  async function sendPrompt(text) {
    sendBtn.disabled = true;
    try {
      var res = await fetch('/api/projects/' + projectId + '/claude/console/prompt', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({text: text}),
      });
      if (!res.ok) {
        var msg = await res.text();
        append('err', 'send', res.status + ' ' + msg);
      } else {
        append('user', 'user', text);
        input.value = '';
      }
    } catch (e) {
      append('err', 'send', String(e));
    } finally {
      sendBtn.disabled = false;
      input.focus();
    }
  }

  form.addEventListener('submit', function(ev) {
    ev.preventDefault();
    var text = input.value.trim();
    if (text) sendPrompt(text);
  });

  input.addEventListener('keydown', function(ev) {
    if ((ev.ctrlKey || ev.metaKey) && ev.key === 'Enter') {
      ev.preventDefault();
      form.requestSubmit();
    }
  });
})();
</script>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/builds — 빌드 목록 + APK 다운로드
    // ────────────────────────────────────────────────────────────────────

    fun buildsPage(
        username: String,
        p: ProjectDto,
        builds: List<BuildDto>,
        artifactsByBuild: Map<String, ArtifactRow>,
        flashErr: String? = null,
        flashOk: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val rowsHtml = if (builds.isEmpty()) {
            """<tr><td colspan="5" class="dim">아직 빌드가 없습니다. 위 버튼으로 첫 빌드를 시작하세요.</td></tr>"""
        } else {
            builds.joinToString("\n") { b ->
                val art = artifactsByBuild[b.id]
                val downloadCell = if (art != null) {
                    val sizeKb = (art.sizeBytes + 512L) / 1024L
                    """<a href="/api/projects/${esc(p.id)}/artifacts/${esc(art.id)}/download" class="primary-link" style="width:auto;display:inline-block;padding:4px 10px">APK · ${sizeKb}KB</a>"""
                } else {
                    """<span class="dim">-</span>"""
                }
                val statusCls = when (b.status.name) {
                    "SUCCESS" -> "ok"
                    "FAILED", "TIMEOUT" -> "warn"
                    "RUNNING", "PENDING" -> ""
                    else -> "dim"
                }
                """<tr>
                    <td><code>${esc(b.id.take(12))}</code></td>
                    <td><span class="$statusCls">${esc(b.status.name)}</span></td>
                    <td>${esc(b.startedAt)}</td>
                    <td>${esc(b.finishedAt ?: "-")}</td>
                    <td>$downloadCell</td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · 빌드",
            username = username,
            currentPath = "/projects",
            body = """
<header>
  <h1>빌드
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>
$okHtml
$errHtml

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>모듈:</strong> ${esc(p.moduleName)} · <strong>Task:</strong> <code>${esc(p.debugTask)}</code>
    </div>
    <div style="display:flex;gap:8px">
      <a href="/projects/${esc(p.id)}" class="primary-link" style="width:auto;display:inline-block;padding:6px 12px;background:transparent;border:1px solid var(--border);color:var(--text-dim)">← 프로젝트로</a>
      <form method="post" action="/projects/${esc(p.id)}/builds" style="display:inline">
        <button type="submit" class="primary" style="width:auto;padding:8px 16px">Debug 빌드 큐 등록</button>
      </form>
    </div>
  </div>
  <p class="hint">큐 등록 후엔 콘솔에서 실시간 로그를 볼 수 있으며, 완료되면 APK 다운로드 링크가 이 표에 나타납니다.</p>
</div>

<table class="devices">
  <thead>
    <tr><th>빌드 ID</th><th>상태</th><th>시작</th><th>종료</th><th>APK</th></tr>
  </thead>
  <tbody>$rowsHtml</tbody>
</table>
"""
        )
    }
}

