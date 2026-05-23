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
├─ server/              # Ktor server (Netty), SQLite (Exposed),
│                       # Claude/Gradle/Git child processes, WS log hub,
│                       # Admin web UI (SSR HTML)
└─ docker/              # Slim Docker image + compose + vibe-doctor
```

## What's inside (v0.10.0)

- **Claude Code CLI orchestration** — one persistent child process per
  project, stream-json on stdin/stdout, console log relayed live over WS.
- **Three Claude auth options** — terminal, file upload, API key, **plus** a
  semi-automatic web OAuth (`script -q` PTY wrap, no xterm.js).
- **MCP catalog** — 60+ Model Context Protocol servers in 10 categories,
  checkbox multi-select, per-MCP token form, recommended ★, trust tiers.
- **Build environment one-click installer** — Android SDK / Gradle cache /
  Node + Claude CLI / MCP packages, all persisted under one host directory.
- **Git clone on project register** — public / private (HTTPS PAT or SSH
  key) with auto-generated ed25519 key pair.
- **JSON API parity** — every admin UI feature is also exposed under `/api/*`
  with Bearer authentication for the Android companion app.

## Quick start (Docker, 3 minutes)

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# Edit .env to set PUID/PGID (id -u; id -g) and host port — defaults work too.
docker compose up -d

# 1. Open http://<PC IP>:17880/setup in a browser to create the admin user.
# 2. Go to "Build environment" → click "Install/update all".
#    (Android SDK download, 3-4GB, 5-15 min.)
# 3. Build environment → "Claude login" card → pick option 0/1/2/3.
```

## Minimum `docker-compose.yaml` (write your own)

```yaml
name: vibe-coder
services:
  vibe-coder-server:
    image: siamakerlab/vibe-coder-server:0.10.0
    container_name: vibe-coder-server
    restart: unless-stopped
    environment:
      PUID: "1000"       # id -u
      PGID: "1000"       # id -g
      TZ: "Asia/Seoul"
      JAVA_OPTS: "-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"
      # First-boot admin auto-create (optional; otherwise /setup screen)
      # VIBECODER_ADMIN_USERNAME: "admin"
      # VIBECODER_ADMIN_PASSWORD: "ChangeMe123"
    ports:
      - "17880:17880"
    volumes:
      # All persistent data lives under one host directory. tar this and
      # you've backed up everything: workspace + DB + Android SDK + Gradle +
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
      start_period: 20s
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
| SQLite DB + server logs           | `./vibe-coder-data/server/`                         | `/data`                         | ✅ kept |
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

## Web routes (v0.10.0)

All routes below sit at the root (no `/admin/*` prefix). Bearer auth or
session cookie required except `/setup`, `/login`, `/health`.

| Path | Purpose |
|---|---|
| `/` | Dashboard (server / environment / activity summary) |
| `/projects` | Project list + register form (empty / clone) |
| `/projects/{id}` | Project detail, recent builds |
| `/projects/{id}/console` | Claude prompt input + live log (WebSocket) + slash chips |
| `/projects/{id}/builds` | Queue debug build + APK download |
| `/projects/{id}/builds/{buildId}` | Build detail + live log + cancel |
| `/projects/{id}/files` | File upload / download / delete |
| `/projects/{id}/git` | git status / diff / log (read-only) |
| `/env-setup` | Build-environment status + one-click installers |
| `/env-setup/mcp` | MCP catalog (60+ entries, checkbox multi-select) |
| `/env-setup/claude-login` | Semi-automatic web OAuth |
| `/env-setup/tasks/{taskId}` | Live install progress (WS) |
| `/settings/git-integrations` | PAT tokens + SSH public key |
| `/settings`, `/devices`, `/password` | Operations |
| `/login`, `/setup`, `/logout` | Auth |

## JSON API (v0.10.0 — for clients like the Android app)

Every UI feature has a matching `/api/*` endpoint with Bearer authentication.
Wire definitions: `shared/.../ApiPath.kt` + `shared/.../Dtos.kt`. Highlights:

- `GET  /api/server/status`, `GET /api/server/environment`, `GET /api/server/environment/check`
- `GET  /api/projects`, `POST /api/projects/register` (with `sourceType=clone` for git clone)
- `POST /api/projects/{id}/build/debug`, `GET /api/projects/{id}/builds`
- `GET  /api/env-setup/components`, `POST /api/env-setup/install-all`,
  `POST /api/env-setup/{componentId}/install`
- `POST /api/env-setup/claude-auth/upload` (multipart)
- `POST /api/env-setup/claude-auth/api-key`, `DELETE /api/env-setup/claude-auth/api-key/delete`
- `POST /api/env-setup/claude-login/start | submit | cancel`, `GET .../status`
- `GET  /api/env-setup/mcp`, `POST /api/env-setup/mcp/install | unregister`
- `GET  /api/settings/git-integrations`, `POST .../register | delete | ssh-keygen`
- WebSocket: `/ws/projects/{id}/console/logs`, `/ws/projects/{id}/builds/{buildId}/logs`,
  `/ws/env-setup/{taskId}/logs`

## Auth (v0.4.0+)

- `POST /api/auth/setup` — first-boot admin creation (only when DB has no admin).
- `POST /api/auth/login` — `{username, password}` → bearer token + `vibe_session` cookie.
- `POST /api/auth/password` — change password.

Passwords are stored as BCrypt cost-12 hashes only. 10 consecutive failures
lock the account for 15 min. Timing-attack-safe dummy verify on missing users.

## Security boundaries (MVP)

- Workspace-relative path checks (`PathSafety.normalizeAndCheck`) reject any
  read/write outside `/workspace`.
- Bearer tokens hashed; plaintext returned to the client only once at issue.
- WebSocket auth happens via the first `{"type":"auth","token":"..."}` frame
  (not via URL query).
- Upload extension blacklist: `exe`, `bat`, `cmd`, `ps1`, `sh`.
- No raw-shell UI. No `git push`. No release signing. No automation prompts
  that wait on stdin (CLAUDE.md non-interactive policy templated into every
  new project's `.claude/settings.json`).
- All external commands have hard timeouts; cancellation calls `destroyForcibly`.

## Build matrix

| Layer | Version |
|---|---|
| Base image | eclipse-temurin:17-jdk-noble (Ubuntu 24.04 LTS) |
| Gradle wrapper | 9.5.1 |
| Kotlin | 2.2.20 |
| Ktor | 3.1.2 |
| Exposed | 0.55.0 |
| SQLite JDBC | 3.46.1.3 |
| JDK toolchain | 17 |

## Build / run locally (without Docker)

```bash
./gradlew :server:installDist
./server/build/install/server/bin/server --workspace ./workspace
```

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
