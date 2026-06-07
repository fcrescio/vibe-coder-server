# Port Plan: upstream console render

Target branch: `port/upstream-console-render`

This task ports the useful console rendering improvements from upstream
`origin/main` into this fork, without replacing the fork-specific
`mistral-vibe-acp`, device tools, image handling, or phone UI navigator work.

## Context

This fork intentionally diverges from upstream:

- Local `main` tracks `fcrescio/main`.
- `origin/main` is the upstream project and is far ahead, but it removed or
  rewrote several areas that this fork depends on.
- Do not merge or rebase `origin/main` into this fork.
- Do not cherry-pick broad version commits directly unless the diff has been
  reviewed file by file. Most relevant upstream commits touch files that also
  contain fork-specific behavior.

The goal of this branch is only to improve the web console rendering and UX.

## High-value upstream commits to inspect

Use these as references, not as blind cherry-picks:

- `c7eebfe` - console response-friendly rendering, raw JSON removal.
- `5414b30` - markdown render, long message folding, compact notification,
  thinking raw bug.
- `0e4bdbe` - null-byte fix after markdown/folding regression.
- `7d00afe` - chat bubble UI, service worker cache fix, thinking badge.
- `9d4cdee` - user prompt sticky restore, service worker reload.
- `dc176ee` - markdown table rendering.
- `30ee628` - consistent collapse/expand for all console messages.
- `c907d65` - collapsed tool result expand fix.
- `2aba54d` - copy original text and clip tool results to 8000.
- `580b728` - task progress and nested summaries.
- `7622da0` and `742759b` - code blocks and syntax highlight.
- `fb0e359` - console filters dialog and auto-scroll layout.
- `1bfcaad` - first prompt missing regression fix and memo button layout.
- `132ef8f` and `4d84760` - initial scroll and re-enter scroll fixes.
- `ddffd55` - status chip moved into integrated tab header.

Useful upstream files to inspect:

- `server/src/main/resources/static/admin/console-render.js`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/admin/WebProjectTemplates.kt`
- `server/src/main/resources/static/admin/admin.css`
- `server/src/main/resources/static/admin/sw.js`
- `shared/src/main/kotlin/com/siamakerlab/vibecoder/shared/ws/WsFrame.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/ws/LogHub.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/claude/ConversationHistoryService.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/claude/HistoryContentFormatter.kt`

## Do not port in this branch

Do not touch these areas unless a minimal compatibility edit is unavoidable:

- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/agent/*`
- `docker/vibe-tools/device.py`
- `docker/scripts/patch-mistral-vibe-acp-images.py`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/devices/*`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/adb/*`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/claude/SubAgent*`
- `AGENTS.md`

Upstream deletes or restructures several of those paths. This fork needs them.

Also avoid porting these feature families in this branch:

- archive/restore
- memos
- notifications
- keystore/AdMob/publishing
- emulator/KVM/device package restructuring
- MCP/plugin/skill marketplace
- prompt automation

Create separate branches for those later.

## Required preservation

The current fork already supports:

- image attachments in the console prompt request DTO;
- rendering user-attached images;
- rendering tool-result images from `device_screencap` and
  `device_analyze_screenshot`;
- sanitized large image/tool payloads in persisted history;
- `vibe-acp` console sessions and subagents;
- phone UI navigator output and `adbCoordinates`.

The port must preserve all of these. In particular, do not regress:

- `PromptRequestDto.images`
- `ConsoleRoutes` image validation and forwarding
- `WebProjectTemplates.kt` image upload UI
- `extractToolImage(...)`
- `renderImageToolResult(...)`
- `renderUserPrompt(...)`

If upstream's renderer is moved into `static/admin/console-render.js`, make sure
these image paths are integrated into the new renderer rather than deleted.

## Suggested approach

1. Create a branch:

   ```bash
   git switch main
   git switch -c port/upstream-console-render
   ```

2. Inspect upstream console files without applying them:

   ```bash
   git show origin/main:server/src/main/resources/static/admin/console-render.js > /tmp/upstream-console-render.js
   git show origin/main:server/src/main/kotlin/com/siamakerlab/vibecoder/server/admin/WebProjectTemplates.kt > /tmp/upstream-WebProjectTemplates.kt
   git show origin/main:server/src/main/resources/static/admin/admin.css > /tmp/upstream-admin.css
   ```

3. Prefer extracting renderer logic into
   `server/src/main/resources/static/admin/console-render.js` if this can be
   done without a large rewrite. Otherwise keep the current inline script and
   port smaller functions manually.

4. Port in small commits:

   - message markdown and code/table rendering;
   - long message folding/expand;
   - tool result clipping/copy original;
   - thinking/status visual treatment;
   - filter dialog/autoscroll refinements;
   - scroll position fixes;
   - CSS cleanup.

5. After each small group, run the server tests. Avoid a single huge commit.

## Compatibility checks while coding

Check these manually in the diff before running tests:

- No references to upstream-only routes or DTO fields were introduced.
- No deletion of fork-specific image functions.
- No deletion of phone UI/device tool rendering.
- The renderer handles both live WebSocket frames and replayed history rows.
- Tool result rendering still handles:
  - plain text;
  - JSON strings;
  - image payload JSON with `data` + `mimeType`;
  - sanitized history where large base64 data is omitted.
- Copy buttons copy readable original text, not truncated HTML.
- Collapsed messages can be expanded.
- Auto-scroll does not fight the user when they are scrolled up.

## Automated verification

Run:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace eclipse-temurin:17-jdk ./gradlew :server:test --no-daemon
```

Also run a compile-only pass if tests are slow during iteration:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace eclipse-temurin:17-jdk ./gradlew :server:compileKotlin --no-daemon
```

## Live verification

Rebuild/restart using the project script:

```bash
docker/scripts/rebuild-server-from-running-env.sh
docker exec vibe-coder-server sh -lc 'curl -fsS http://127.0.0.1:17880/health'
```

Then verify from the web UI, not only through REST:

1. Open an existing project console.
2. Send a normal text prompt.
3. Send a prompt with one attached image.
4. Ask for a device screenshot or visual check so that
   `device_screencap` / `device_analyze_screenshot` returns an image result.
5. Reload the page and confirm history replay renders the same messages.
6. Confirm markdown renders:
   - headings
   - bullet lists
   - fenced code blocks
   - tables
7. Confirm long tool results fold and expand.
8. Confirm copy buttons work for assistant output and tool output.
9. Confirm autoscroll:
   - stays at bottom while live output arrives if already at bottom;
   - does not jump to bottom if the user scrolls up;
   - jump-to-bottom control appears when appropriate.
10. Confirm image attachments and image tool results do not overflow on mobile width.

## Device/LLM smoke check

Use the existing device if available:

```bash
docker exec vibe-coder-server sh -lc 'adb -H host.docker.internal -P 5037 devices -l'
```

If device `0123456789ABCDEF` is connected, run a short navigator flow from the
UI or via API:

- launch one installed demo app;
- request one screenshot analysis;
- verify the image result is visible in the console;
- verify the final report is markdown-rendered;
- reload and verify history replay.

Do not spend time creating new Android apps in this branch.

## Expected deliverables

- A branch named `port/upstream-console-render`.
- Several focused commits, not one broad upstream merge.
- A short final note listing:
  - upstream commits/features ported;
  - intentionally skipped upstream items;
  - automated tests run;
  - live UI checks run;
  - known remaining gaps.

## Merge criteria

This branch is mergeable only if:

- `:server:test` passes;
- image prompt send still works;
- device image tool result rendering still works;
- history replay still renders images and markdown;
- no fork-specific ACP/device/subagent files are removed or overwritten;
- working tree is clean.
