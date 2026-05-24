# vibe-coder VS Code extension

Talk to a self-hosted [vibe-coder-server](https://github.com/siamakerlab/vibe-coder-server)
directly from VS Code.

## Features (v0.2.0 — for server v0.43.0+)

- **Projects sidebar** (activity-bar icon $(rocket)) — TreeView of every
  registered project with recent builds (last 20 per project).
- **Status bar** — `host (vX.Y.Z)`, refreshed every 60 s. Click for full
  server status.
- **Live console (WebSocket subscribe)** — `Vibe Coder: Follow project
  console` opens an Output Channel and streams every `assistant` /
  `tool_use` / `tool_result` / `done` frame in real time. Toggle off by
  re-running the command on the same project.
- **Quick-pick** for projects, builds, prompt input.
- **TOTP-aware login** — handles `401 totp_required` automatically.

## Install

### From a `.vsix` (local)

```bash
cd vscode-extension
npm install
npm run package     # produces vibe-coder-0.2.0.vsix
code --install-extension vibe-coder-0.2.0.vsix
```

### Dev mode

```bash
cd vscode-extension
npm install
npm run watch
# In VS Code: press F5 to launch the Extension Development Host.
```

## Commands (Ctrl/Cmd+Shift+P)

| Command | Behavior |
|---|---|
| **Vibe Coder: Login** | Interactive — server URL + username + password (+ TOTP if enabled) |
| **Vibe Coder: Server status** | `GET /api/server/status` → info notification + status-bar update |
| **Vibe Coder: List projects** | Quick-pick of registered projects |
| **Vibe Coder: Send prompt to project console** | Project quick-pick (or TreeView right-click) + prompt input |
| **Vibe Coder: Trigger debug build** | Quick-pick or TreeView right-click |
| **Vibe Coder: Follow project console (WebSocket)** | Live stream into Output Channel. Re-run to toggle off. |
| **Vibe Coder: Refresh projects tree** | Re-fetch the sidebar |

### Tree right-click menu

In the **Projects** sidebar:

- **$(eye) Follow console** — inline button next to each project.
- Right-click → "Send prompt..." or "Trigger debug build".

## Settings

| Key | Default | Notes |
|---|---|---|
| `vibeCoder.serverUrl` | `http://localhost:17880` | Base URL (no trailing slash) |
| `vibeCoder.token` | (empty) | Bearer token; set by **Login** |
| `vibeCoder.statusBar` | `true` | Hide the status-bar item with `false` |

All settings persist to user **Global** settings.

## What's not in v0.2.0

- **No Webview rendering** of the project console (only Output Channel).
- **No build log streaming** (separate WS endpoint planned).
- **No Webview for `/multi-console`** — use the browser.
- **No Marketplace listing yet** — package + install locally for now.

## Marketplace publish (maintainer)

```bash
npm install -g @vscode/vsce
vsce login siamakerlab        # requires Microsoft Marketplace PAT
npm run package
vsce publish                  # also: vsce publish minor
```

For Open VSX (cross-marketplace):

```bash
npm install -g ovsx
ovsx publish vibe-coder-0.2.0.vsix --pat <OPEN_VSX_PAT>
```

The icon (`icon.png`) is currently shared with the server — Marketplace
accepts up to 1.5 MB but renders at 128×128. A dedicated smaller asset
is on the to-do list.

## License

AGPL-3.0 (same as the server).
