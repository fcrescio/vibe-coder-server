package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}
private val wsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

/**
 * v1.6.0 — Workspace terminal routes.
 *
 * REST:
 *   POST   /api/terminal/sessions     — 신규 bash PTY spawn → { sessionId, workdir }
 *   GET    /api/terminal/sessions     — 활성 session 목록
 *   DELETE /api/terminal/sessions/{id} — 강제 종료
 *
 * SSR:
 *   GET /settings/terminal            — xterm.js + WS 연결 페이지
 *
 * WS:
 *   /ws/terminal/{sessionId}          — 양방향 (TerminalInput/Output/Resize/Exit)
 *
 * 모든 라우트는 `security.allowTerminal=true` 필수. 미설정 시 404.
 */
fun Routing.terminalRoutes(
    authDeps: AdminRoutesDeps,
    manager: TerminalSessionManager,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
) {
    if (!authDeps.config.security.allowTerminal) {
        log.info { "Terminal routes disabled (security.allowTerminal=false). Skipping registration." }
        return
    }

    // SSR
    get("/settings/terminal") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            TerminalTemplates.page(sess.username, csrf = sess.csrf, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    // REST
    post(ApiPath.TERMINAL_SESSIONS) {
        val s = manager.create()
        call.respond(mapOf("sessionId" to s.id, "workdir" to s.workdir))
    }

    get(ApiPath.TERMINAL_SESSIONS) {
        val list = manager.list().map {
            mapOf(
                "sessionId" to it.id,
                "workdir" to it.workdir,
                "createdAt" to it.createdAt.toString(),
                "alive" to it.isAlive(),
            )
        }
        call.respond(mapOf("sessions" to list))
    }

    delete("/api/terminal/sessions/{id}") {
        val id = call.parameters["id"].orEmpty()
        manager.close(id)
        call.respond(HttpStatusCode.NoContent)
    }

    // WS bidirectional
    webSocket("/ws/terminal/{id}") {
        val id = call.parameters["id"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing id"))
        // auth: 첫 frame 이 WsFrame.Auth 여야 함.
        val firstFrame = incoming.tryReceive().getOrNull() ?: incoming.receiveCatching().getOrNull()
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no auth"))
        val authParsed = runCatching {
            (firstFrame as? Frame.Text)?.readText()?.let {
                wsJson.decodeFromString(WsFrame.serializer(), it)
            }
        }.getOrNull()
        val token = (authParsed as? WsFrame.Auth)?.token
        if (token == null || deviceRepo.findByTokenHash(tokens.hashOf(token)) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))
            return@webSocket
        }
        val sess = manager.get(id) ?: run {
            close(CloseReason(CloseReason.Codes.NORMAL, "session not found"))
            return@webSocket
        }

        coroutineScope {
            // server → client: PTY stdout → TerminalOutput frame.
            val outJob = launch {
                sess.output.collect { data ->
                    runCatching {
                        send(Frame.Text(wsJson.encodeToString(WsFrame.serializer(), WsFrame.TerminalOutput(data))))
                    }
                }
            }
            // exit 도 한 번 보내고 종료.
            val exitJob = launch {
                sess.exit.collect { code ->
                    runCatching {
                        send(Frame.Text(wsJson.encodeToString(WsFrame.serializer(), WsFrame.TerminalExit(code))))
                    }
                    close(CloseReason(CloseReason.Codes.NORMAL, "exited"))
                }
            }
            // client → server: input / resize.
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val parsed = runCatching {
                        wsJson.decodeFromString(WsFrame.serializer(), frame.readText())
                    }.getOrNull() ?: continue
                    when (parsed) {
                        is WsFrame.TerminalInput -> sess.write(parsed.data)
                        is WsFrame.TerminalResize -> sess.resize(parsed.cols, parsed.rows)
                        else -> Unit
                    }
                }
            } finally {
                outJob.cancel()
                exitJob.cancel()
            }
        }
    }
}

internal object TerminalTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(username: String, csrf: String?, lang: String = "en"): String {
        val t = { key: String -> Messages.t(lang, key) }
        return AdminTemplates.shell(
            title = t("term.title"),
            username = username,
            currentPath = "/settings/terminal",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("term.title"))}</h1>
    <a href="/settings" class="chip chip-link">${esc(t("term.backToSettings"))}</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">${esc(t("term.intro"))}</p>
</header>

<!-- xterm.js: BSD-licensed terminal emulator. CDN 사용 (jsdelivr). -->
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/css/xterm.min.css">

<div id="term-host" style="background:#000;padding:10px;border-radius:8px;height:70vh;min-height:400px"></div>
<div id="term-status" class="dim" style="font-size:12px;margin-top:6px">${esc(t("term.status.connecting"))}</div>

<script src="https://cdn.jsdelivr.net/npm/@xterm/xterm@5.5.0/lib/xterm.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/@xterm/addon-fit@0.10.0/lib/addon-fit.min.js"></script>
<script>
(function(){
  var status = document.getElementById('term-status');
  function setStatus(s){ status.textContent = s; }

  var term = new Terminal({
    cursorBlink: true,
    convertEol: true,
    fontFamily: 'ui-monospace, Menlo, Consolas, monospace',
    fontSize: 13,
    theme: { background: '#000', foreground: '#e5e5e5', cursor: '#e5e5e5' },
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('term-host'));
  fit.fit();

  // 1) session 생성.
  fetch('/api/terminal/sessions', { method: 'POST', credentials: 'same-origin' })
    .then(function(r){ return r.json(); })
    .then(function(s){
      setStatus('${esc(t("term.status.connectingWs"))} ' + s.sessionId);
      var token = (document.cookie.match(/vibe_session=([^;]+)/) || [])[1];
      var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      var ws = new WebSocket(proto + '//' + location.host + '/ws/terminal/' + s.sessionId);
      ws.onopen = function(){
        ws.send(JSON.stringify({ type: 'auth', token: token || '' }));
        setStatus('${esc(t("term.status.connected"))} ' + s.sessionId);
        fit.fit();
        ws.send(JSON.stringify({ type: 'terminal_resize', cols: term.cols, rows: term.rows }));
      };
      ws.onmessage = function(ev){
        try {
          var f = JSON.parse(ev.data);
          if (f.type === 'terminal_output') term.write(f.data);
          else if (f.type === 'terminal_exit') {
            term.write('\r\n\r\n[process exited code=' + f.exitCode + ']');
            setStatus('${esc(t("term.status.exited"))}');
          }
        } catch(e){}
      };
      ws.onclose = function(){ setStatus('${esc(t("term.status.disconnected"))}'); };
      term.onData(function(d){
        if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'terminal_input', data: d }));
      });
      window.addEventListener('resize', function(){
        fit.fit();
        if (ws.readyState === 1) {
          ws.send(JSON.stringify({ type: 'terminal_resize', cols: term.cols, rows: term.rows }));
        }
      });
    })
    .catch(function(e){ setStatus('error: ' + e); });
})();
</script>
""",
        )
    }
}
