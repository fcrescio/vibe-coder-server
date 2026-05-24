// v0.39.0 — vibe-coder VS Code extension (MVP scaffold).
//
// Talks to a self-hosted vibe-coder-server via REST over Bearer auth.
// Commands:
//   - vibeCoder.login          : interactive login (server URL + username + password [+ TOTP])
//   - vibeCoder.status         : show server status in an info notification
//   - vibeCoder.listProjects   : show projects in a quick-pick
//   - vibeCoder.sendPrompt     : prompt for projectId + prompt text, send to console
//   - vibeCoder.buildDebug     : trigger a debug build
//
// Uses the workspace settings `vibeCoder.serverUrl` + `vibeCoder.token`. Token is
// persisted via vscode.workspace.getConfiguration().update().
//
// Implementation is intentionally minimal — fits in a single file. A real
// release with WebSocket subscribe (live console / build logs) would split into
// multiple modules.

import * as vscode from 'vscode';
import * as http from 'http';
import * as https from 'https';

function config() {
  return vscode.workspace.getConfiguration('vibeCoder');
}

function serverUrl(): string {
  return (config().get<string>('serverUrl') || '').replace(/\/+$/, '');
}

function token(): string {
  return config().get<string>('token') || '';
}

async function setToken(value: string) {
  await config().update('token', value, vscode.ConfigurationTarget.Global);
}

interface ApiOpts {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  bearer?: boolean;
}

function api(path: string, opts: ApiOpts = {}): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const baseUrl = serverUrl();
    if (!baseUrl) return reject(new Error('vibeCoder.serverUrl is not configured'));
    const url = new URL(baseUrl + path);
    const client = url.protocol === 'https:' ? https : http;
    const headers: Record<string, string> = { 'Content-Type': 'application/json; charset=utf-8' };
    if (opts.bearer !== false) {
      const t = token();
      if (t) headers['Authorization'] = `Bearer ${t}`;
    }
    const req = client.request(
      {
        method: opts.method || 'GET',
        hostname: url.hostname,
        port: url.port || (url.protocol === 'https:' ? 443 : 80),
        path: url.pathname + url.search,
        headers,
      },
      (res) => {
        let body = '';
        res.on('data', (chunk) => (body += chunk.toString()));
        res.on('end', () => resolve({ status: res.statusCode || 0, body }));
      }
    );
    req.on('error', reject);
    if (opts.body !== undefined) req.write(JSON.stringify(opts.body));
    req.end();
  });
}

async function cmdLogin() {
  const url = await vscode.window.showInputBox({
    prompt: 'Server URL',
    value: serverUrl() || 'http://localhost:17880',
  });
  if (!url) return;
  await config().update('serverUrl', url.replace(/\/+$/, ''), vscode.ConfigurationTarget.Global);

  const username = await vscode.window.showInputBox({ prompt: 'Username' });
  if (!username) return;
  const password = await vscode.window.showInputBox({ prompt: 'Password', password: true });
  if (!password) return;

  let totpCode: string | undefined;
  const tryLogin = async () => {
    const body: Record<string, string> = { username, password, deviceName: 'vscode' };
    if (totpCode) body.totpCode = totpCode;
    return api('/api/auth/login', { method: 'POST', body, bearer: false });
  };
  let res = await tryLogin();
  if (res.status === 401 && res.body.includes('totp_required')) {
    totpCode = await vscode.window.showInputBox({ prompt: 'TOTP code (6 digits)' });
    if (!totpCode) return;
    res = await tryLogin();
  }
  if (res.status !== 200) {
    vscode.window.showErrorMessage(`Login failed (${res.status}): ${res.body.slice(0, 200)}`);
    return;
  }
  const data = JSON.parse(res.body) as { token: string; username: string };
  await setToken(data.token);
  vscode.window.showInformationMessage(`Logged in as ${data.username}`);
}

async function cmdStatus() {
  const res = await api('/api/server/status');
  if (res.status !== 200) {
    vscode.window.showErrorMessage(`status: HTTP ${res.status}`);
    return;
  }
  const s = JSON.parse(res.body);
  vscode.window.showInformationMessage(
    `${s.serverName} v${s.serverVersion} — ${s.projectCount} projects, ${s.runningTaskCount} running`
  );
}

async function cmdListProjects(): Promise<string | undefined> {
  const res = await api('/api/projects');
  if (res.status !== 200) {
    vscode.window.showErrorMessage(`projects: HTTP ${res.status}`);
    return;
  }
  const arr = JSON.parse(res.body) as Array<{ id: string; name: string; packageName: string }>;
  if (arr.length === 0) {
    vscode.window.showInformationMessage('No projects yet — register one in the browser at /projects.');
    return;
  }
  const pick = await vscode.window.showQuickPick(
    arr.map((p) => ({ label: p.id, description: p.name, detail: p.packageName })),
    { placeHolder: 'Pick a project' }
  );
  return pick?.label;
}

async function cmdSendPrompt() {
  const projectId = await cmdListProjects();
  if (!projectId) return;
  const text = await vscode.window.showInputBox({
    prompt: 'Prompt to send to the Claude console',
    placeHolder: 'Add a settings screen with dark-mode toggle',
  });
  if (!text) return;
  const res = await api(`/api/projects/${projectId}/claude/console/prompt`, {
    method: 'POST',
    body: { text },
  });
  if (res.status >= 200 && res.status < 300) {
    vscode.window.showInformationMessage(`Prompt sent to ${projectId}`);
  } else {
    vscode.window.showErrorMessage(`Prompt failed (${res.status}): ${res.body.slice(0, 200)}`);
  }
}

async function cmdBuildDebug() {
  const projectId = await cmdListProjects();
  if (!projectId) return;
  const res = await api(`/api/projects/${projectId}/build/debug`, { method: 'POST' });
  if (res.status >= 200 && res.status < 300) {
    vscode.window.showInformationMessage(`Debug build queued for ${projectId}`);
  } else {
    vscode.window.showErrorMessage(`Build failed (${res.status}): ${res.body.slice(0, 200)}`);
  }
}

export function activate(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.commands.registerCommand('vibeCoder.login', cmdLogin),
    vscode.commands.registerCommand('vibeCoder.status', cmdStatus),
    vscode.commands.registerCommand('vibeCoder.listProjects', cmdListProjects),
    vscode.commands.registerCommand('vibeCoder.sendPrompt', cmdSendPrompt),
    vscode.commands.registerCommand('vibeCoder.buildDebug', cmdBuildDebug),
  );
}

export function deactivate() {
  /* nothing — settings persist via vscode.workspace.getConfiguration().update */
}
