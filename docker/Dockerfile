# syntax=docker/dockerfile:1.7
#
# Vibe Coder Server — slim runtime image
#
# Design intent
#   - Keep Android SDK / Gradle cache / Claude auth out of the image to minimize size.
#   - Download those heavier components into volumes after container boot via `vibe-doctor`.
#   - Include only the vibe-coder server, Claude Code CLI, and setup tools.
#
# Build (maintainers)
#
#   Regular commit push (amd64-only; v0.6.3+ default):
#     docker buildx build --platform linux/amd64 \
#         -f docker/Dockerfile -t siamakerlab/vibe-coder-server:0.6.3 --push .
#
#   Milestone multi-arch push (v0.7.0 / v1.0.0, etc.):
#     docker buildx build --platform linux/amd64,linux/arm64 \
#         -f docker/Dockerfile -t siamakerlab/vibe-coder-server:0.6.3 --push .

# ------------------------------------------------------------------------------
# Stage 1: server build (Gradle installDist)
# v0.38.0 — Ubuntu 26.04 LTS rebase. eclipse-temurin's resolute tag maps to
# Ubuntu 26.04 LTS (`/etc/os-release`: Ubuntu 26.04 LTS).
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-resolute AS builder

WORKDIR /src

# Copy Gradle wrapper and build scripts. Exclude Android modules with -PskipAndroidModule=true.
COPY gradle ./gradle
COPY gradlew ./gradlew
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY shared/build.gradle.kts ./shared/
COPY server/build.gradle.kts ./server/

# Copy source
COPY shared ./shared
COPY server ./server

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/src/.gradle \
    chmod +x ./gradlew && \
    ./gradlew -PskipAndroidModule=true :server:installDist --no-daemon -x test

# ------------------------------------------------------------------------------
# Stage 2: runtime — v0.38.0 Ubuntu 26.04 LTS rebase, v0.68.1 switched to JDK.
#
# v0.68.1 hotfix: Android debug builds made Gradle's Java toolchain select
# `/opt/java/openjdk`; with JRE-only images, javac was missing and compilation failed
# (`Toolchain installation '/opt/java/openjdk' does not provide the required
# capabilities: [JAVA_COMPILER]`). Use JDK in runtime too (~130MB increase);
# for this single-user LAN tool, build stability is more important than image size.
# The builder stage already uses jdk-resolute, so there is no extra download there.
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-resolute AS runtime

LABEL org.opencontainers.image.title="Vibe Coder Server"
LABEL org.opencontainers.image.description="Ktor-based vibe-coder server (single-user LAN development tool) · Ubuntu 26.04 LTS"
LABEL org.opencontainers.image.source="https://github.com/siamakerlab/vibe-coder"
LABEL org.opencontainers.image.licenses="AGPL-3.0-or-later"
LABEL org.opencontainers.image.source="https://github.com/siamakerlab/vibe-coder-server"

