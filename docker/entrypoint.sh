#!/usr/bin/env bash
# vibe-coder container entrypoint.
#
# Responsibilities
#   1. Match host UID/GID (from PUID/PGID env, default 1000)
#   2. Normalize ownership for mounted volume directories
#   3. Pass admin bootstrap env through to the server
#   4. Warn when doctor has not installed Android SDK yet
#   5. Dispatch to server / vibe-doctor / shell

set -euo pipefail

# ─── 0. Colors (TTY only) ────────────────────────────────────────────────────
if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'; C_BLUE=$'\033[0;34m'
    C_YELLOW=$'\033[0;33m'; C_GREEN=$'\033[0;32m'; C_BOLD=$'\033[1m'
else
    C_RESET="" C_BLUE="" C_YELLOW="" C_GREEN="" C_BOLD=""
fi
log()  { printf '%s[entrypoint]%s %s\n' "${C_BLUE}" "${C_RESET}" "$*"; }
warn() { printf '%s[entrypoint]%s %s\n' "${C_YELLOW}" "${C_RESET}" "$*"; }
ok()   { printf '%s[entrypoint]%s %s\n' "${C_GREEN}" "${C_RESET}" "$*"; }

# ─── 1. UID/GID match ────────────────────────────────────────────────────────
# Match the vibe user's UID/GID to host PUID/PGID to avoid mounted-volume
# ownership conflicts.
PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

current_uid="$(id -u vibe)"
current_gid="$(id -g vibe)"

if [[ "$current_uid" != "$PUID" ]] || [[ "$current_gid" != "$PGID" ]]; then
    log "Matching UID/GID: ${current_uid}:${current_gid} -> ${PUID}:${PGID}"
    groupmod -o -g "$PGID" vibe 2>/dev/null || true
    usermod  -o -u "$PUID" -g "$PGID" vibe 2>/dev/null || true
fi

# ─── 2. Volume ownership ─────────────────────────────────────────────────────
# Avoid slow recursive chown on every boot: chown top-level directories once and
# let the vibe user create new files.
# v0.7.0 — persistent locations for tools that disappeared on image upgrades:
#   /home/vibe/.npm                  npx cache (frequently used by MCP)
#   /home/vibe/.cache/ms-playwright  Playwright browsers
#   /home/vibe/.local                npm global prefix for persistent MCP installs
for dir in \
    /workspace \
    /data \
    /opt/android-sdk \
    /home/vibe/.gradle \
    /home/vibe/.claude \
    /home/vibe/.config \
    /home/vibe/.npm \
    /home/vibe/.cache \
    /home/vibe/.cache/ms-playwright \
    /home/vibe/.local \
    /home/vibe/.ssh \
    /home/vibe/keystores \
; do
    if [[ -d "$dir" ]]; then
        chown vibe:vibe "$dir" 2>/dev/null || true
    fi
done

# v1.7.23 — clean root-owned leftovers from each workspace project's .gradle /
# .android cache directories. Files written by prior root `docker exec` sessions
# can break Gradle with "executionHistory.lock (Permission denied)".
for sub in .gradle .android; do
    find /workspace -mindepth 2 -maxdepth 3 -type d -name "$sub" 2>/dev/null | while read -r p; do
        bad=$(find "$p" -mindepth 1 ! -user vibe -print -quit 2>/dev/null)
        if [[ -n "$bad" ]]; then
            warn "Root-owned leftovers found under $p; running chown -R vibe:vibe"
            chown -R vibe:vibe "$p" 2>/dev/null || true
        fi
    done
done

# ─── 2b. SSH key bootstrap (v1.2.0, v1.2.1 graceful degrade) ────────────────
# Generate an ED25519 SSH keypair for the vibe user on first container boot.
# Existing keys are never overwritten, so server/image updates keep the same key.
# Keys are persisted through the mounted volume. Regeneration is an explicit
# operator action through the settings UI.
#
# v1.2.1 — SSH key bootstrap is auxiliary. Missing ssh-keygen or generation
# failures should not block server boot, even under set -e.
SSH_DIR=/home/vibe/.ssh
SSH_KEY=$SSH_DIR/id_ed25519
if [[ ! -f "$SSH_KEY" ]]; then
    if ! command -v ssh-keygen >/dev/null 2>&1; then
        warn "openssh-client is not installed; skipping automatic SSH key generation."
        warn "Install openssh-client in the image and restart if SSH git clone/push is needed."
    else
        mkdir -p "$SSH_DIR"
        chown vibe:vibe "$SSH_DIR"
        chmod 700 "$SSH_DIR"
        log "Generating SSH key (ED25519): $SSH_KEY"
        if gosu vibe:vibe ssh-keygen -t ed25519 -f "$SSH_KEY" -N "" \
            -C "vibe-coder-server@$(hostname)-$(date +%Y%m%d)" >/dev/null
        then
            chmod 600 "$SSH_KEY"
            chmod 644 "${SSH_KEY}.pub"
            ok "SSH public key generated. Copy it from Settings -> SSH Key to register with Gitea/GitHub."
        else
            warn "ssh-keygen failed; skipping automatic SSH key generation and continuing server boot."
        fi
    fi
fi
# Permission cleanup; idempotent on every boot and fixes bad mounted-volume modes.
[[ -d "$SSH_DIR" ]] && chmod 700 "$SSH_DIR" 2>/dev/null || true
[[ -f "$SSH_KEY" ]] && chmod 600 "$SSH_KEY" 2>/dev/null || true
[[ -f "${SSH_KEY}.pub" ]] && chmod 644 "${SSH_KEY}.pub" 2>/dev/null || true

# v0.7.0 — handle empty mounted .local volumes. If .npmrc is missing, npm's
# prefix can fall back to a non-persistent location, so recreate it idempotently.
if [[ ! -f /home/vibe/.npmrc ]]; then
    printf 'prefix=/home/vibe/.local\nfund=false\nupdate-notifier=false\n' \
        > /home/vibe/.npmrc
    chown vibe:vibe /home/vibe/.npmrc
fi

# ─── 2c. Persistent Git global config (v1.9.0) ───────────────────────────────
# Paired with Dockerfile ENV GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config.
# dev-tools/config covers the directory, but it may be empty on first boot, so
# create it idempotently and fix ownership. The file itself is created only after
# the user fills the "Git Identity" card in /env-setup.
GIT_CFG_DIR=/home/vibe/.config/git
GIT_CFG_FILE=$GIT_CFG_DIR/config
if [[ ! -d "$GIT_CFG_DIR" ]]; then
    mkdir -p "$GIT_CFG_DIR"
fi
chown vibe:vibe "$GIT_CFG_DIR" 2>/dev/null || true
chmod 700 "$GIT_CFG_DIR" 2>/dev/null || true
if [[ -f "$GIT_CFG_FILE" ]]; then
    chown vibe:vibe "$GIT_CFG_FILE" 2>/dev/null || true
    chmod 600 "$GIT_CFG_FILE" 2>/dev/null || true
fi

# ─── 3. Admin bootstrap (passed through env when provided) ────────────────────
JAVA_OPTS="${JAVA_OPTS:-}"
if [[ -n "${VIBECODER_ADMIN_USERNAME:-}" ]] && [[ -n "${VIBECODER_ADMIN_PASSWORD:-}" ]]; then
    log "Admin bootstrap credentials detected; passing them to the server for first-run creation"
    # Keep the password in env so it is not exposed through process arguments.
    export VIBECODER_ADMIN_USERNAME VIBECODER_ADMIN_PASSWORD
fi

# ─── 4. Android SDK hint ─────────────────────────────────────────────────────
if [[ ! -d "${ANDROID_HOME:-/opt/android-sdk}/platform-tools" ]]; then
    warn ""
    warn "Android SDK is not installed yet."
    warn "Before building Android apps, run doctor with:"
    warn ""
    warn "    docker exec -it <container-name> vibe-doctor"
    warn ""
fi

# ─── 5. Debug information ────────────────────────────────────────────────────
ok "vibe-coder container booting"
log "PUID:PGID    = ${PUID}:${PGID}"
log "ANDROID_HOME = ${ANDROID_HOME:-(unset)}"
log "WORKSPACE    = ${VIBECODER_WORKSPACE_ROOT:-(unset)}"
log "Admin URL    = http://0.0.0.0:17880/admin"

# ─── 6. Command dispatch ─────────────────────────────────────────────────────
case "${1:-server}" in
    server)
        log "Starting vibe-coder server..."
        # Drop privileges to the vibe user before running the server.
        exec gosu vibe:vibe /opt/vibe-coder/bin/server
        ;;
    doctor)
        shift
        exec gosu vibe:vibe /usr/local/bin/vibe-doctor "$@"
        ;;
    shell|bash|sh)
        exec gosu vibe:vibe /bin/bash
        ;;
    *)
        # Run any other command as vibe (`docker run ... claude --version`, etc.).
        exec gosu vibe:vibe "$@"
        ;;
esac
