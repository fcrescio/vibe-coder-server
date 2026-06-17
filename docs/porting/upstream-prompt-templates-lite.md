# Port Plan: upstream prompt templates lite

Target branch: `port/upstream-prompt-templates-lite`

Goal: port the useful prompt-template improvements that help local LLM workflows,
without taking upstream's automation/scheduling stack.

## Upstream References

Inspect, do not broad cherry-pick:

- `8fd9cca` - prompt template feature improvements.
- `6f09da4` - category header duplication fix.
- `4555729` - rail prompt history list/fade behavior.
- `59d623c` - prompt broadcast, only for UX ideas; do not port broadcast here.
- `ebbb9ae` / `f9f2c1b` - scheduled prompts, only for future planning.

Useful upstream files:

- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/prompts/PromptRoutes.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/prompts/PromptTemplateStore.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/prompts/PromptTemplates.kt`
- `server/src/main/resources/static/admin/prompt-templates.js`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/admin/WebProjectTemplates.kt`

## Local Constraints

Do not add scheduling, broadcast, or persistent automation in this branch. Those
touch concurrency/session policy and should be designed around `vibe-acp`.

## Suggested Scope

1. Fix prompt template category rendering if still present locally.
2. Improve template picker UX and prompt history display in the console rail.
3. Keep storage format backward compatible.
4. Add a short test for category/group serialization if store behavior changes.

## Verification

Run:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace eclipse-temurin:17-jdk ./gradlew :server:test --no-daemon
```

Manual UI checks:

- Create/edit/delete a prompt template.
- Confirm categories do not duplicate after repeated open/close.
- Insert a template into console prompt.
- Confirm normal prompt send still uses the existing ACP endpoint.

## Merge Criteria

- Template store backward compatibility preserved.
- No prompt scheduling/broadcast side effects.
- `:server:test` passes.
