package com.siamakerlab.vibecoder.server.i18n

/**
 * v0.77.0 — Phase 64 i18n. English bundle (default).
 *
 * Key 컨벤션:
 *   common.*           재사용 단어 (save, cancel, delete, back, …)
 *   nav.*              상단 nav + Settings 8 탭
 *   home.*             dashboard
 *   projects.*         project list / register / detail
 *   console.*          console (prompt / messages)
 *   builds.*           build list / detail
 *   git.*              git status / commit / log
 *   files.*            file browser
 *   env.*              environment setup (SDK / Claude / MCP / Git)
 *   claude.*           Claude auth (oauth / file / api key)
 *   mcp.*              MCP catalog
 *   gitint.*           Git integrations (PAT / SSH)
 *   settings.*         settings (account / password / language / cors / 2fa / backup)
 *   users.*            user management
 *   backup.*           backup
 *   audit.*            audit log
 *   logs.*             log search
 *   notif.*            notifications
 *   webauthn.*         passkeys
 *   error.*            error messages
 *
 * 모든 key 는 본 파일에 등록 + [MessagesKo] 에 동일 key 가 있어야 함 (linter 부재라 수동
 * 동기화). 비어있으면 [Messages.t] 가 key 자체를 반환 — 운영 중 깨진 string 즉시 발견.
 */
