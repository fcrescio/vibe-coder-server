package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.ComponentState
import com.siamakerlab.vibecoder.server.env.ComponentStatus
import com.siamakerlab.vibecoder.server.env.SetupComponent

/**
 * 빌드환경 페이지 SSR 템플릿.
 *
 * v0.6.0 Phase A — 상태 카드 + 사용자 절차 안내.
 * v0.6.1 Phase B — 카드별 원클릭 설치 버튼 + "모두 설치/업데이트" 일괄 버튼
 *   + 진행 페이지 (실시간 WS 로그).
 *
 * AdminTemplates.shell() 레이아웃을 공유한다 (좌측 nav 의 "빌드환경" 메뉴와 동일 경로).
 */
object EnvSetupTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    fun envSetupPage(username: String, states: List<ComponentState>): String {
        val cards = states.joinToString("\n") { renderCard(it) }
        return AdminTemplates.shell(
            title = "빌드환경",
            username = username,
            currentPath = "/env-setup",
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">빌드환경</h1>
    <form method="post" action="/env-setup/install-all" style="display:inline"
          onsubmit="return confirm('자동 설치 가능한 모든 컴포넌트(Android SDK / MCP 등)를 순차로 설치/업데이트합니다. 진행 페이지로 이동합니다. 계속할까요?')">
      <button type="submit" class="primary" style="width:auto;padding:8px 18px">⚡ 모두 설치/업데이트</button>
    </form>
  </div>
</header>

<div class="card" style="margin-bottom:16px">
  <h2>처음 사용하시나요?</h2>
  <p>도커 이미지는 의도적으로 슬림화되어 있어, 안드로이드 빌드에 필요한
  컴포넌트는 컨테이너 첫 부팅 후 사용자가 직접 다운로드해야 합니다.</p>
  <ol style="margin:8px 0 0 20px;line-height:1.8">
    <li><strong>이미지 내장 컴포넌트</strong> (JDK / Git / Node / Claude CLI) 는 이미 설치되어 있으므로 그대로 두세요.</li>
    <li><strong>Claude 로그인</strong> 은 OAuth 가 필요해 터미널에서 한 번만 <code>docker exec -it vibe-coder claude login</code> 실행. (자동화 불가)</li>
    <li>위 우측 <strong>"모두 설치/업데이트"</strong> 버튼 또는 카드 개별 버튼으로 Android SDK / MCP 를 설치. 진행은 실시간 로그로 확인.</li>
    <li>설치가 모두 ✓ 로 바뀌면 <a href="/projects">/projects</a> 로 이동해 첫 프로젝트를 만들고 콘솔에서 Claude 에게 안드로이드 앱 생성을 부탁하세요.</li>
  </ol>
  <p class="hint">에뮬레이터(AVD) 는 LAN-only PoC 도구 특성상 기본 제공하지 않습니다. 실 디바이스(USB / 무선 ADB) 또는 호스트 PC 의 Android Studio 에뮬레이터를 추천합니다.</p>
</div>

<div class="card" style="margin-bottom:16px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
  <h2 style="color:var(--ok)">✅ 설치한 빌드환경은 이미지 pull 후에도 보존됩니다</h2>
  <p>Android SDK / Gradle 캐시 / Claude 인증은 <strong>Docker named volume</strong> 또는
  <strong>호스트 bind mount</strong> 에 저장되므로, 새 이미지로 서버를 업그레이드해도
  사라지지 않습니다.</p>
  <pre class="diff-block">docker pull siamakerlab/vibe-coder-server:&lt;새 버전&gt;
docker compose up -d --force-recreate</pre>
  <p class="hint">⚠️ <code>docker compose down -v</code> 는 named volume 까지 삭제합니다 (SDK 3~4GB 재다운로드). 일반 업그레이드 시 사용하지 마세요. 자세한 내용은 README 의 "빌드환경은 이미지를 갈아끼워도 보존됩니다" 섹션 참고.</p>
</div>

<section class="grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr))">
  $cards
</section>
"""
        )
    }

    private fun renderCard(s: ComponentState): String {
        val c = s.component
        // CLAUDE_AUTH 만 "로그인됨/로그인 필요" 로 표기, 나머지는 설치됨/미설치.
        val (badgeCls, badgeText) = badgeFor(c, s.status)
        val actionHtml = renderAction(c, s.status)
        return """<div class="card">
  <div style="display:flex;justify-content:space-between;align-items:start;gap:8px">
    <h2 style="margin-bottom:8px">${esc(c.displayName)}</h2>
    <span class="$badgeCls" style="white-space:nowrap;font-size:12px">${esc(badgeText)}</span>
  </div>
  <p class="dim" style="font-size:12px;margin:0 0 8px">${esc(c.sizeHint)}</p>
  <p style="font-size:13px;line-height:1.5">${esc(c.description)}</p>
  <p style="font-size:12px;color:var(--text-dim);margin:8px 0 0">${esc(s.message)}</p>
  $actionHtml
</div>"""
    }

    private fun badgeFor(c: SetupComponent, status: ComponentStatus): Pair<String, String> =
        if (c == SetupComponent.CLAUDE_AUTH) {
            when (status) {
                ComponentStatus.INSTALLED -> "ok" to "✓ 로그인됨"
                ComponentStatus.PARTIAL -> "warn" to "△ 부분 인증"
                ComponentStatus.MISSING -> "warn" to "✗ 로그인 필요"
                ComponentStatus.UNKNOWN -> "dim" to "?"
            }
        } else {
            when (status) {
                ComponentStatus.INSTALLED -> "ok" to "✓ 설치됨"
                ComponentStatus.PARTIAL -> "warn" to "△ 일부 설치"
                ComponentStatus.MISSING -> "warn" to "✗ 미설치"
                ComponentStatus.UNKNOWN -> "dim" to "?"
            }
        }

    private fun renderAction(c: SetupComponent, status: ComponentStatus): String {
        return when (c) {
            // 이미지 내장 — 진단 실패 시에만 경고. 정상이면 액션 없음.
            SetupComponent.JAVA,
            SetupComponent.GIT,
            SetupComponent.NODE,
            SetupComponent.CLAUDE_CLI ->
                if (status == ComponentStatus.INSTALLED) ""
                else """<p class="hint" style="margin-top:8px">⚠ 이미지 내장 컴포넌트인데 진단 실패. 컨테이너 재기동 또는 이미지 재pull 을 시도하세요.</p>"""

            // Claude 로그인 — OAuth 라 자동화 불가. 명령 안내만.
            SetupComponent.CLAUDE_AUTH -> {
                if (status == ComponentStatus.INSTALLED) ""
                else """<details style="margin-top:8px" open><summary class="dim" style="cursor:pointer;font-size:12px">로그인 방법</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder claude login</pre>
                  <p class="hint">OAuth 콜백을 위해 터미널에서 직접 실행해 주세요. 완료 후 이 페이지를 새로고침. (자동화 불가)</p>
                </details>"""
            }

            // Android SDK — 원클릭 설치 + 진행 페이지.
            SetupComponent.ANDROID_SDK -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> "재설치 / 업데이트"
                    ComponentStatus.PARTIAL -> "이어서 설치"
                    else -> "설치"
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                        onsubmit="return confirm('Android SDK 설치를 시작합니다 (3~4GB, 5~15분). 진행 페이지로 이동합니다. 계속할까요?')">
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                <details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">CLI 로 직접 실행하려면</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder vibe-doctor android</pre>
                </details>"""
            }

