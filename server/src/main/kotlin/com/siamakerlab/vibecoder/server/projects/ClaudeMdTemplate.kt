package com.siamakerlab.vibecoder.server.projects

object ClaudeMdTemplate {
    const val CONTENT = """# CLAUDE.md — Vibe Coder Android Project Rules

## Project Rules

- This is an Android project managed through Vibe Coder.
- Use Kotlin Android SDK.
- Prefer Jetpack Compose and Material 3 when UI changes are required.
- Keep architecture clean and maintainable.
- Use MVVM + Repository pattern when adding new features.
- Do not place business logic directly in Activity or Composable.
- Avoid unnecessary dependencies.
- Preserve existing package structure unless explicitly requested.
- Before finishing coding tasks, check for obvious build errors.

## Build Rules

- Use Gradle Wrapper only.
- On Windows, use gradlew.bat.
- On Linux/macOS, use ./gradlew.
- Debug build task is assembleDebug unless the project config says otherwise.

## Response Rules

- Summarize modified files.
- Summarize important implementation decisions.
- Mention whether build was executed.
- If build failed, explain the likely cause and next step.

## Non-Interactive Environment (CRITICAL)

Vibe Coder runs Claude as a **non-interactive child process** behind a web/mobile
UI. The user CANNOT respond to TUI prompts, arrow-key menus, stdin reads, or any
in-stream interactive widget. Treat every turn as one-shot.

- DO NOT use AskUserQuestion, interactive selection menus, or any tool/affordance
  that requires the user to press a key inside an active session.
- DO NOT call CLI commands that wait on stdin (e.g. `npm init` without `-y`,
  `gh auth login` interactive flow, `claude login`).
- DO NOT enter watch / REPL / TUI modes (`gradle --console=plain` is fine,
  `./gradlew --watch-fs` interactive is not).
- DO NOT pause and ask "should I continue?". Either proceed with a sensible
  default, or list the question(s) at the end of your response so the user
  answers in the **next prompt**.
- Long-running commands must complete and return (no `tail -f`, no `adb logcat`
  without a clear stop condition).

When you need user input or a decision:

1. State the question(s) inline at the end of the response.
2. Show 2~3 concrete options labeled (A), (B), (C) with one-line trade-offs.
3. Suggest a default (marked "권장 / Recommended").
4. Stop. The user replies in the next prompt with their choice.

### 한국어 요약

vibe-coder 환경은 인터랙티브 입력이 불가능합니다. 화살표 키 선택, stdin
응답, TUI/REPL 진입은 모두 작동하지 않습니다.

- 사용자 확인이 필요하면 응답 마지막에 (A)(B)(C) 옵션과 권장안을 적어
  두세요. 사용자는 **다음 프롬프트**에서 선택해 보냅니다.
- 대기하는 명령(`adb logcat`, `gradle --watch-fs`, 인터랙티브 `claude login`
  등)은 절대 호출하지 마세요.
- 한 턴은 항상 자기완결적이어야 합니다.
"""
}
