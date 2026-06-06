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

## Known Current Risk

ACP subagents can start and receive direct instructions, and `vibe-acp` can issue
real tool calls. The terminal bridge still needs careful validation: if `bash`
creates a terminal but the turn remains `in_progress`, inspect ACP schemas and
native Vibe logs before changing prompts again.
