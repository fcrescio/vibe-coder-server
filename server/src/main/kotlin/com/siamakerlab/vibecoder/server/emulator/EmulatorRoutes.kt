package com.siamakerlab.vibecoder.server.emulator

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v0.19.0 — `/emulator` 진단 페이지.
 *
 * 본 cycle 은 read-only 진단 + 수동 setup 가이드. AVD launch 자동화 + noVNC
 * 미러는 v0.20+ scope (별도 image variant 검토).
 */
fun Routing.emulatorRoutes(authDeps: AdminRoutesDeps, svc: EmulatorService) {
    get("/emulator") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val d = svc.diagnose()
        call.respondText(EmulatorTemplates.page(sess.username, d, sess.csrf), ContentType.Text.Html)
    }
}

private object EmulatorTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(username: String, d: EmulatorService.Diagnostics, csrf: String?): String {
        val badge = { ok: Boolean, label: String ->
            if (ok) """<span class="ok">✓ $label</span>"""
            else """<span class="warn">✗ $label</span>"""
        }
        val avdsHtml = if (d.avds.isEmpty())
            """<p class="dim">(설치된 AVD 없음 — <code>avdmanager create avd</code> 로 생성)</p>"""
        else d.avds.joinToString("") { """<li><code>${esc(it)}</code></li>""" }
            .let { """<ul style="margin:6px 0 0 20px">$it</ul>""" }

        val devicesHtml = if (d.runningDevices.isEmpty())
            """<p class="dim">(실행 중인 device/emulator 없음)</p>"""
        else d.runningDevices.joinToString("") { """<li><code>${esc(it)}</code></li>""" }
            .let { """<ul style="margin:6px 0 0 20px">$it</ul>""" }

        return AdminTemplates.shell(
            title = "Android Emulator",
            username = username,
            currentPath = "/emulator",
            csrf = csrf,
            body = """
<header>
  <h1>Android Emulator <small class="dim" style="font-size:14px;font-weight:400">v0.19.0 — 진단 + 가이드</small></h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">
    실 emulator 자동 launch + noVNC 미러는 v0.20+ 에서 별도 이미지 variant
    (<code>siamakerlab/vibe-coder-server:full</code>) 로 제공 예정. 본 cycle 은
    진단 + 수동 setup 가이드 + ADB 통합.
  </p>
</header>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">컨테이너 환경 진단</h2>
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 14px;margin:0">
    <dt class="dim">KVM (/dev/kvm)</dt><dd>${badge(d.kvmAvailable, if (d.kvmAvailable) "사용 가능" else "사용 불가")}</dd>
    <dt class="dim">emulator binary</dt><dd>${if (d.emulatorBinary != null) """<code>${esc(d.emulatorBinary)}</code>""" else """<span class="warn">없음 — sdkmanager 'emulator'</span>"""}</dd>
    <dt class="dim">adb binary</dt><dd>${if (d.adbBinary != null) """<code>${esc(d.adbBinary)}</code>""" else """<span class="warn">없음</span>"""}</dd>
    <dt class="dim">설치된 AVD</dt><dd>$avdsHtml</dd>
    <dt class="dim">실행 중인 device</dt><dd>$devicesHtml</dd>
  </dl>
</div>

<div class="card" style="margin-bottom:14px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
  <h2 style="margin-top:0">권장 사항</h2>
  <pre class="diff-block" style="margin:0">${esc(d.recommendation)}</pre>
</div>

<div class="card">
  <h2 style="margin-top:0">수동 launch — 컨테이너 안에서</h2>
  <p class="dim" style="font-size:13px">현재는 컨테이너 안 터미널에서 emulator 를 직접 실행하고,
  본 페이지의 "실행 중인 device" 에서 확인. 이후 APK install 은 콘솔에서 Claude 에게 부탁:</p>

  <h3 style="margin-top:14px">1. AVD 생성 (한 번만)</h3>
  <pre class="diff-block">docker exec -it --user vibe vibe-coder-server bash
${'$'} cmdline-tools/latest/bin/sdkmanager 'system-images;android-35;google_apis;x86_64'
${'$'} cmdline-tools/latest/bin/avdmanager create avd -n test -k 'system-images;android-35;google_apis;x86_64'
exit</pre>

  <h3 style="margin-top:14px">2. emulator 시작 (KVM 있으면 빠름, 없으면 software 모드)</h3>
  <pre class="diff-block">docker exec -d --user vibe vibe-coder-server \
  bash -c '${'$'}ANDROID_HOME/emulator/emulator -avd test -no-window -no-audio &amp;'</pre>
  <p class="hint">no-window 는 GUI 없이 (headless). adb 통신만 가능. GUI 미러는 v0.20+ noVNC.</p>

  <h3 style="margin-top:14px">3. 빌드된 APK 설치 (콘솔에서 Claude 에게)</h3>
  <pre class="diff-block">/projects/{id}/builds 에서 APK 다운로드 또는 콘솔:
> 최근 디버그 APK 를 실행 중인 emulator 에 설치하고 앱을 실행해 첫 화면 확인해줘.</pre>
</div>

<div class="card" style="margin-top:14px;background:rgba(80,150,255,0.05)">
  <h2 style="margin-top:0">Roadmap — v0.20+ "full" 이미지</h2>
  <ul style="font-size:13px;line-height:1.7">
    <li>별도 image <code>siamakerlab/vibe-coder-server:full</code> 에 qemu + KVM
      + noVNC + websockify 사전 설치 (~+800MB).</li>
    <li>EmulatorService 자동 launch (avd 자동 생성, system-image lazy download).</li>
    <li><code>/emulator/{avd}</code> 에 noVNC iframe — 브라우저에서 화면 확인.</li>
    <li>빌드 페이지에 "build → install to emulator → screenshot" 원클릭 버튼.</li>
  </ul>
</div>
"""
        )
    }
}
