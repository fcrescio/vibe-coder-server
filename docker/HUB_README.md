# Vibe Coder Server

> **Self-hostable Android development server orchestrating Claude Code,
> Gradle, and Git as child processes.** Single Docker container, browser-only
> operation, optional Android companion client. Spin up on your PC and log in
> to create projects, send prompts, build, and download APKs — no local SDK,
> JDK, or Gradle install on the operator side.

## Quick reference

- **Source**: <https://github.com/siamakerlab/vibe-coder-server>
- **Wiki (Android client guide, REST API, MCP catalog)**:
  <https://github.com/siamakerlab/vibe-coder-server/wiki>
- **Issues**: <https://github.com/siamakerlab/vibe-coder-server/issues>
- **Architectures**: `linux/amd64` (multi-arch builds reserved for milestones).
- **Latest tags (slim)**: `0.43.0`, `latest`
- **Latest tags (full / emulator + noVNC)**: `0.38.0-full`, `full`
- **Base OS**: Ubuntu 26.04 LTS (Resolute Raccoon) since v0.38.0
- **Image size**: ~600 MB (Android SDK / Gradle / MCP packages live in
  bind-mounted volumes — see below). v0.14.0+ runs alongside a small
  `postgres:17-alpine` sidecar container.
- **License**: AGPL-3.0

## Quick start (3 minutes)

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# v0.14.0+: set VIBECODER_DB_PASSWORD in .env — required (compose refuses empty).
# Also tweak PUID/PGID (id -u; id -g) and host port; defaults work otherwise.
${EDITOR:-nano} .env

docker compose up -d            # boots postgres + vibe-coder-server

