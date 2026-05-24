# vibe-coder VS Code extension (MVP scaffold)

Talk to a self-hosted `vibe-coder-server` directly from VS Code.

## Status

**v0.39.0 — scaffold only.** Not yet published to the VS Code
Marketplace. To use locally:

```bash
cd vscode-extension
npm install
npm run compile
# In VS Code: press F5 to launch the Extension Development Host.
```

## Commands

| Command (palette `Ctrl+Shift+P`) | What it does |
|---|---|
| **Vibe Coder: Login** | Interactive — server URL + username + password (+ TOTP if enabled) |
| **Vibe Coder: Server status** | `GET /api/server/status` shown as info notification |
| **Vibe Coder: List projects** | Quick-pick of registered projects |
| **Vibe Coder: Send prompt to project console** | Quick-pick project + prompt input |
| **Vibe Coder: Trigger debug build** | Queue a debug build |

## Settings

| Key | Default | Notes |
|---|---|---|
| `vibeCoder.serverUrl` | `http://localhost:17880` | Base URL — no trailing slash |
| `vibeCoder.token` | (empty) | Bearer token; set automatically by **Login** |

Both settings are persisted **globally** (`ConfigurationTarget.Global`),
so VS Code remembers them across workspaces.

## What's not in v0.39.0

This is a minimum-viable scaffold. Planned (no version commitment):

- **WebSocket subscribe** — live console / build log following inside a
  VS Code output channel.
- **Status bar item** — current build status / Claude usage % at a glance.
- **TreeView** for projects + builds in the sidebar.
- **Webview** for the project console.

## Architecture

- No external deps beyond `@types/vscode` + `typescript`. HTTP via Node's
  built-in `http` / `https`.
- `src/extension.ts` holds everything (one file).
- 5 commands registered in `activate(context)`.

## Build & publish

```bash
npm install
npm run compile          # → out/extension.js
# package via vsce when ready to publish
npx vsce package
```

## License

AGPL-3.0 (same as server).