# ---- System packages (minimal toolset) --------------------------------------
# Android SDK / Playwright Chromium / heavier MCP packages are installed into volumes by doctor.
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        gosu \
        expect \
        gnupg \
        jq \
        openssh-client \
        openssl \
        pipx \
        python3-venv \
        sudo \
        tini \
        tzdata \
        unzip \
        util-linux \
        wget \
        xz-utils \
        zip \
    && rm -rf /var/lib/apt/lists/*
# util-linux provides `script` for PTY wrapping. v0.7.0 option A needs it to
# spawn the `claude auth login` TUI for semi-automatic web OAuth.

# ---- Node.js 20 LTS + Claude Code CLI ----------------------------------------
# Node is required for Claude CLI and MCP execution, so it is included in the image.
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && npm config set fund false \
    && npm config set update-notifier false \
    && npm install -g @anthropic-ai/claude-code \
    && npm cache clean --force

# ---- Mistral Vibe CLI / ACP runtime -----------------------------------------
# Optional runtime used when VIBECODER_AGENT_PROVIDER=mistral-vibe-acp. It talks
# to OpenAI-compatible providers such as llama.cpp through ~/.vibe/config.toml.
ENV PIPX_HOME=/opt/pipx
ENV PIPX_BIN_DIR=/usr/local/bin
ARG MISTRAL_VIBE_VERSION=2.14.0
RUN pipx install "mistral-vibe==${MISTRAL_VIBE_VERSION}"
COPY --chown=root:root docker/scripts/patch-mistral-vibe-sse.py /tmp/patch-mistral-vibe-sse.py
COPY --chown=root:root docker/scripts/patch-mistral-vibe-acp-images.py /tmp/patch-mistral-vibe-acp-images.py
RUN /opt/pipx/venvs/mistral-vibe/bin/python /tmp/patch-mistral-vibe-sse.py \
    && /opt/pipx/venvs/mistral-vibe/bin/python /tmp/patch-mistral-vibe-acp-images.py \
    && rm /tmp/patch-mistral-vibe-sse.py /tmp/patch-mistral-vibe-acp-images.py
COPY --chown=root:root docker/vibe-tools/device.py /opt/vibe-coder/vibe-tools/device.py

# ---- User / directories ------------------------------------------------------
# The entrypoint adjusts this to the host UID when requested. These are defaults.
ARG VIBE_UID=1000
ARG VIBE_GID=1000
# Ubuntu 24.04 (noble) images pre-create an `ubuntu` user with UID 1000.
# Remove it first to avoid collisions; ignore failures on bases without it.
RUN userdel -r ubuntu 2>/dev/null || true \
    && groupdel ubuntu 2>/dev/null || true \
    && groupadd --gid ${VIBE_GID} vibe \
    && useradd --uid ${VIBE_UID} --gid ${VIBE_GID} --create-home --shell /bin/bash vibe \
    && mkdir -p \
        /opt/android-sdk \
        /workspace \
        /data \
        /home/vibe/.claude \
        /home/vibe/.vibe \
        /home/vibe/.gradle \
        /home/vibe/.config \
        /home/vibe/.npm \
        /home/vibe/.cache \
        /home/vibe/.cache/ms-playwright \
        /home/vibe/.local/bin \
        /home/vibe/.local/lib/node_modules \
    && printf 'prefix=/home/vibe/.local\nfund=false\nupdate-notifier=false\n' > /home/vibe/.npmrc \
    && chown -R vibe:vibe /opt/android-sdk /workspace /data /home/vibe \
    && printf 'vibe ALL=(ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/vibe-nopasswd \
    && chmod 0440 /etc/sudoers.d/vibe-nopasswd \
    && visudo -c -f /etc/sudoers.d/vibe-nopasswd

# v0.7.0 — NOPASSWD sudo for the vibe user.
# Rationale: this is a single-user LAN container with the external trust boundary
# at Docker isolation. Claude Code may need sudo for package installs or permission
# cleanup; password prompts would hang the vibe-coder stream, so bypass them.
# This is separate from CLAUDE.md §3's raw shell UI policy: Claude Code still
# decides which sudo command to run under its own policy.

# ---- vibe-coder server ------------------------------------------------------
COPY --from=builder --chown=vibe:vibe /src/server/build/install/server /opt/vibe-coder

# ---- doctor / entrypoint -----------------------------------------------------
COPY --chown=root:root docker/doctor /opt/vibe-doctor
COPY --chown=root:root docker/entrypoint.sh /usr/local/bin/entrypoint.sh
# v1.4.0 — Claude TUI Usage tab PTY capture (expect script). Slash commands are
# blocked in --print mode, so quota cannot be read another way.
COPY --chown=root:root docker/scripts/claude-usage-capture.exp /usr/local/bin/claude-usage-capture.exp
RUN chmod +x /opt/vibe-doctor/vibe-doctor \
    && chmod +x /opt/vibe-doctor/lib/*.sh \
    && chmod +x /usr/local/bin/entrypoint.sh \
    && chmod +x /usr/local/bin/claude-usage-capture.exp \
    && ln -s /opt/vibe-doctor/vibe-doctor /usr/local/bin/vibe-doctor \
    && ln -s /opt/vibe-coder/bin/server /usr/local/bin/vibe-coder-server

# ---- Runtime environment ----------------------------------------------------
# Android SDK path (volume)
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

# vibe-coder server config path
ENV VIBECODER_WORKSPACE_ROOT=/workspace
ENV VIBECODER_DATA_DIR=/data

# Claude CLI auth path (volume)
ENV CLAUDE_CONFIG_DIR=/home/vibe/.claude
ENV VIBE_HOME=/home/vibe/.vibe

# v1.9.0 — force Git global config to a persistent path:
# `/home/vibe/.config/git/config`. `/home/vibe/.config` is already persisted by
# the `dev-tools/config` volume, so user-entered `user.name` / `user.email`
# survive container restarts and image upgrades. Git 2.32+ honors GIT_CONFIG_GLOBAL
# for child git processes such as clone / commit / log.
ENV GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config

# v0.7.0 — move the vibe user's npm global prefix to /home/vibe/.local.
# `vibe-doctor mcp` installs global npm packages into a persistent volume instead
# of a system directory, so MCP tools survive image upgrades. Add
# /home/vibe/.local/bin to PATH so Claude CLI can find MCP commands.
ENV PATH=/home/vibe/.local/bin:${PATH}

# JVM options (user-overridable through .env)
ENV JAVA_OPTS="-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"

# ---- Port / healthcheck -----------------------------------------------------
EXPOSE 17880

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -fsS http://127.0.0.1:17880/health || exit 1

# ---- Execution --------------------------------------------------------------
# tini handles PID 1 signals; entrypoint matches UID and switches to vibe via gosu.
WORKDIR /workspace
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
CMD ["server"]
