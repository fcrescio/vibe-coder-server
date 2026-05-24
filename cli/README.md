# vibe-coder-cli (MVP)

Single-file bash wrapper around the REST API. Useful for shell automation
and CI/CD without a full client.

## Install

```bash
sudo install -m 0755 cli/vibe /usr/local/bin/vibe
```

Or symlink:

```bash
ln -s "$(pwd)/cli/vibe" ~/.local/bin/vibe
```

## Dependencies

- `bash` 4+
- `curl` (required)
- `jq` (optional — pretty print)

## First use

```bash
vibe login
# Server URL [http://localhost:17880]: ↵
# Username: admin
# Password: ********
# TOTP code: 123456                 (only if 2FA enabled)
# logged in as admin → http://localhost:17880
# config saved to ~/.config/vibe-coder/config
```

The token is stored in `$XDG_CONFIG_HOME/vibe-coder/config` (or
`~/.config/vibe-coder/config`) with `chmod 0600`.

## Commands

| Command | Purpose |
|---|---|
| `vibe login` | Interactive login (server URL + username + password + optional TOTP) |
| `vibe logout` | Remove local token |
| `vibe whoami` | Show server URL + username + token prefix |
| `vibe projects` | List projects (JSON) |
| `vibe status` | Server status (JSON) |
| `vibe console <projectId> <prompt...>` | Send one prompt to Claude (one-shot; WS log is separate) |
| `vibe build <projectId>` | Queue a debug build |
| `vibe help` | Show this list |

## Future

This MVP is shell-only — see [[vibe-coder-server Wiki|https://github.com/siamakerlab/vibe-coder-server/wiki]]
roadmap for the planned Go/Rust port with:

- WS subscribe (`vibe console --follow <id>` streaming live)
- Project register / clone
- File upload
- Build artifact download

## License

AGPL-3.0 (same as the server).
