# Agent Development Guide

This repository is a Ktor/PostgreSQL server for Android project orchestration.
Treat it as a live development environment: Docker containers may already be
running, the git worktree may contain unrelated local files, and user data lives
in the workspace/database.

## First Checks

Run these before changing code:

```bash
git status --short --branch
rg --files | head
docker exec vibe-coder-server sh -lc 'curl -fsS http://127.0.0.1:17880/health'
```

Do not remove or revert unrelated files. In particular, `.gradle.bak/` may be
present as an untracked local directory and should be ignored unless the user
explicitly asks otherwise.

## Code Layout

- `server/`: Ktor server, admin SSR UI, REST/WS routes, agent orchestration.
- `shared/`: JVM DTOs and websocket frame contracts.
- `docker/`: Docker image, compose files, entrypoint, rebuild helpers.
- `demo/`: sample Android projects and generated demos.

Important agent integration files:

- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/agent/`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/claude/`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/ws/`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/env/AgentRegistry.kt`

The code still contains historical `claude` names. Do not rename broad packages
or routes casually; many UI/API paths and database rows depend on compatibility.

## Build And Test

Preferred compile/test command:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace eclipse-temurin:17-jdk ./gradlew test --no-daemon
```

For server rebuild/restart, use the helper so the running DB password is
preserved:

```bash
docker/scripts/rebuild-server-from-running-env.sh
```

After restart, verify:

```bash
docker exec vibe-coder-server sh -lc 'curl -fsS http://127.0.0.1:17880/health'
docker logs --tail 120 vibe-coder-server
```

Passing Gradle tests is not enough for agent/LLM changes. Always run at least
one live container/API test that exercises the user-visible path.

## Git Discipline

Make small, atomic commits. Stage only the files you changed:

```bash
git diff -- <path>
git add <path>
git commit -m "scope: concise change description"
```

Never run destructive git commands such as `git reset --hard` or
`git checkout -- <file>` unless the user explicitly requested that exact
operation. If the worktree has unrelated changes, leave them alone.

## ACP / Vibe Notes

The current local LLM path uses `mistral-vibe` / `vibe-acp` pointed at a
llama.cpp OpenAI-compatible endpoint. This is not Claude Code. Claude-specific
phrases such as `Use the <agent> sub-agent` may trigger Vibe's own `task` tool
instead of the server-side subagent path.

When adapting Claude behavior to ACP:

- Prefer explicit runtime instructions over Claude-specific incantations.
- Validate the exact ACP JSON-RPC method names and response schemas against the
  installed package in the container, not memory.
- Check native Vibe logs under
  `/workspace/.vibecoder/agent/vibe-home/logs/session/`.
- A REST `{"ok":true}` only means the prompt was accepted. Confirm tool events,
  final assistant output, and process cleanup.
- If a tool call remains `in_progress`, inspect both `conversation_turns` and
  Vibe `messages.jsonl` before changing prompts.

Useful inspection commands:

```bash
docker exec vibe-coder-server sh -lc 'grep -R "CLIENT_METHODS" -n /opt/pipx/venvs/mistral-vibe/lib/python*/site-packages/acp | head'
docker exec vibe-coder-server sh -lc 'find /workspace/.vibecoder/agent/vibe-home/logs/session -type f -printf "%T@ %p\n" | sort -nr | head'
docker exec vibe-coder-postgres psql -U vibecoder -d vibecoder -c "select turn_idx, role, tool_name, left(content, 1200), ts from conversation_turns order by ts desc limit 20;"
```

## Live Subagent Smoke Test

Use a temporary Bearer token and remove it when done. Prefer `user_id = null`
for legacy admin-mode smoke tests so ACLs do not obscure agent behavior.

1. Create or update a temporary agent file in the running container:

```bash
docker exec vibe-coder-server sh -lc 'mkdir -p /home/vibe/.claude/agents && cat > /home/vibe/.claude/agents/codex-smoke.md <<EOF
---
name: codex-smoke
description: Minimal environment-aware smoke-test agent for ACP bridge validation.
---
Inspect real files and terminal output before answering. Do not invent file names, command output, frameworks, or build instructions.
EOF
chown -R vibe:vibe /home/vibe/.claude/agents'
```

2. Insert a temporary device token. Compute the token hash with `sha256sum`.

```bash
docker exec vibe-coder-postgres psql -U vibecoder -d vibecoder -c "insert into devices(id,name,token_hash,created_at,last_seen_at,user_id,channel) values ('codex-subagent-test','codex-subagent-test','<sha256>', now()::text, now()::text, null, 'app') on conflict (id) do update set token_hash=excluded.token_hash,last_seen_at=excluded.last_seen_at,user_id=null;"
```

3. Reset and prompt the subagent:

```bash
docker exec vibe-coder-server sh -lc 'curl -fsS -X POST -H "Authorization: Bearer <token>" http://127.0.0.1:17880/api/projects/qwen-orbit-lock/agents/codex-smoke/console/new'
docker exec vibe-coder-server sh -lc 'curl -fsS -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" --data "{\"text\":\"Do not edit files. Inspect the real project. Read CLAUDE.md, then run pwd and ls -1. Reply with the actual directory and two real top-level entries.\"}" http://127.0.0.1:17880/api/projects/qwen-orbit-lock/agents/codex-smoke/console/prompt'
```

4. Verify real behavior:

```bash
docker exec vibe-coder-postgres psql -U vibecoder -d vibecoder -c "select turn_idx, role, tool_name, left(content, 1600), ts from conversation_turns where project_id='qwen-orbit-lock' and agent_name='codex-smoke' order by ts desc limit 20;"
docker logs --tail 200 vibe-coder-server
```

5. Cleanup:

```bash
docker exec vibe-coder-server sh -lc 'curl -fsS -X POST -H "Authorization: Bearer <token>" http://127.0.0.1:17880/api/projects/qwen-orbit-lock/agents/codex-smoke/console/cancel'
docker exec vibe-coder-postgres psql -U vibecoder -d vibecoder -c "delete from devices where id='codex-subagent-test';"
docker exec vibe-coder-server sh -lc 'ps -ef | grep vibe-acp | grep -v grep || true'
```

## Demo App Validation

For end-to-end checks, create or use a non-trivial Android project through the
web UI or the same REST routes the UI uses. Ask the agent to make a small game
or utility, then build an APK from the server UI. A useful final validation is:

- the conversation reaches a final assistant response,
- files changed in the project workspace are plausible,
- Gradle build finishes,
- an APK appears in the build detail page,
- server logs have no agent process crashes or stuck tool calls.

Do not claim success from generated code alone; the APK build is the user-visible
artifact.

## Phone UI Navigation Subagents

Use two separate subagents for real-device app validation:

- `phone-ui-navigator`: drives the connected Android device and records a
  turn-by-turn trace.
- `phone-ui-run-summarizer`: compresses the trace into bug reports and
  improvement hypotheses for the main agent.

This split is deliberate. The navigator should act, observe, and keep evidence;
the summarizer should not operate the device or invent missing steps.

Recommended agent files live in the running container at
`/home/vibe/.claude/agents/phone-ui-navigator.md` and
`/home/vibe/.claude/agents/phone-ui-run-summarizer.md`. Recreate them with the
templates below if missing.

### phone-ui-navigator

```markdown
---
name: phone-ui-navigator
description: Drives a connected Android device through a requested app scenario and records a structured visual navigation trace.
---
You are a phone UI navigation subagent. Your job is to test one Android app on a real or emulated device through ADB-backed tools.

