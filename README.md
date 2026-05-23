# Vibe Coder — Server

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/siamakerlab/vibe-coder-server)](https://hub.docker.com/r/siamakerlab/vibe-coder-server)

> **Standalone Docker app.** A self-hostable Android development server that
> drives Claude Code, Gradle, and Git as child processes — accessible from any
> browser, no client install required. Spin up one container on your PC and
> log in to create projects, send prompts, build, and download APKs.

This repository contains the server body (Ktor backend) and the operations
web UI. An Android companion app (`vibe-coder-android`, separate repo) is an
optional client that points at the same server — every feature works in the
browser alone.

## Repository layout

```
vibe-coder-server/
├─ shared/              # JVM library — @Serializable DTOs / ApiPath / WsFrame
├─ server/              # Ktor server (Netty), PostgreSQL via Exposed,
│                       # Claude/Gradle/Git child processes, WS log hub,
│                       # Admin web UI (SSR HTML)
└─ docker/              # Slim Docker image + compose + vibe-doctor
```

## What's inside (v0.15.0)

### Core orchestration
- **Claude Code CLI orchestration** — one persistent child process per project,
  stream-json on stdin/stdout, console log relayed live over WS. Cancel a
  runaway turn with the ■ stop button (v0.13.0+); session-id is preserved so the
  next prompt resumes the same conversation.
- **Friendly tool rendering** — `Bash`, `Read`, `Write`, `Edit`, `Glob`, `Grep`,
  `TaskCreate/Update`, `WebSearch/Fetch` etc. each get a one-line readable
  representation in the console instead of raw JSON.
- **Four Claude auth options** — terminal, file upload, API key, **plus** a
  semi-automatic web OAuth (`script -q` PTY wrap, no xterm.js).
- **Prompt template library** (v0.13.0+) — save reusable prompts at `/prompts`,
  pull them into any console via the ▼ dropdown. JSON-backed, max 500.
- **General Chat** (v0.13.0+) — `/chat` page runs a project-less Claude
  session in a synthetic `__scratch__` workspace. Full multi-turn conversation
  with `--resume`, identical UX to the project console.

### Build & deploy
- **MCP catalog** — 60+ Model Context Protocol servers in 10 categories,
  checkbox multi-select, per-MCP token form, recommended ★, trust tiers.
- **Build environment one-click installer** — Android SDK / Gradle binary &
  cache / Node + Claude CLI / MCP packages, all persisted under one host
  directory. New project `CLAUDE.md` is wired to use the installed Gradle to
  avoid redundant wrapper downloads (v0.14.1+).
- **Git clone on project register** — public / private (HTTPS PAT or SSH key)
  with auto-generated ed25519 key pair.

### Project tooling
- **In-browser file tree + editor** (v0.13.0+) — `/projects/{id}/tree` browses
  the workspace; `/projects/{id}/view` opens read-only / edit toggle with
  syntax highlighting via bundled highlight.js (Kotlin / Java / XML / JSON /
  YAML / Markdown / properties / shell). 1 MB / binary / symlink guards.
- **Settings persistence** (v0.14.0+) — `/settings` writes `server.yml` with
  atomic move + `.bak.<ts>` rotation (keeps 5). Restart required for
  host/port/name; other fields take effect on next read.

### Persistence & security
- **PostgreSQL backend** (v0.14.0+) — sidecar `postgres:17-alpine` container,
  Exposed ORM + Hikari pool, JSONB-ready for future history features.
- **CSRF protection on every SSR POST** (v0.12.4+) — HMAC-SHA256 deterministic
  derivation from the device cookie. REST API (Bearer header) is exempt.
- **IP-based brute-force throttling** (v0.12.4+) — account lock at 10 fails /
  15 min, IP block at 30 fails / 24 h. Timing-safe dummy verify on missing users.
- **Audit log** (v0.15.0+) — every operational action (login / device revoke /
  project / build / MCP / settings / git token / console new-cancel) lands in
  `audit_log` with user, IP, result, ts. `/audit` page with filter + paginate.
- **JSON API parity** — every admin UI feature is also exposed under `/api/*`
  with Bearer authentication for the Android companion app.

## Quick start (Docker, 3 minutes)

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# Edit .env — REQUIRED: set VIBECODER_DB_PASSWORD to a strong value (v0.14.0+).
# Also: PUID/PGID (id -u; id -g), host port — defaults work for the rest.
${EDITOR:-nano} .env

docker compose up -d            # starts postgres + vibe-coder-server

# 1. Open http://<PC IP>:17880/setup in a browser to create the admin user.
# 2. Go to "Build environment" → click "Install/update all".
#    (Android SDK download, 3-4GB, 5-15 min.)
# 3. Build environment → "Claude login" card → pick option 0/1/2/3.
```

> **v0.14.0+** ships a sidecar PostgreSQL container (`postgres:17-alpine`). The
> `VIBECODER_DB_PASSWORD` env var is mandatory — compose refuses to start with an
> empty value. Upgrading from v0.13.x is a fresh start (admin / projects
> re-created; workspace files preserved on disk). See the v0.14.0 entry in
> [CHANGELOG.md](CHANGELOG.md) for the exact steps.

## Minimum `docker-compose.yaml` (write your own)

```yaml
name: vibe-coder
services:
  postgres:
    image: postgres:17-alpine
    container_name: vibe-coder-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: vibecoder
      POSTGRES_USER: vibecoder
      POSTGRES_PASSWORD: ${VIBECODER_DB_PASSWORD:?VIBECODER_DB_PASSWORD must be set}
    volumes:
      - ./vibe-coder-data/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U vibecoder -d vibecoder"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s

  vibe-coder-server:
    image: siamakerlab/vibe-coder-server:latest
    container_name: vibe-coder-server
    restart: unless-stopped
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      PUID: "1000"       # id -u
      PGID: "1000"       # id -g
      TZ: "Asia/Seoul"
      JAVA_OPTS: "-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"
      # PostgreSQL connection (v0.14.0+)
      VIBECODER_DB_HOST: postgres
      VIBECODER_DB_NAME: vibecoder
      VIBECODER_DB_USER: vibecoder
      VIBECODER_DB_PASSWORD: ${VIBECODER_DB_PASSWORD:?VIBECODER_DB_PASSWORD must be set}
      # First-boot admin auto-create (optional; otherwise /setup screen)
      # VIBECODER_ADMIN_USERNAME: "admin"
      # VIBECODER_ADMIN_PASSWORD: "ChangeMe123"
    ports:
      - "17880:17880"
    volumes:
      # All persistent data lives under one host directory. tar this and
      # you've backed up everything: workspace + PG data + Android SDK + Gradle +
      # MCP packages + Playwright + Claude auth.
      - ./vibe-coder-data/workspace:/workspace
      - ./vibe-coder-data/server:/data
      - ./vibe-coder-data/dev-tools/android-sdk:/opt/android-sdk
      - ./vibe-coder-data/dev-tools/gradle:/home/vibe/.gradle
      - ./vibe-coder-data/dev-tools/npm-global:/home/vibe/.local
      - ./vibe-coder-data/dev-tools/npm-cache:/home/vibe/.npm
      - ./vibe-coder-data/dev-tools/playwright:/home/vibe/.cache/ms-playwright
      - ./vibe-coder-data/dev-tools/config:/home/vibe/.config
      - ./vibe-coder-data/claude:/home/vibe/.claude
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:17880/health"]
      interval: 30s
      timeout: 5s
      start_period: 60s
      retries: 3
```

## Common operations

```bash
docker compose logs -f vibe-coder-server          # tail server logs
docker compose restart vibe-coder-server          # restart
docker exec -it vibe-coder-server bash            # shell (root)
docker exec -it --user vibe vibe-coder-server bash   # shell (vibe user)
docker exec -it --user vibe vibe-coder-server claude --version

# Upgrade (data preserved)
docker compose pull
docker compose up -d --force-recreate
```

## Build environment persists across image upgrades ✅

Since v0.7.0 every persistent path lives under one host directory
(`./vibe-coder-data/`), so `docker compose pull && up -d` never deletes
your SDK, Gradle cache, MCP servers, Playwright browsers, or Claude auth.

| Data                              | Host path                                           | Container path                  | On recreate |
|---|---|---|---|
| Project sources + APKs            | `./vibe-coder-data/workspace/`                      | `/workspace`                    | ✅ kept |
| **PostgreSQL data (v0.14.0+)**    | `./vibe-coder-data/postgres/`                       | `/var/lib/postgresql/data` (PG container) | ✅ kept |
| Server logs + build metadata      | `./vibe-coder-data/server/`                         | `/data`                         | ✅ kept |
| Android SDK (3-4 GB)              | `./vibe-coder-data/dev-tools/android-sdk/`          | `/opt/android-sdk`              | ✅ kept |
| Gradle dependency cache (1-2 GB)  | `./vibe-coder-data/dev-tools/gradle/`               | `/home/vibe/.gradle`            | ✅ kept |
| MCP server packages (`npm -g`)    | `./vibe-coder-data/dev-tools/npm-global/`           | `/home/vibe/.local`             | ✅ kept |
| npx cache                         | `./vibe-coder-data/dev-tools/npm-cache/`            | `/home/vibe/.npm`               | ✅ kept |
| Playwright browsers (optional)    | `./vibe-coder-data/dev-tools/playwright/`           | `/home/vibe/.cache/ms-playwright` | ✅ kept |
| Other tool config                 | `./vibe-coder-data/dev-tools/config/`               | `/home/vibe/.config`            | ✅ kept |
| Claude auth (OAuth / API key / MCP registrations) | `./vibe-coder-data/claude/`         | `/home/vibe/.claude`            | ✅ kept |
| **Server body** (Ktor + Claude CLI + JDK + Node) | image layer                          | —                               | 🔄 replaced |

```bash
# Backup, one line
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/

# Move to another machine
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost 'cd ~/vibe-coder && tar xzf vibe-coder-data-*.tar.gz && docker compose up -d'
```

⚠️ **`docker compose down -v` removes named volumes.** v0.7.0+ uses bind
mounts only (no named volumes by default), but watch out if you mixed
in legacy state. For regular upgrades, always `up -d --force-recreate`.

## Web routes (v0.15.0)

All routes below sit at the root (no `/admin/*` prefix). Bearer auth or
session cookie required except `/setup`, `/login`, `/health`. Every SSR POST
carries a CSRF `_csrf` token (v0.12.4+).

| Path | Purpose |
|---|---|
| `/` | Dashboard (server / environment / activity summary) |
| `/projects` | Project list + register form (empty / clone) |
| `/projects/{id}` | Project detail, recent builds |
| `/projects/{id}/console` | Claude prompt input + live log (WebSocket) + slash chips + ▼ template dropdown + ■ stop button |
| `/projects/{id}/builds` | Queue debug build + APK download |
| `/projects/{id}/builds/{buildId}` | Build detail + live log + cancel |
| `/projects/{id}/tree` | **v0.13.0** Filesystem browser inside the project workspace |
| `/projects/{id}/view?path=...` | **v0.13.0** Read-only view (highlight.js) ↔ Edit mode (textarea) |
| `/projects/{id}/files` | Upload / download / delete (the upload area) |
| `/projects/{id}/git` | git status / diff / log (read-only) |
| `/chat` | **v0.13.0** General Chat — project-less Claude session (`__scratch__` workspace) |
| `/prompts` | **v0.13.0** Prompt template CRUD (used by the ▼ dropdown) |
| `/env-setup` | Build-environment status + one-click installers |
| `/env-setup/mcp` | MCP catalog (60+ entries, checkbox multi-select) |
| `/env-setup/claude-login` | Semi-automatic web OAuth |
| `/env-setup/tasks/{taskId}` | Live install progress (WS) |
| `/settings/git-integrations` | PAT tokens + SSH public key |
| `/settings/cors` | Read-only CORS policy viewer |
| `/audit` | **v0.15.0** Operational audit log (filter / paginate) |
| `/settings`, `/devices`, `/password` | Operations |
| `/login`, `/setup`, `/logout` | Auth |

## JSON API (v0.15.0 — for clients like the Android app)

Every UI feature has a matching `/api/*` endpoint with Bearer authentication.
Wire definitions: `shared/.../ApiPath.kt` + `shared/.../Dtos.kt`. Highlights:

- `GET  /api/server/status`, `GET /api/server/environment`, `GET /api/server/environment/check`
- `GET  /api/projects`, `POST /api/projects/register` (with `sourceType=clone` for git clone)
- `POST /api/projects/{id}/build/debug`, `GET /api/projects/{id}/builds`
- `POST /api/projects/{id}/builds/{buildId}/cancel`
- `POST /api/projects/{id}/claude/console/prompt | new | cancel`
  (`.../cancel` is **v0.13.0+** — Android `shared/` v0.6.11+ required)
- `GET  /api/projects/{id}/claude/status`
- `GET  /api/prompt-templates` (v0.13.0+ — prompt library)
- `GET  /api/env-setup/components`, `POST /api/env-setup/install-all`,
  `POST /api/env-setup/{componentId}/install`
- `POST /api/env-setup/claude-auth/upload` (multipart)
- `POST /api/env-setup/claude-auth/api-key`, `DELETE /api/env-setup/claude-auth/api-key/delete`
- `POST /api/env-setup/claude-login/start | submit | cancel`, `GET .../status`
- `GET  /api/env-setup/mcp`, `POST /api/env-setup/mcp/install | unregister`
- `POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` (multipart — Service Account JSON / Apple .p8 etc.)
- `GET  /api/settings/git-integrations`, `POST .../register | delete | ssh-keygen`
- WebSocket: `/ws/projects/{id}/console/logs`, `/ws/projects/{id}/builds/{buildId}/logs`,
  `/ws/env-setup/{taskId}/logs`

## Auth (v0.4.0+)

- `POST /api/auth/setup` — first-boot admin creation (only when DB has no admin).
- `POST /api/auth/login` — `{username, password}` → bearer token + `vibe_session` cookie.
- `POST /api/auth/password` — change password.
- `POST /api/auth/logout` — invalidate the device row (cookie + Bearer header both work).

Passwords are stored as BCrypt cost-12 hashes only. **Brute-force protection**
(v0.12.4+):
- Account lock: 10 consecutive failures → 15 min cooldown.
- IP block: 30 failures from one IP within 24 h → 24 h block (catches
  credential-stuffing across multiple accounts).
- Timing-safe dummy verify on missing users (runtime-computed valid BCrypt
  hash so the response time matches a real verification).

## Security boundaries

- **CSRF protection** (v0.12.4+) — every SSR POST carries a hidden `_csrf`
  HMAC-SHA256 token. REST API (Bearer header, not cookie) is exempt.
  Multipart uploads carry `_csrf` in the query string.
- **WebSocket Origin check** (v0.12.4+) — handshake rejects mismatched Origin
  to defend against cross-site WebSocket hijacking.
- **Workspace path safety** — `PathSafety.normalizeAndCheck` rejects any
  read/write outside `/workspace`. Symlinks are not followed
  (`LinkOption.NOFOLLOW_LINKS`).
- **Bearer tokens** stored hashed only; plaintext returned to the client once
  at issue.
- **WebSocket auth** — cookie automatic on same-origin handshake; Android
  clients send `{"type":"auth","token":"..."}` as the first frame.
- **Upload extension blacklist**: `exe`, `bat`, `cmd`, `ps1`, `sh`.
- **No raw-shell UI.** No `git push`. No release signing. No automation
  prompts that wait on stdin (CLAUDE.md non-interactive policy templated
  into every new project's `.claude/settings.json`).
- **External commands** have hard timeouts; cancellation calls
  `destroyForcibly`. The `claude` child can be SIGTERM'd mid-turn via the
  ■ stop button while preserving its session-id (`--resume` later).
- **Audit log** (v0.15.0+) — `/audit` shows every operational action with
  user / IP / result / detail for post-incident review.

## Build matrix

| Layer | Version |
|---|---|
| Base image | eclipse-temurin:17-jdk-noble (Ubuntu 24.04 LTS) |
| Gradle wrapper | 9.5.1 |
| Kotlin | 2.2.20 |
| Ktor | 3.1.2 |
| Exposed | 0.55.0 |
| PostgreSQL JDBC | 42.7.4 |
| PostgreSQL server | 17-alpine (sidecar container) |
| JDK toolchain | 17 |

## Build / run locally (without Docker)

You need a reachable PostgreSQL instance (host installation, separate Docker
container, or a remote PG). Point the server at it via env vars:

```bash
./gradlew :server:installDist

export VIBECODER_DB_HOST=127.0.0.1
export VIBECODER_DB_PORT=5432
export VIBECODER_DB_NAME=vibecoder
export VIBECODER_DB_USER=vibecoder
export VIBECODER_DB_PASSWORD=your-strong-password

./server/build/install/server/bin/server --workspace ./workspace
```

The bundled compose file already provides a `postgres` container — running
`docker compose up -d postgres` and using the same `.env` is the easiest path.

```
>>> Vibe Coder Server started
>>> URL         : http://192.168.0.10:17880
```

## License

[GNU Affero General Public License v3.0](LICENSE). Modifications served over
a network must release source under the same license. Commercial use allowed
under copyleft obligations.

## Companion repository

`vibe-coder-android` — mobile client that talks to the same server. Both
repos share the `shared/` module (DTOs / ApiPath / WsFrame); update them in
lockstep when wire changes occur. See `CHANGELOG.md` for the matrix.