# 1. Browser → http://<PC IP>:17880/setup  (create the first admin user)
# 2. Build environment → "Install/update all" (Android SDK, ~5-15 min)
# 3. Build environment → Claude login card → pick one of 4 options
# 4. Projects → New project (empty or git clone) → console / build / download
```

> **Upgrading from v0.13.x?** v0.14.0 swaps SQLite for PostgreSQL — fresh
> start required (admin / projects re-created; workspace files preserved).
> See the v0.14.0 entry in
> [CHANGELOG.md](https://github.com/siamakerlab/vibe-coder-server/blob/main/CHANGELOG.md)
> for the exact steps.

## What's in the box (v0.64.0)

**Core**
- **Claude Code CLI orchestration** — one persistent child per project,
  stream-json IO, live console relayed via WebSocket. Cancel a runaway turn
  with the ■ stop button (v0.13.0+); session-id preserved so the next prompt
  resumes via `--resume`.
- **Friendly tool rendering** — Bash, Read, Write, Edit, Glob, Grep,
  TaskCreate/Update, WebSearch/Fetch get readable one-line summaries instead
  of raw JSON.
- **Four Claude auth options** — terminal, file upload, API key, **plus** a
  semi-automatic web OAuth (`script -q` PTY wrap, no xterm.js).
- **Prompt template library** (v0.13.0+) — save reusable prompts at
  `/prompts`, pull them into any console via the ▼ dropdown.
- **General Chat** (v0.13.0+) — `/chat` page runs a project-less Claude
  session (`__scratch__` workspace) with full multi-turn `--resume`.

**Build & deploy**
- **MCP catalog with 60+ servers** in 10 categories — checkbox multi-select,
  per-MCP token form, recommended ★ markers, trust tiers.
- **Build environment one-click installer** — Android SDK / Gradle host
  binary & dependency cache / Node + Claude CLI / MCP packages, persisted
  under one host directory. New projects' `CLAUDE.md` is wired to reuse the
  installed Gradle (no double-download via wrapper).
- **Git clone on project register** — public + private (HTTPS PAT or
  auto-generated ed25519 SSH key).

**Project tooling**
- **In-browser file tree + editor** (v0.13.0+) — `/projects/{id}/tree` browses
  the workspace; `/projects/{id}/view` toggles read-only (syntax-highlighted
  by bundled highlight.js) ↔ Edit (textarea). 1 MB / binary / symlink guards.
- **Settings persistence** (v0.14.0+) — `/settings` writes `server.yml` with
  atomic move + `.bak.<ts>` rotation. Restart needed for `host/port/name`.

**Persistence & security**
- **PostgreSQL backend** (v0.14.0+) — sidecar `postgres:17-alpine`, Exposed +
  Hikari pool.
- **Conversation history** (v0.16.0+) — every prompt / assistant turn / tool
  call written to `conversation_turns`. Browse per-project at
  `/projects/{id}/history` and scratch chat at `/chat/history`.
- **CSRF protection on every SSR POST** (v0.12.4+).
- **IP-based brute-force throttling** (v0.12.4+) — account lock + IP block.
- **Audit log** (v0.15.0+) — `/audit` page with filter + paginate. Every
  operational action (login, project, build, MCP, settings, git, console,
  git commit) recorded with user / IP / result.
- **JSON API parity** — every browser feature is also at `/api/*` with
  Bearer auth, for the Android companion or third-party automation.

**Notifications (v0.17.0+, expanded in v0.21 / v0.27 / v0.29)**
- **SMTP email alerts** on build failure / first success, idle Claude
  session waiting for input, disk / quota thresholds, SSH-key / PAT expiry.
  Configure at `/settings/email`.
- **Slack / Discord / Telegram webhooks** (v0.27.0+) — parallel delivery on
  the same triggers. SSRF whitelist for provider hosts. Configure at
  `/settings/webhook`.
- **Claude usage monitor** (v0.21.0+) — polls `claude /status`, alerts on
  warn/critical thresholds (80% / 95%). Dashboard card.
- **Disk usage monitor** (v0.29.0+) — polls workspace filesystem, alerts at
  85% used. Dashboard card.

**Security & sessions (v0.26.0+)**
- **2FA TOTP** (Google Authenticator / 1Password / Authy compatible) at
  `/2fa`. Zero-dependency RFC 6238 implementation.
- **Session idle timeout** — default 30 min for Bearer and cookie auth, both
  enforced via `device.lastSeenAt`. `security.sessionIdleTimeoutMinutes`.

**Publishing (v0.22.0–v0.23.0)**
- **Play Console upload** (AAB → internal/alpha/beta/production) via
  `google-play-publisher` MCP delegation.
- **TestFlight upload** (.ipa) via `app-store-connect` MCP delegation.
  (vibe-coder does not build iOS itself — externally produced .ipa lands in
  the workspace first.)

**Quality of life (v0.28.0+)**
- **APK signature inspection** — inline `apksigner verify` result on each
  build detail page (schemes v1–v4 + Signer DN + SHA-256).
- **Build cache cleanup** — `/settings/cache` shows per-target size of
  Gradle/Android/npm caches with per-target clear buttons.
- **Project source zip** — `GET /projects/{id}/zip` for one-click source
  backup (excludes build/.git/.gradle).
- **Build history chart** (v0.30.0+) — inline SVG, last 30 builds
  (duration line + status dots + APK size points).
- **Keyboard shortcuts** (v0.30.0+) — `g p/c/h/e/s/a/d/l` + `?` overlay.

**Search & cross-project (v0.30.0–v0.32.0)**
- **`/history`** — cross-project conversation grep (LIKE + role filter +
  excerpt with `<mark>` highlight).
- **`/logs`** — build-log grep across `.vibecoder/<id>/logs/*.log`
  (last 2 MB per file scanned, project filter optional).
- **`/projects/{id}/deps`** — `./gradlew :{module}:dependencies` runner
  + `group:name:version` extraction.

**Claude integration (v0.31.0+)**
- **`/agents`** — `~/.claude/agents/*.md` CRUD UI.
- **Conversation export/import** — JSON envelope (`/projects/{id}/history/export`
  + `.../history/import` multipart). sessionId-level idempotency.
- **Conversation auto-archive** (v0.33.0+) — 30-day inactive sessions
  dumped to `<workspace>/.vibecoder/<id>/archive/session-<sid>.json`
  and pruned from PostgreSQL.
- **Prompt suggestions** — `GET /api/projects/{id}/claude/prompt-suggestions?prefix=…`.

**Env & build files (v0.32.0+)**
- **`/projects/{id}/env-files`** — whitelist-edit `local.properties`,
  `gradle.properties`, `.env`, `build.gradle.kts`, etc.

**Automation (v0.33.0+)**
- **Cron build schedule** — `/projects/{id}/automation` registers
  `HH:MM` / `*:MM` / `*:*` triggers.
- **External build webhook** — `POST /api/webhooks/build/{projectId}` with
  `X-Vibe-Secret-Id` + `X-Vibe-Secret` (TLS expected) + optional
  `X-Vibe-Signature` HMAC.

**Backup & CLI (v0.34.0+)**
- **`/backup`** — streams a tar.gz of the workspace (cache/log dirs and
  `postgres/` excluded). `pg_dump` command rendered inline for DB.
- **`cli/vibe`** — bundled `bash` + `curl` MVP. `vibe login` (handles
  `totp_required`) + projects / status / console / build.

**Code analysis (v0.35.0+)**
- **`/projects/{id}/wrapper`** — Gradle wrapper version + 1-click upgrade.
- **`/projects/{id}/stats`** — LoC / files / size per language.
- **`/code-search`** — workspace-wide grep with `<mark>` highlight.

**Multi-project / multi-agent (v0.36.0+)**
- **`/multi-console?projects=a,b,c`** — up to 6 project consoles in an
  iframe grid (cookie auth flows in automatically).
- **`GET /api/agents`** — list registered `.agents/*.md` for dispatch UI.

**Multi-user / role (v0.37.0+)**
- **`admin_users.role`** — `admin` / `member` distinction. First admin
  always admin; new users default to `member`.
- **`/users`** — admin-only management (create / role-toggle / delete).
  Last-admin demotion + self-deletion blocked.

**Ubuntu 26.04 LTS (v0.38.0+)**
- Slim & `:full` images rebased on `eclipse-temurin:17-{jdk,jre}-resolute`.
  JDK 17.0.19 unchanged. LTS support window through 2031-04.

**PWA + VS Code extension (v0.39.0+)**
- `manifest.json` + service worker → mobile/desktop browsers can
  "install" the admin UI.
- `vscode-extension/` **v0.2.0** (since server v0.43.0): Projects
  TreeView, status bar, live console (WebSocket → Output Channel), 7
  palette commands.

**Roles & access (extended in v0.40.0)**
- `viewer` role added next to `admin` / `member`. Destructive `POST`
  endpoints (project / build / console / git / agents) are blocked at
  the SSR layer for viewers.
- `/audit`, `/settings`, `/backup` now admin-only.

**Agent dispatch UX (v0.41.0+)**
- Console page picker for registered `~/.claude/agents/*.md` — selecting
  injects `Use the <agent-name> sub-agent to ` prefix.

**In-browser noVNC reverse proxy (v0.42.0+)**
- `/emulator/vnc/*` proxies localhost:6080 HTTP + WebSocket through the
  same `vibe_session` cookie (admin only). No host-side 6080 exposure or
  SSH tunnel needed.

**Real multi-agent sub-agent pool (v0.44.0+)**
- `SubAgentSessionManager` spawns a separate Claude child per
  `(projectId, agentName)`. Same workspace, parallel execution —
  e.g. reviewer + frontend + backend all working at once.
- `/projects/{id}/agents` lists registered `.agents/*.md` with live
  status; click to open per-agent console at
  `/projects/{id}/agents/{agent}/console`. Independent WebSocket
  topics and session-id files.
- First prompt auto-injects `Use the <agent> sub-agent to ...` so
  Claude Code's standard dispatch mechanism activates.

**JSON API + WebSocket role guards (v0.45.0+)**
- v0.40.0's `viewer` role enforcement extended past SSR. Mutating
  REST endpoints require `canWrite` (admin/member); server-level
  setup endpoints require `admin`. WebSocket `UserPrompt` /
  `ActionInvoke` from a viewer reply with `viewer_readonly` error
  but keep the read stream alive.

**Web Push (v0.46.0+)**
- Payload-less Web Push, zero external deps. VAPID P-256 keypair
  auto-generated and persisted to `vapid-keys.json` in the workspace.
  Admin page at `/settings/push` to subscribe a browser, list /
  revoke subscriptions, send a test.
- Service worker (`/static/sw.js`) shows generic notifications on
  push events. Integrated with `Notifiers` facade so build / Claude
  usage / disk usage warnings fan out to email + webhook + browser
  in one call.

**Admin guard sweep + `/usage` + Helm chart (v0.47.0+)**
- All `/settings/*` SSR pages now admin-only (email, webhook, cors,
  git-integrations, cache).
- `/usage` admin page renders raw Claude `/status` output with
  `cache`-mentioning lines bolded — surfaces prompt-cache stats the
  moment Anthropic ships them.
- `helm/vibe-coder-server/` minimal viable chart. Single-replica
  Deployment + optional postgres StatefulSet sidecar + optional
  ingress. See chart README inside the source repo.

**WebAuthn / passkey 2FA (v0.48.0+)**
- `webauthn4j` 0.29.1 (only new server dep since v0.46.0; ~600 KB
  with BouncyCastle + Jackson-CBOR transitive).
- `/webauthn` for register / list / delete. Login page exposes a
  "🔑 Passkey 로 로그인" button next to the password form — Touch ID
  / Windows Hello / FIDO2 key works without typing a password.
- `server.webauthn.{rpId, rpName, origin}` config — set to actual
  user-facing hostname (LAN: `vibe.local`).

**Project ACL + sub-agent persistent history (v0.49.0+)**
- `project_acls` table — opt-in restriction. 0 rows for a user →
  every project visible (default); 1+ rows → only those. `admin`
  bypasses. `/users/{userId}/projects` checkbox bulk-replace UI.
- `conversation_turns.agent_name` column (nullable). Sub-agent
  process pool turns (v0.44.0) now persist to PostgreSQL alongside
  the main console — survive container restart.

**Web Push payload encryption (v0.50.0+ — RFC 8291)**
- The payload-less mode from v0.46.0 now has the full ECDH +
  HKDF-SHA256 + AES-128-GCM (`aes128gcm` content-encoding)
  implementation. JDK stdlib only (no BouncyCastle / no
  web-push-java). Notifications carry real title / body / URL — the
  service worker focuses the relevant page on click.

**JSON API + WebSocket ACL completion (v0.51.0+)**
- v0.49.0 made project ACL SSR-only; v0.51.0 extends the same check
  to every mutating per-project REST endpoint
  (`call.requireProjectAcl(...)` returns `403 project_forbidden`)
  and to the console + sub-agent WebSocket handshakes (close with
  `WsFrame.Error("project_forbidden")`). No more bypass via Bearer
  token.

**/history agent_name filter (v0.52.0+)**
- The `agent_name` column added in v0.49.0 is now user-facing. The
  history page gets a dropdown — main only / all / per-agent — and
  every row carries an `@<agent>` badge so sub-agent origin is
  visible at a glance.

