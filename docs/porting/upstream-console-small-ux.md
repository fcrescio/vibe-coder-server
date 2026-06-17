# Port Plan: upstream console small UX improvements

Target branch: `port/upstream-console-small-ux`

Goal: port small console usability fixes that are independent from upstream's
large Claude/session rewrites.

## Upstream References

Inspect these commits as references:

- `df2962c` - history image count / `[image]` marker / tab viewer support.
- `e25dc06` - image attachment UI dialog cleanup.
- `7a3e9ea` - show "more" only at console scroll top.
- `8d2a5ff` - console message paging, duplicate removal, autoscroll redesign.
- `dca7233` - project combo busy-first ordering.
- `f41e9e9` - timestamp display cleanup.
- `6f09da4` - prompt template combo duplicate category header fix.

Useful upstream files:

- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/admin/WebProjectTemplates.kt`
- `server/src/main/resources/static/admin/console-render.js`
- `server/src/main/resources/static/admin/admin.css`
- `server/src/main/resources/static/admin/project-tabs.js`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/claude/HistoryRoutes.kt`
- `shared/src/main/kotlin/com/siamakerlab/vibecoder/shared/dto/HistoryDtos.kt`

## Local Constraints

This fork already has image-in-chat and device screenshot rendering. Preserve:

- `PromptRequestDto.images`
- image upload/send UI;
- `extractToolImage(...)`
- `renderImageToolResult(...)`
- `renderUserPrompt(...)`
- device screenshot and visual analysis rendering.

Do not port upstream session/runtime code in this branch.

## Suggested Scope

1. Start with pure frontend/template polish:
   - image attachment dialog usability;
   - timestamp cleanup;
   - busy-first project selector ordering;
   - prompt template category duplicate fix.
2. Only then consider message paging/autoscroll if it can be patched locally
   without replacing the fork's renderer.
3. Keep history DTO changes optional. If DTO changes touch Android wire
   compatibility, split them into a separate branch.

## Verification

Run:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace eclipse-temurin:17-jdk ./gradlew :server:test --no-daemon
```

Live UI checks:

- Send a text prompt.
- Send a prompt with an image.
- Trigger `device_screencap` or visual device analysis and confirm image render.
- Reload page and confirm history replay still renders images.
- Confirm autoscroll does not jump when the user is scrolled up.

## Merge Criteria

- No regression in image prompt send.
- No regression in device image tool result rendering.
- No broad replacement of `console-render.js` unless reviewed separately.