Core rules:
- Start from a clean state unless the prompt explicitly asks to preserve state.
- Do not edit source files.
- Do not declare success from code inspection. Use the device screen.
- Use `device_analyze_screenshot` before coordinate-sensitive taps. It provides ADB coordinates; use those coordinates directly.
- If the screen is locked or not in the app, unlock and relaunch before continuing.
- If a tap has no visible effect, do not repeat blindly. Re-analyze the screenshot, state the hypothesis, then try a different target or mark a blocker.
- Respect the requested minimum navigation turns. A navigation turn is: observe screenshot, decide one user action, perform it, observe result.

Required setup sequence:
1. Identify package/activity from the prompt or project build output.
2. Run a clean reset unless told otherwise:
   - `adb shell pm clear <package>`
   - wake/unlock the device
   - launch the app
3. Capture and analyze the initial screen.
4. Execute the app-specific scenario supplied by the main agent/user.

Trace format:
Return a final Markdown report with:
- Device serial, package, app version/build if known.
- Initial reset/launch commands run.
- Minimum turns requested and turns completed.
- A numbered trace. Each turn must include:
  - `screen`: concise visual state
  - `intent`: what user behavior is being tested
  - `action`: exact ADB/tool action, including coordinates
  - `result`: observed visual change
  - `evidence`: screenshot/tool call summary
  - `issue`: none/blocker/bug/usability concern
- Final state: pass/fail/blocked.
- Bugs and suspicious behavior, ordered by severity.
- Coordinates or UI labels that were hard to target.

Stop conditions:
- Stop after the requested minimum turns only if the scenario has been covered.
- Stop early only for hard blockers: app crash, install failure, no connected device, or repeated inability to navigate after two distinct analyzed attempts.
```

### phone-ui-run-summarizer

```markdown
---
name: phone-ui-run-summarizer
description: Summarizes a phone UI navigation trace into actionable bugs, repro steps, and next implementation tasks.
---
You are a UI test trace summarizer. You receive a trace from `phone-ui-navigator` and produce a compact engineering summary for the main agent.

Rules:
- Do not operate the device.
- Do not invent screens, taps, or outcomes not present in the trace.
- Distinguish observed facts from hypotheses.
- Prioritize issues that block the requested user scenario.
- Convert vague visual observations into reproducible steps when possible.

Output format:
- Verdict: pass/fail/blocked/partial.
- Scenario coverage: requested turns vs completed turns, and which user goals were covered.
- High-confidence bugs: each with severity, repro steps, observed result, expected result, and evidence turn numbers.
- Suspected bugs or UX risks: clearly labeled as hypotheses.
- Navigation reliability notes: coordinate mistakes, ambiguous labels, lock screen/app state problems.
- Recommended next code changes, in priority order.
- Recommended next test prompt for another navigator run.

Keep the summary short enough to paste back into the main agent prompt.
```

Suggested main-agent prompt to the navigator:

```text
Reset and launch package <package> on device <serial>. Run this app-specific
scenario: <custom scenario>. Complete at least <N> navigation turns. Use visual
analysis before every tap. Return the structured trace only; do not modify files.
```

Suggested loop:

1. Main agent builds/installs the APK.
2. Main agent starts `phone-ui-navigator` with package, serial, scenario, and
   minimum turns.
3. Main agent sends the navigator trace to `phone-ui-run-summarizer`.
4. Main agent fixes the highest-priority bugs.
5. Repeat with a narrower scenario or higher minimum turn count.

## Known Current Risk

ACP subagents can start and receive direct instructions, and `vibe-acp` can issue
real tool calls. The terminal bridge still needs careful validation: if `bash`
creates a terminal but the turn remains `in_progress`, inspect ACP schemas and
native Vibe logs before changing prompts again.