**PostgreSQL tsvector + GIN content search (v0.53.0+)**
- The v0.16.0 LIKE-only limitation is finally lifted. A
  `content_tsv` `GENERATED ... STORED` column + GIN index turns
  `Filter.q` into `plainto_tsquery('simple', ?)` matching. Sub-ms
  search on hundreds of thousands of rows. `'simple'` tokenizer is
  language-agnostic (no Korean stemming; richer extensions are
  optional and a future phase).

**Symbol definition lookup (v0.54.0+)**
- Regex-based best-effort "jump to definition" for Kotlin / Java —
  `fun` / `class` / `object` / `interface` / `val` / `var` /
  `typealias`. Zero new deps, ms response. `/projects/{id}/symbols`
  SSR + JSON API `/api/projects/{id}/symbols?name=`. File viewer
  reads `?line=N` to smooth-scroll + flash the target row. Full
  Kotlin LSP (~300 MB image, ~200 MB RAM) intentionally deferred for
  the single-user dev profile.

**Prometheus metrics + per-IP rate limit (v0.55.0+ / v0.56.0+)**
- `/metrics` admin endpoint (zero-deps text exposition v0.0.4) —
  11 gauges + 5 counters covering JVM, project / user / device
  totals, console / sub-agent process pools, push subscriptions,
  build outcomes, rate-limit rejections.
