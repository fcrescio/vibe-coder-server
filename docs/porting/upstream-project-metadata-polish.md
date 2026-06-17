# Port Plan: upstream project metadata polish

Target branch: `port/upstream-project-metadata-polish`

Goal: port the low-risk upstream improvements that make project lists and cards
more informative without touching the ACP/runtime/device work in this fork.

## Upstream References

Inspect, do not blindly cherry-pick:

- `42cb5e6` - Kotlin/Flutter project type badges.
- `838fc73` - type badge as separate project-list column.
- `a4e48ad` and `f105442` - applicationId/package detector fixes.
- `596c1f4` - versionName static resolver.
- `1b5d0fc` - Flutter app icon recognition.
- `e292873` - project list icon resize/cache/ETag.

Useful upstream files:

- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/projects/AppIconCache.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/projects/PackageNameDetector.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/projects/VersionNameResolver.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/projects/ProjectService.kt`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/admin/WebProjectTemplates.kt`
- `server/src/main/resources/static/admin/admin.css`

## Local Constraints

This fork has ACP/device additions that upstream does not have. Do not touch:

- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/agent/*`
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/devices/*`
- `docker/vibe-tools/*`
- `docker/scripts/patch-mistral-vibe-*.py`

Keep this branch focused on detection/rendering only.

## Suggested Scope

1. Add or port small pure services:
   - app icon discovery/cache;
   - package/applicationId detector fixes;
   - versionName resolver.
2. Add project list/card fields only where existing DTO/template patterns already
   support it, or keep the change SSR-only if DTO churn grows.
3. Add CSS for compact badges/icons.
4. Add tests for detector/resolver edge cases.

## Verification

Run:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace eclipse-temurin:17-jdk ./gradlew :server:test --no-daemon
```

Manual UI check:

- Open project list.
- Confirm Android/Kotlin projects still show and open normally.
- Confirm an app with variable/interpolated `versionName` does not render `vv...`.
- Confirm missing icon falls back cleanly.
- Confirm no ACP/device console behavior changed.

## Merge Criteria

- `:server:test` passes.
- No changes under agent/device/docker ACP paths.
- Project list remains usable on narrow/mobile widths.