            SetupComponent.PLATFORM_TOOLS ->
                """<p class="hint" style="margin-top:8px">Android SDK 설치에 포함됩니다. 위 "Android SDK" 카드의 설치 버튼을 사용하세요.</p>"""

            SetupComponent.MCP_DEFAULTS ->
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                       onsubmit="return confirm('기본 MCP 묶음 설치를 시작합니다. 진행 페이지로 이동합니다. 계속할까요?')">
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">MCP 설치</button>
                </form>"""
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 진행 페이지 (/env-setup/tasks/{taskId})
    // ────────────────────────────────────────────────────────────────────

    fun taskProgressPage(username: String, taskId: String): String {
        val safeId = esc(taskId)
        return AdminTemplates.shell(
            title = "설치 진행",
            username = username,
            currentPath = "/env-setup",
            body = """
<header>
  <h1>설치 진행 <small class="dim" style="font-size:14px;font-weight:400">$safeId</small></h1>
</header>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
    <div>
      <strong>상태:</strong> <span id="job-status" class="dim">대기 중</span>
      · <span id="job-lines" class="dim">0 줄</span>
    </div>
    <a href="/env-setup" class="chip chip-link">← 빌드환경</a>
  </div>
  <div class="progress-bar" style="margin-top:12px">
    <div id="progress-fill" class="progress-fill"></div>
  </div>
  <p class="hint" id="progress-hint" style="margin-top:8px">설치 작업의 정확한 종료 시점을 미리 알 수 없으므로 진행도는 라인 수 기반의 추정치입니다.</p>
</div>

<div class="card">
  <h2>실시간 로그</h2>
  <div id="job-log" class="console-log" aria-live="polite"></div>
</div>

<script>
(function() {
  var taskId = "$safeId";
  var logEl = document.getElementById('job-log');
  var statusEl = document.getElementById('job-status');
  var linesEl = document.getElementById('job-lines');
  var progressEl = document.getElementById('progress-fill');
  var hintEl = document.getElementById('progress-hint');
  var lineCount = 0;

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
    lineCount += 1;
    linesEl.textContent = lineCount + ' 줄';
    // 라인 수 기반 progress: 1000 라인을 100% 로 보정 (saturating).
    var pct = Math.min(100, Math.round(lineCount / 10));
    progressEl.style.width = pct + '%';
  }
  function classOfLevel(level) {
    if (level === 'ERROR' || level === 'STDERR') return 'err';
    if (level === 'WARN') return 'tool';
    if (level === 'STDOUT') return 'assistant';
    if (level === 'INFO') return 'sys';
    return 'sys';
  }
  function renderFrame(f) {
    if (f.type === 'log') {
      append(classOfLevel(f.level), f.level, f.message);
    } else if (f.type === 'done') {
      var ok = f.status === 'SUCCESS';
      statusEl.textContent = f.status + (f.errorMessage ? ' · ' + f.errorMessage : '');
      statusEl.className = ok ? 'ok' : 'warn';
      progressEl.style.width = '100%';
      progressEl.className = 'progress-fill ' + (ok ? 'done-ok' : 'done-fail');
      hintEl.innerHTML = ok
        ? '✅ 완료 — <a href="/env-setup">빌드환경 페이지</a>로 돌아가 다음 단계를 확인하세요.'
        : '✗ 실패 — 위 로그에서 원인을 확인 후 다시 시도하세요.';
      append(ok ? 'sys' : 'err', 'done', f.status + (f.errorMessage ? ' · ' + f.errorMessage : ''));
    } else if (f.type === 'error') {
      append('err', 'ws', (f.code || '') + ': ' + (f.message || ''));
    }
  }
  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var ws = new WebSocket(proto + '//' + location.host + '/ws/env-setup/' + taskId + '/logs');
    ws.onopen = function() {
      statusEl.textContent = '연결됨, 작업 대기 중…';
      append('sys', 'ws', 'connected');
    };
    ws.onmessage = function(ev) {
      try { renderFrame(JSON.parse(ev.data)); }
      catch (e) { append('err', 'parse', String(e)); }
    };
    ws.onclose = function(ev) { append('sys', 'ws', 'closed (' + ev.code + ')'); };
    ws.onerror = function() { append('err', 'ws', 'error'); };
  }
  connect();
})();
</script>
"""
        )
    }

    /** POST 실패 시 inline 으로 안내. */
    fun errorBlurb(message: String): String =
        """<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>오류</title>
        <link rel="stylesheet" href="/static/admin.css"></head><body class="layout no-nav">
        <main class="content"><div class="auth-card"><h1>설치 시작 실패</h1>
        <div class="error">${esc(message)}</div>
        <a href="/env-setup" class="primary-link">← 빌드환경으로</a></div></main></body></html>"""
}