- Token-bucket rate limiter: `api` (cap 120, 2 tok/s) for `/api/`
  + `/ws/`, `auth` (cap 10, 0.2 tok/s) for `/login` paths. Admin
  bypass, `Retry-After` header on 429. Disable with
  `security.rateLimit.enabled: false` behind a reverse proxy.

**WebAuthn passwordless + Helm `:full` (v0.57.0+)**
- `passwordless_only` user flag: when on, password/TOTP login
  returns `401 passkey_required`. Toggle from `/webauthn`.
- Helm `fullImage.enabled=true` swaps to the emulator image,
  mounts `/dev/kvm`, runs privileged, exposes port 6080.

**Build comparison + statistics (v0.58.0+ / v0.59.0+)**
- Per-build "vs previous SUCCESS" card on the detail page — APK
  size + duration delta with color badges.
- Per-project stats card on the builds list — total / success
  rate / avg duration + status sparkline + APK size trend
  (inline SVG, zero new deps).

**Backup automation (v0.60.0+)**
- Cron-driven `BackupScheduler` writes `.vibecoder/backups/<ts>.
  tar.gz` and rotates to keep the most-recent `retentionCount`.
  Default off; opt-in via `backup.enabled: true`. `/backup` page
  now lists scheduled files + "Run now" + per-file download / delete.

**Conversation memo + star + Korean FTS + Cache stats (v0.61.0+ / v0.62.0+ / v0.63.0+)**
- ☆/★ toggle and inline memo on every history turn; filter
  `?starred=1` for bookmarked-only view.
- `pg_trgm` extension + GIN trigram index: non-ASCII queries
  (Korean / Japanese / Chinese / emoji) auto-route to indexed
  `ILIKE %q%`. ASCII queries stay on the v0.53.0 tsvector path.
- `ClaudeStreamParser` reads the Anthropic `usage` object out of
  assistant + result frames. Persisted as `role="usage"` history
  rows; `/usage` page shows a structured card with per-project
  input / output / cache-read / cache-create / hit rate.

**Git + project scaffolding (v0.18.0+)**
- **Git commit + push** wrapped in a single non-interactive endpoint
  (`POST /api/projects/{id}/git/commit` + SSR form). PAT / SSH auth,
  push failure keeps the commit, destructive ops disabled by design.
