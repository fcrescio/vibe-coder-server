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
- **Architectures**: `linux/amd64`, `linux/arm64`
- **Latest tags**: `0.14.0`, `latest`
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

## What's in the box (v0.14.0)

- **Claude Code CLI orchestration** — one persistent child per project,
  stream-json IO, live console relayed via WebSocket.
- **Four Claude auth options** — terminal, file upload, API key, **plus** a
  semi-automatic web OAuth (`script -q` PTY wrap, no xterm.js).
- **MCP catalog with 60+ servers** in 10 categories — checkbox multi-select,
  per-MCP token form, recommended ★ markers, trust tiers.
- **Build environment one-click installer** — Android SDK / Gradle cache /
  Node + Claude CLI / MCP packages, persisted under one host directory.
- **Git clone on project register** — public + private (HTTPS PAT or
  auto-generated ed25519 SSH key).
- **JSON API parity** — every browser feature is also at `/api/*` with
  Bearer auth, for the Android companion or third-party automation.

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
| `VIBECODER_IMAGE` | `siamakerlab/vibe-coder-server:0.14.0` | Image tag to pull |
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

```bash
docker compose stop
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/
docker compose start

# On another machine
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost 'cd ~/vibe-coder && tar xzf vibe-coder-data-*.tar.gz && docker compose up -d'
```

## Web UI routes (v0.10.0)

All routes sit at the root (no `/admin/*` prefix from v0.4.2+). Bearer
token or session cookie required except `/setup`, `/login`, `/health`.

| Path | Purpose |
|---|---|
| `/` | Dashboard |
| `/projects` | List + register (empty / clone) |
| `/projects/{id}/console` | Live Claude chat (WebSocket) |
| `/projects/{id}/builds` | Queue debug build + APK download |
| `/env-setup` | Build environment installer |
| `/env-setup/mcp` | MCP catalog (60+ entries) |
| `/env-setup/claude-login` | Semi-automatic web OAuth |
| `/settings/git-integrations` | PAT + SSH key |
| `/settings`, `/devices`, `/password` | Operations |

## JSON API (v0.10.0 — for clients)

Full reference + curl examples in the
[REST API Reference](https://github.com/siamakerlab/vibe-coder-server/wiki/REST-API-Reference)
wiki. Retrofit interfaces in the
[Android Client Guide](https://github.com/siamakerlab/vibe-coder-server/wiki/Android-Client-Guide).

Highlights:

- `POST /api/auth/login` → Bearer token
- `GET  /api/projects`, `POST /api/projects/register` (with `sourceType=clone`)
- `GET  /api/env-setup/components`, `POST /api/env-setup/install-all`
- `POST /api/env-setup/claude-auth/upload | api-key`
- `POST /api/env-setup/claude-login/start | submit | cancel`
- `GET  /api/env-setup/mcp`, `POST /api/env-setup/mcp/install | unregister`
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
# Regular development push (amd64-only, fast 2-3 min)
docker buildx build --platform linux/amd64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:<ver> \
    -t siamakerlab/vibe-coder-server:latest \
    --push .

# Milestone (multi-arch — slow 10-15 min via arm64 emulation)
docker buildx build --platform linux/amd64,linux/arm64 ...
```

Full guide: <https://github.com/siamakerlab/vibe-coder-server/blob/main/docker/README.md>

## Links

- Source code: <https://github.com/siamakerlab/vibe-coder-server>
- Wiki: <https://github.com/siamakerlab/vibe-coder-server/wiki>
- Changelog: <https://github.com/siamakerlab/vibe-coder-server/blob/main/CHANGELOG.md>
- License (AGPL-3.0): <https://github.com/siamakerlab/vibe-coder-server/blob/main/LICENSE>
