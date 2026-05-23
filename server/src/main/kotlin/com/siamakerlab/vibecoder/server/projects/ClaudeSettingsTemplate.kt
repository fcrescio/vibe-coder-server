package com.siamakerlab.vibecoder.server.projects

/**
 * 신규 프로젝트의 `.claude/settings.json` 기본 템플릿.
 *
 * vibe-coder 환경 특성:
 *  - Claude Code 가 stream-json 모드로 자식 프로세스로 spawn (TTY 없음).
 *  - 사용자 응답은 다음 prompt 메시지로만 들어옴 — turn 내 인터랙션 불가.
 *  - 1인 LAN 격리 컨테이너, 운영자가 직접 책임 (multi-tenant 아님).
 *
 * v0.7.0 정책:
 *  1. `permissions.defaultMode = "bypassPermissions"` — 모든 권한 승인 자동.
 *     vibe-coder 콘솔은 `ask` prompt 를 사용자에게 노출할 채널이 없어,
 *     `ask` 로 두면 세션이 영원히 대기 상태가 됨.
 *  2. **인터랙티브 / hang 가능 명령은 deny** (bypassPermissions 와 동시 적용
 *     가능; deny 가 우선). vim/top/less/tail -f 등이 vibe-coder 콘솔에 떨어지면
 *     무한 출력이 stream-json 을 깨거나 컨테이너 메모리를 폭주시킴.
 *  3. **모든 MCP allow** — 운영자가 vibe-doctor 로 직접 설치/등록한 MCP 만
 *     실제로 존재하므로, 화이트리스트가 별도로 불필요.
 *  4. **env 강제 비대화형** — TERM=dumb, CI=1, NO_COLOR=1, BROWSER="", PAGER=cat,
 *     EDITOR=true. 많은 CLI 도구가 이 환경에서 자동으로 -y 와 동등하게 동작.
 *
 * 사용자가 프로젝트별로 override 하고 싶으면 `.claude/settings.local.json` 에
 * 부분 덮어쓰기 (Claude Code 가 두 파일을 머지). 이 템플릿은 신규 생성 시
 * 1회만 떨어뜨려지고, 이후엔 사용자 자유.
 */
object ClaudeSettingsTemplate {
    const val CONTENT = """{
  "permissions": {
    "defaultMode": "bypassPermissions",
    "deny": [
      "Bash(vim *)",
      "Bash(vi *)",
      "Bash(nano *)",
      "Bash(emacs *)",
      "Bash(top)",
      "Bash(htop)",
      "Bash(less *)",
      "Bash(more *)",
      "Bash(tail -f *)",
      "Bash(adb logcat)",
      "Bash(claude login*)",
      "Bash(claude logout*)",
      "Bash(gh auth login*)",
      "Bash(npm init *)"
    ]
  },
  "env": {
    "TERM": "dumb",
    "CI": "1",
    "NO_COLOR": "1",
    "BROWSER": "",
    "PAGER": "cat",
    "EDITOR": "true",
    "VISUAL": "true",
    "DEBIAN_FRONTEND": "noninteractive"
  }
}
"""
}