- **Project templates** (`empty`, `compose-basic`, `compose-mvvm-hilt`,
  `compose-mvvm-room`, `wear-os`, `android-tv`) — each seeds a
  `starterPrompt` for the first Claude console turn.

**Android emulator (v0.19.0 → v0.24.0 lifecycle → v0.25.0 `:full`)**
- `/emulator` page reports KVM availability, AVD inventory, running devices.
- **v0.24.0** added one-click AVD lifecycle (create / launch / stop).
- **v0.25.0** ships a separate `:full` image (~3-4 GB) with `qemu`, Xvfb,
  x11vnc, websockify, noVNC pre-installed. Use `docker/compose.full.yml`
  with `/dev/kvm` passthrough + `group_add KVM_GID` + port `6080` for
  browser-based noVNC mirroring (LAN / SSH tunnel only — no auth on
  port 6080).

## Image layout (~600 MB)

| Layer | Contents | Size |
|---|---|---|
| Ubuntu 24.04 LTS (Noble Numbat) | base | ~30 MB |
| OpenJDK 17 (JRE) | runs the vibe-coder server | ~200 MB |
| Node 20 LTS + Claude Code CLI | Claude child process | ~250 MB |
| git, curl, unzip, jq, tini, gosu, util-linux, sudo | minimal tooling | ~80 MB |
| vibe-coder server (Ktor installDist) | app body | ~50 MB |

**Not bundled** (operator installs into volumes on first run via the
`/env-setup` page):

- Android SDK (~3-4 GB)
- Gradle dependency cache (~1-2 GB on first build)
- Claude OAuth credentials / API key
- MCP servers from the catalog (`npm install -g <pkg>`)
- Playwright browsers (optional, ~300 MB)

## Configuration (`.env`)

| Variable | Default | Description |
|---|---|---|
| `VIBECODER_IMAGE` | `siamakerlab/vibe-coder-server:latest` | Image tag to pull (pin to a specific `0.x.y` for reproducibility) |
| `VIBECODER_POSTGRES_IMAGE` | `postgres:17-alpine` | PG sidecar image (v0.14.0+) |
| **`VIBECODER_DB_PASSWORD`** | (required) | **Must be set.** compose refuses to start with empty value |
| `VIBECODER_DB_HOST` | `postgres` | DB host. Use `host:port` for an external PG |
| `VIBECODER_DB_NAME` / `_USER` | `vibecoder` / `vibecoder` | DB name & user |
| `VIBECODER_DB_SSLMODE` | `disable` | `prefer`/`require`/`verify-ca`/`verify-full` |
| `PUID` / `PGID` | `1000` / `1000` | Match host UID/GID (`id -u` / `id -g`) |
| `VIBE_PORT` | `17880` | Host port to expose |
| `VIBE_DATA_ROOT` | `./vibe-coder-data` | **Unified host directory** holding everything persistent |
| `VIBE_CLAUDE_DIR` | `${VIBE_DATA_ROOT}/claude` | Override to `~/.claude` to share host's Claude auth |
| `VIBECODER_ADMIN_USERNAME` | (unset) | Auto-create admin on first boot |
| `VIBECODER_ADMIN_PASSWORD` | (unset) | Pair with above. Change via `/password` immediately |
| `JAVA_OPTS` | `-Xmx2g …` | JVM heap — tune to host RAM |

### Volume layout (v0.7.0+ — unified)

All persistent data lives in **one host directory** (`./vibe-coder-data/`).
`tar` it and you've backed up everything: workspace + DB + Android SDK +
Gradle + MCP + Playwright + Claude auth.

```
${VIBE_DATA_ROOT}/                          container
─────────────────                           ─────────
├── workspace/                  →  /workspace                       (sources + APKs)
├── postgres/                   →  vibe-coder-postgres : /var/lib/postgresql/data  (v0.14.0+)
├── server/                     →  /data                            (logs + build metadata)
├── dev-tools/
│   ├── android-sdk/            →  /opt/android-sdk                 (3-4 GB)
│   ├── gradle/                 →  /home/vibe/.gradle               (1-2 GB)
│   ├── npm-global/             →  /home/vibe/.local                (MCP packages)
│   ├── npm-cache/              →  /home/vibe/.npm                  (npx cache)
│   ├── playwright/             →  /home/vibe/.cache/ms-playwright  (optional)
│   └── config/                 →  /home/vibe/.config               (tool config)
└── claude/                     →  /home/vibe/.claude               (OAuth / API key / MCP registrations)
```