internal object MessagesEn {
    val MAP: Map<String, String> = mapOf(
        // ─────────────────────────────────────────────── common
        "common.save" to "Save",
        "common.cancel" to "Cancel",
        "common.delete" to "Delete",
        "common.back" to "Back",
        "common.edit" to "Edit",
        "common.create" to "Create",
        "common.update" to "Update",
        "common.confirm" to "Confirm",
        "common.close" to "Close",
        "common.next" to "Next",
        "common.previous" to "Previous",
        "common.search" to "Search",
        "common.refresh" to "Refresh",
        "common.download" to "Download",
        "common.upload" to "Upload",
        "common.copy" to "Copy",
        "common.copied" to "Copied",
        "common.loading" to "Loading…",
        "common.empty" to "Nothing here yet",
        "common.error" to "Error",
        "common.success" to "Success",
        "common.warning" to "Warning",
        "common.info" to "Info",
        "common.yes" to "Yes",
        "common.no" to "No",
        "common.ok" to "OK",
        "common.disabled" to "Disabled",
        "common.enabled" to "Enabled",
        "common.required" to "Required",
        "common.optional" to "Optional",
        "common.unknown" to "Unknown",
        "common.never" to "Never",
        "common.now" to "Now",
        "common.signOut" to "Sign out",
        "common.signIn" to "Sign in",
        "common.username" to "Username",
        "common.password" to "Password",
        "common.email" to "Email",
        "common.name" to "Name",
        "common.id" to "ID",
        "common.status" to "Status",
        "common.actions" to "Actions",
        "common.type" to "Type",
        "common.date" to "Date",
        "common.time" to "Time",
        "common.size" to "Size",
        "common.count" to "Count",
        "common.role" to "Role",
        "common.you" to "You",

        // ─────────────────────────────────────────────── nav (top-level)
        "nav.home" to "Home",
        "nav.projects" to "Projects",
        "nav.tools" to "Tools",
        "nav.builds" to "Builds",
        "nav.devices" to "Devices",
        "nav.settings" to "Settings",
        "nav.logout" to "Sign out",

        // ─────────────────────────────────────────────── settings tabs
        "settings.tab.general" to "General",
        "settings.tab.account" to "Account",
        "settings.tab.security" to "Security",
        "settings.tab.network" to "Network",
        "settings.tab.notifications" to "Notifications",
        "settings.tab.backup" to "Backup",
        "settings.tab.users" to "Users",
        "settings.tab.audit" to "Audit",
        "settings.tab.buildEnv" to "Build environment",
        "settings.tab.prompts" to "Prompts & Agents",
        "settings.tab.monitoring" to "Monitoring",

        // ─────────────────────────────────────────────── settings page
        "settings.title" to "Settings",
        "settings.general.language.title" to "Language",
        "settings.general.language.body" to "Choose the SSR language for your session. Server default applies if not set.",
        "settings.general.language.option.system" to "Use server default (%s)",
        "settings.general.language.option.en" to "English",
        "settings.general.language.option.ko" to "한국어 (Korean)",
        "settings.general.language.save" to "Save language",
        "settings.general.language.saved" to "Language saved — please refresh the page.",

        // ─────────────────────────────────────────────── home / dashboard
        "home.greeting" to "Welcome to %s",
        "home.metric.projects" to "Projects",
        "home.metric.runningTasks" to "Running",
        "home.metric.diskFree" to "Free disk",
        "home.quickActions" to "Quick actions",

        // ─────────────────────────────────────────────── projects
        "projects.title" to "Projects",
        "projects.register" to "Register project",
        "projects.empty.title" to "No projects yet",
        "projects.empty.body" to "Register your first Android project to get started.",
        "projects.delete.confirm" to "Delete project %s?",
        "projects.lastBuild" to "Last build",

        // ─────────────────────────────────────────────── env setup
        "env.title" to "Environment setup",
        "env.installAll" to "Install all",
        "env.installOne" to "Install %s",
        "env.refresh" to "Refresh",
        "env.status.installed" to "Installed",
        "env.status.missing" to "Missing",
        "env.status.installing" to "Installing…",

        // ─────────────────────────────────────────────── claude
        "claude.title" to "Claude authentication",
        "claude.option.oauth" to "OAuth (recommended)",
        "claude.option.file" to "Credentials file",
        "claude.option.apiKey" to "API key",

        // ─────────────────────────────────────────────── mcp
        "mcp.title" to "MCP catalog",
        "mcp.install" to "Install selected",

        // ─────────────────────────────────────────────── git integrations
        "gitint.title" to "Git integrations",
        "gitint.token.register" to "Register token",
        "gitint.ssh.keygen" to "Generate SSH key",

        // ─────────────────────────────────────────────── auth (setup / login / totp)
        "auth.setup.title" to "Initial setup",
        "auth.setup.heading" to "Vibe Coder Initial Setup",
        "auth.setup.intro" to "Create an admin account on first use. This account signs you in to the web and the app.",
        "auth.setup.usernameLabel" to "Username",
        "auth.setup.passwordHint" to "Password (letters + digits, 8+ characters)",
        "auth.setup.passwordConfirm" to "Confirm password",
        "auth.setup.submit" to "Create account and start",
        "auth.login.title" to "Sign in",
        "auth.login.heading" to "Sign in",
        "auth.login.submit" to "Sign in",
        "auth.login.totp.title" to "Two-factor authentication",
        "auth.login.totp.heading" to "Two-factor authentication",
        "auth.login.totp.body" to "Enter the 6-digit code shown in your authenticator app.",
        "auth.login.totp.code" to "TOTP code",
        "auth.login.totp.submit" to "Verify",
        "auth.login.totp.back" to "← Pick a different user",
        "auth.login.usernameLabel" to "Username",
        "auth.login.passwordLabel" to "Password",
        "auth.login.passkey" to "Sign in with passkey",
        "auth.login.passkeyHint" to "Use a passkey (biometric / security key) — no password required.",
        "auth.login.passkey.btn" to "🔑 Sign in with passkey",
        "auth.login.passkey.hint.disabled" to "Enter your username to enable…",
        "auth.login.passkey.fetching" to "Fetching challenge…",
        "auth.login.passkey.noCred" to "No passkey registered for this user.",
        "auth.login.passkey.using" to "Using authenticator…",
        "auth.login.passkey.fail" to "Failed: ",

        // ─────────────────────────────────────────────── dashboard
        "dashboard.title" to "Dashboard",
        "dashboard.heading" to "Dashboard",
        "dashboard.card.server" to "Server",
        "dashboard.card.server.name" to "Name",
        "dashboard.card.server.version" to "Version",
        "dashboard.card.server.jvm" to "JVM",
        "dashboard.card.server.os" to "OS",
        "dashboard.card.server.workspace" to "Workspace",
        "dashboard.card.env" to "Environment",
        "dashboard.card.env.claudeCli" to "Claude CLI",
        "dashboard.card.env.claudeAuth" to "Claude sign-in",
        "dashboard.card.env.androidSdk" to "Android SDK",
        "dashboard.card.env.doctorHint" to "If SDK is missing, run <code>vibe-doctor</code> inside the container.",
        "dashboard.card.activity" to "Activity",
        "dashboard.card.activity.projects" to "Projects",
        "dashboard.card.activity.runningBuilds" to "Running builds",
        "dashboard.card.activity.devices" to "Connected devices",
        "dashboard.card.count" to "%d",
        "dashboard.claudeOk" to "✓ OK",
        "dashboard.claudeMissing" to "✗ Not installed",
        "dashboard.sdkOk" to "✓ OK",
        "dashboard.sdkMissing" to "✗ Run doctor required",
        "dashboard.signedIn" to "✓ Signed in",
        "dashboard.signinRequired" to "✗ Sign-in required",
        "dashboard.disabled" to "(disabled)",
        "dashboard.claudeLoginHint" to "Sign in: <code>docker exec -it --user vibe vibe-coder-server claude login</code>",
        "dashboard.diskHint" to "Threshold alerts go to email / webhook. Cache cleanup:",
        "dashboard.usageQuotaLine" to "quota line",
        "dashboard.usageReset" to "Reset",
        "dashboard.usageEmailHint" to "Threshold alerts go to email. Configure:",
        "dashboard.usageParseFailed" to "(parse failed)",

        // ─────────────────────────────────────────────── password / devices / error
        "password.title" to "Change password",
        "devices.title" to "Devices",
        "devices.currentSession" to "(current session)",
        "error.page.title" to "Error %s",

        // ─────────────────────────────────────────────── error / form
        "error.required" to "%s is required",
        "error.invalid" to "Invalid %s",
        "error.notFound" to "%s not found",
        "error.forbidden" to "Forbidden",
        "error.unauthorized" to "Unauthorized",
        "error.serverError" to "Server error",
        "error.csrf" to "CSRF check failed",
    )
}