> **v0.7.0 fixed a data-loss bug.** Pre-0.7.0 stored MCP servers in the
> image's system directory (`/usr/local/lib/node_modules`), so they vanished
> on `docker compose pull && up -d`. v0.7.0+ routes them to a bind mount.
> See the [Upgrade Guide](https://github.com/siamakerlab/vibe-coder-server/wiki/Upgrade-Guide) on the wiki.

### Backup / migrate

Stop the containers (especially `postgres`) before snapshotting to guarantee
file consistency.

```bash
# Snapshot everything (workspace + PostgreSQL data + dev-tools + Claude auth)
docker compose stop
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/
docker compose start

# Or: PostgreSQL logical dump while the server keeps running
docker exec vibe-coder-postgres pg_dump -U vibecoder -F c vibecoder > vibe-pg-$(date +%F).pgdump

# On another machine
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost 'cd ~/vibe-coder && tar xzf vibe-coder-data-*.tar.gz && docker compose up -d'
```

`vibe-coder-data/postgres/` is owned by the `postgres` user inside the
container (UID 70 in alpine images). On the host you may need `sudo` to read
files directly. Either use `tar` with sudo, or do logical `pg_dump` against
the running container.

## Web UI routes (v0.64.0)

All routes sit at the root (no `/admin/*` prefix from v0.4.2+). Bearer
token or session cookie required except `/setup`, `/login`, `/health`.
SSR POST forms carry a CSRF token (v0.12.4+).

| Path | Purpose |
|---|---|
| `/` | Dashboard |
| `/projects` | List + register (empty / clone) |
| `/projects/{id}/console` | Live Claude chat + ▼ template picker + ■ stop |
| `/projects/{id}/builds` | Queue debug build + APK download |
| `/projects/{id}/tree`, `/projects/{id}/view` | File tree + read-only highlight ↔ edit (v0.13.0+) |
| `/projects/{id}/history` | Persistent prompt/response history (v0.16.0+) |
| `/projects/{id}/git` | Read-only status/diff/log + commit & push form (v0.18.0+) |
| `/chat` | General Chat — project-less Claude session (v0.13.0+) |
| `/chat/history` | Scratch-project persistent history (v0.16.0+) |
| `/prompts` | Prompt template CRUD (v0.13.0+) |
| `/env-setup` | Build environment installer |
| `/env-setup/mcp` | MCP catalog (60+ entries) |
| `/env-setup/claude-login` | Semi-automatic web OAuth |
| `/emulator` | Diagnostics + AVD lifecycle (v0.24.0+) + `:full` setup guide (v0.25.0+) |
| `/2fa` | Two-factor TOTP enable / disable (v0.26.0+) |
| `/settings/git-integrations` | PAT + SSH key (admin-only, v0.47.0) |
| `/settings/email` | SMTP config + notification triggers (v0.17.0+; admin-only, v0.47.0) |
| `/settings/webhook` | Slack / Discord / Telegram webhooks (v0.27.0+; admin-only, v0.47.0) |
| `/settings/cache` | Gradle / Android / npm cache size + cleanup (v0.28.0+; admin-only, v0.47.0) |
| `/settings/cors` | Read-only CORS policy viewer (admin-only, v0.47.0) |
| `/settings/push` | Web Push subscriptions + VAPID + test (v0.46.0+) |
| `/audit` | Operational audit log (v0.15.0+) |
| `/projects/{id}/zip` | Source zip download (v0.29.0+) |
| `/projects/{id}/env-files` | Whitelist-edit env / build property files (v0.32.0+) |
| `/projects/{id}/deps` | Gradle dependency tree (v0.32.0+) |
| `/projects/{id}/automation` | Cron schedule + webhook secret management (v0.33.0+) |
| `/history` | Cross-project conversation search (v0.30.0+) |
| `/logs` | Build log grep (v0.32.0+) |
| `/agents` | Custom `.agents/*.md` CRUD (v0.31.0+) |
| `/backup` | Workspace tar.gz backup + restore guide (v0.34.0+) |
| `/projects/{id}/wrapper` | Gradle wrapper version + upgrade (v0.35.0+) |
| `/projects/{id}/stats` | Code statistics (LoC / languages) (v0.35.0+) |
| `/code-search` | Workspace-wide grep (v0.35.0+) |
| `/multi-console` | N-pane multi-project console (v0.36.0+) |
| `/users` | Multi-user / role management (admin only, v0.37.0+; `viewer` added v0.40.0) |
| `/emulator/vnc/*` | noVNC reverse proxy (HTTP + WS; admin only, v0.42.0+) |
| `/projects/{id}/agents` | Sub-agent index (v0.44.0+) — live status + open-console |
| `/projects/{id}/agents/{agent}/console` | Per-agent console — independent Claude child (v0.44.0+) |
| `/usage` | Claude `/status` raw viewer (admin-only, v0.47.0+) |
| `/webauthn` | Passkey (WebAuthn) — register / list / delete (v0.48.0+) |
| `/users/{userId}/projects` | Project ACL editor — admin only (v0.49.0+) |
| `/projects/{id}/symbols` | Symbol definition lookup (regex; Kotlin/Java; v0.54.0+) |
| `/metrics` | Prometheus exposition (admin; v0.55.0+) |
| `/settings`, `/devices`, `/password` | Operations |

## JSON API (v0.64.0 — for clients)

Full reference + curl examples in the
[REST API Reference](https://github.com/siamakerlab/vibe-coder-server/wiki/REST-API-Reference)
wiki. Retrofit interfaces in the
[Android Client Guide](https://github.com/siamakerlab/vibe-coder-server/wiki/Android-Client-Guide).

**v0.64.0 — Android client wire 정렬**: 그동안 `ApiPath` SSOT 밖에서 단독
운영되던 14 endpoint 를 회수 + JSON variant 6개 신규 추가:

- `GET  /api/projects/{id}/history` / `GET /api/chat/history` — history page
  (Bearer 토큰; v0.16 이후 SSR-only 였던 것을 정식 JSON 분리)
- `GET  /api/history/search?q=...` — cross-project search JSON (admin)
- `GET  /api/projects/{id}/history/export` / `POST .../history/import` —
  export/import JSON variant
- `GET  /api/usage` — Anthropic 토큰/캐시 누적 합산
- `POST /api/projects/{id}/history/{turnId}/memo|star` — v0.61.0 endpoint 가
  v0.64.0 부터 Bearer 토큰 dual-auth (헤더 있으면 CSRF skip)

Wire shape align: `ClaudeCredentialsUploadResponseDto`/`GitTokenViewDto` 에
Android-friendly alias 추가, `McpEntryDto.status` 소문자 emit, `ApiErrorCode.RATE_LIMITED`
신규 (v0.56 429 응답).

Highlights:

- `POST /api/auth/login` → Bearer token
- `GET  /api/projects`, `POST /api/projects/register` (with `sourceType=clone`
  or `templateId` for built-in scaffolds, v0.18.0+)
- `POST /api/projects/{id}/claude/console/prompt | new | cancel` (`.../cancel` is v0.13.0+)
- `POST /api/projects/{id}/builds/{buildId}/cancel`
- `GET  /api/projects/{id}/history`, `GET /api/chat/history` (v0.16.0+ —
  paginate the persisted conversation_turns)
- `POST /api/projects/{id}/git/commit` (v0.18.0+ — non-interactive commit & push)
- `GET  /api/prompt-templates` (v0.13.0+ — `PROMPT_TEMPLATES` wire promoted in v0.20.0)
- `POST /api/auth/login` accepts optional `totpCode` (v0.26.0+ — 2FA users
  receive `401 totp_required` until code is supplied)
- `GET  /api/projects/{id}/claude/prompt-suggestions?prefix=...` (v0.31.0+)
- `GET  /projects/{id}/history/export` / `POST .../history/import` (v0.31.0+)
- `POST /api/webhooks/build/{projectId}` (v0.33.0+ — external trigger,
  multi-secret auth: `X-Vibe-Secret-Id` + `X-Vibe-Secret` + optional HMAC)
- `GET  /api/agents` (v0.36.0+ — list `.agents/*.md` for dispatch UI)
- `POST /api/projects/{id}/agents/{agent}/console/prompt | cancel`,
  `GET /api/projects/{id}/agents/active` (v0.44.0+ — real multi-agent
  process pool, one Claude child per agent + WS topic)
- `GET  /api/push/vapid-public-key`, `POST /api/push/subscribe`,
  `DELETE /api/push/subscriptions/{id}` (v0.46.0+ — browser Web Push;
  v0.50.0+ payload-encrypted per RFC 8291 aes128gcm)
- `POST /api/webauthn/register/options | verify`,
  `POST /api/webauthn/assert/options | verify`
  (v0.48.0+ — passkey registration + login; assert mints a fresh
  `vibe_session` cookie + Bearer token)
- `GET  /api/projects/{id}/symbols?name=<symbol>` (v0.54.0+ —
  best-effort Kotlin/Java definition lookup; returns `{hits:[...]}`)
- **Role guards (v0.45.0+)**: mutating REST endpoints require write
  role (admin/member) — viewers get `403 viewer_readonly`. Server-level
  setup endpoints require admin — non-admins get `403 admin_only`.
  WebSocket `UserPrompt`/`ActionInvoke` from a viewer reply with
  `viewer_readonly` error frame but keep the read stream open.
- **Project ACL (v0.49.0+ / v0.51.0+)**: `GET /api/projects` is
  filtered by the caller's ACL; `GET /api/projects/{id}` returns
  `403 project_forbidden` on violation. **v0.51.0** extends the
  check to every mutating per-project REST endpoint and to the
  console + sub-agent WebSocket handshakes (close with
  `WsFrame.Error("project_forbidden")`).
- `GET  /api/env-setup/components`, `POST /api/env-setup/install-all`
- `POST /api/env-setup/claude-auth/upload | api-key`
- `POST /api/env-setup/claude-login/start | submit | cancel`
- `GET  /api/env-setup/mcp`, `POST /api/env-setup/mcp/install | unregister`
- `POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` (multipart — secret upload)
- `GET  /api/settings/git-integrations`, `POST .../register | delete | ssh-keygen`
- WebSocket: `/ws/projects/{id}/console/logs`, `/ws/env-setup/{taskId}/logs`

## Common operations

```bash
docker compose logs -f vibe-coder-server                # tail server logs
docker compose restart vibe-coder-server                # restart
docker exec -it vibe-coder-server bash                  # shell (root)
docker exec -it --user vibe vibe-coder-server bash      # shell (vibe user)
docker exec -it --user vibe vibe-coder-server claude --version

# Upgrade (data preserved)
docker compose pull
docker compose up -d --force-recreate
```

## vibe-doctor (CLI alternative to the web UI)

```bash
docker exec -it vibe-coder-server vibe-doctor                # interactive (recommended)
docker exec -it vibe-coder-server vibe-doctor check          # diagnostics only
docker exec    vibe-coder-server vibe-doctor install         # non-interactive bulk install
docker exec -it vibe-coder-server vibe-doctor android        # Android SDK only
docker exec -it vibe-coder-server vibe-doctor claude         # Claude auth helper
docker exec -it vibe-coder-server vibe-doctor mcp            # prompt-based MCP picker
```

## Troubleshooting

### "Permission denied" — volume permission error
`PUID` / `PGID` don't match host. `id -u; id -g`, update `.env`, then
`docker compose up -d --force-recreate`.

### "Build failed: SDK location not found"
Run the build environment installer (web UI) or `vibe-doctor android`.

### Claude says "Not logged in" but UI shows ✓
Either you ran `claude login` as root (need `--user vibe`) or your token
expired (≥30 days unused — re-login). Diagnostic detects both from v0.6.2+.

### MCP installed but Claude doesn't see it
Make sure `~/.claude/.mcp.json` has the entry. The MCP catalog UI writes
this automatically; manual installations need a hand-edit.

### Windows / WSL2 builds are slow
Keep project sources on the **Linux side of WSL2** (`/home/...`), not
under `/mnt/c/...` (5-20× slower I/O).

Full troubleshooting catalog:
<https://github.com/siamakerlab/vibe-coder-server/wiki/Troubleshooting>

## Security notes

- **LAN-internal only.** Do not expose this port on a public IP. Use VPN
  (Tailscale / WireGuard) or a reverse proxy with HTTPS + auth for remote
  access.
- Admin password policy: ≥ 8 chars, mixed letters + digits.
- Passwords stored as BCrypt cost-12 hashes. Bearer tokens stored as
  hashes too; plaintext returned only at issue.
- 10 failed logins → 15-min account lock (timing-safe).
- All disk operations validated by `PathSafety` (no `..` escape from
  workspace).
- No raw shell endpoint; no web terminal emulator.

Full model: <https://github.com/siamakerlab/vibe-coder-server/wiki/Security-Model>

## Build instructions (maintainer)

```bash
# Regular development push — slim (amd64-only, fast 2-3 min)
docker buildx build --platform linux/amd64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:<ver> \
    -t siamakerlab/vibe-coder-server:latest \
    --push .

# :full (emulator + noVNC) — amd64-only (KVM is host-arch dependent)
docker buildx build --platform linux/amd64 \
    -f docker/Dockerfile.full \
    -t siamakerlab/vibe-coder-server:<ver>-full \
    -t siamakerlab/vibe-coder-server:full \
    --push .

# Milestone multi-arch slim (slow 10-15 min via arm64 emulation)
docker buildx build --platform linux/amd64,linux/arm64 ...
```

Full guide: <https://github.com/siamakerlab/vibe-coder-server/blob/main/docker/README.md>

## Links

- Source code: <https://github.com/siamakerlab/vibe-coder-server>
- Wiki: <https://github.com/siamakerlab/vibe-coder-server/wiki>
- Changelog: <https://github.com/siamakerlab/vibe-coder-server/blob/main/CHANGELOG.md>
- License (AGPL-3.0): <https://github.com/siamakerlab/vibe-coder-server/blob/main/LICENSE>
