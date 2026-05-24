// v0.43.0 — vibe-coder VS Code extension (full).
//
// Commands:
//   - vibeCoder.login          : interactive login (server URL + username + password [+ TOTP])
//   - vibeCoder.status         : show server status in an info notification + update status bar
//   - vibeCoder.listProjects   : quick-pick of projects
//   - vibeCoder.sendPrompt     : prompt for projectId + prompt text, send to console
//   - vibeCoder.buildDebug     : trigger a debug build
//   - vibeCoder.followConsole  : WS subscribe to /ws/projects/{id}/console/logs → Output Channel
//   - vibeCoder.refreshTree    : refresh projects TreeView
//
// Sidebar: "Projects" tree (activity-bar icon $(rocket)).
// Status bar: "Vibe Coder: <user>@<host>" — click → vibeCoder.status.

import * as vscode from 'vscode';
import { api, getJson, ProjectDto, serverUrl, setServerUrl, setToken } from './api';
import { followConsole } from './ws';
import { ProjectTreeItem, ProjectsTreeProvider } from './treeview';

let statusBarItem: vscode.StatusBarItem | undefined;
let projectsProvider: ProjectsTreeProvider | undefined;
const consoleDisposables = new Map<string, vscode.Disposable>();

async function cmdLogin() {
  const url = await vscode.window.showInputBox({
    prompt: 'Server URL',
    value: serverUrl() || 'http://localhost:17880',
  });
  if (!url) return;
  await setServerUrl(url);

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
  await refreshStatusBar();
  projectsProvider?.refresh();
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
  await refreshStatusBar();
}

async function pickProject(): Promise<string | undefined> {
  const arr = await getJson<ProjectDto[]>('/api/projects');
  if (!arr || arr.length === 0) {
    vscode.window.showInformationMessage('No projects yet — register one in the browser at /projects.');
    return;
  }
  const pick = await vscode.window.showQuickPick(
    arr.map((p) => ({ label: p.id, description: p.name, detail: p.packageName })),
    { placeHolder: 'Pick a project' }
  );
  return pick?.label;
}

async function cmdListProjects() {
  await pickProject();
}

async function cmdSendPrompt(arg?: ProjectTreeItem | string) {
  const projectId = typeof arg === 'string' ? arg
    : (arg && (arg as ProjectTreeItem).projectId) || (await pickProject());
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

async function cmdBuildDebug(arg?: ProjectTreeItem | string) {
  const projectId = typeof arg === 'string' ? arg
    : (arg && (arg as ProjectTreeItem).projectId) || (await pickProject());
  if (!projectId) return;
  const res = await api(`/api/projects/${projectId}/build/debug`, { method: 'POST' });
  if (res.status >= 200 && res.status < 300) {
    vscode.window.showInformationMessage(`Debug build queued for ${projectId}`);
    projectsProvider?.refresh();
  } else {
    vscode.window.showErrorMessage(`Build failed (${res.status}): ${res.body.slice(0, 200)}`);
  }
}

async function cmdFollowConsole(arg?: ProjectTreeItem | string, context?: vscode.ExtensionContext) {
  const projectId = typeof arg === 'string' ? arg
    : (arg && (arg as ProjectTreeItem).projectId) || (await pickProject());
  if (!projectId) return;

  // Toggle: if already following, close it.
  const existing = consoleDisposables.get(projectId);
  if (existing) {
    existing.dispose();
    consoleDisposables.delete(projectId);
    vscode.window.showInformationMessage(`Stopped following ${projectId}`);
    return;
  }
  const sub = followConsole(projectId);
  consoleDisposables.set(projectId, sub);
  if (context) context.subscriptions.push(sub);
  vscode.window.showInformationMessage(`Following ${projectId} (open the Output panel)`);
}

async function refreshStatusBar() {
  if (!statusBarItem) return;
  const enabled = vscode.workspace.getConfiguration('vibeCoder').get<boolean>('statusBar', true);
  if (!enabled) {
    statusBarItem.hide();
    return;
  }
  const url = serverUrl();
  if (!url) {
    statusBarItem.text = '$(rocket) Vibe Coder: setup';
    statusBarItem.tooltip = 'Click to configure server URL and login';
    statusBarItem.show();
    return;
  }
  const host = url.replace(/^https?:\/\//, '');
  try {
    const s = await getJson<{ serverVersion: string; runningTaskCount: number }>('/api/server/status');
    if (s) {
      statusBarItem.text = `$(rocket) ${host} (v${s.serverVersion})`;
      statusBarItem.tooltip = `Connected · ${s.runningTaskCount} running task${s.runningTaskCount === 1 ? '' : 's'}\nClick for full status`;
    } else {
      statusBarItem.text = `$(rocket) ${host} (auth?)`;
      statusBarItem.tooltip = 'Status unreachable — token may be invalid';
    }
  } catch {
    statusBarItem.text = `$(rocket) ${host} (offline)`;
    statusBarItem.tooltip = 'Connection failed';
  }
  statusBarItem.show();
}

export function activate(context: vscode.ExtensionContext) {
  // Status bar
  statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  statusBarItem.command = 'vibeCoder.status';
  context.subscriptions.push(statusBarItem);

  // Projects TreeView
  projectsProvider = new ProjectsTreeProvider();
  context.subscriptions.push(
    vscode.window.registerTreeDataProvider('vibeCoder.projects', projectsProvider),
  );

  // Commands
  context.subscriptions.push(
    vscode.commands.registerCommand('vibeCoder.login', cmdLogin),
    vscode.commands.registerCommand('vibeCoder.status', cmdStatus),
    vscode.commands.registerCommand('vibeCoder.listProjects', cmdListProjects),
    vscode.commands.registerCommand('vibeCoder.sendPrompt', (arg) => cmdSendPrompt(arg)),
    vscode.commands.registerCommand('vibeCoder.buildDebug', (arg) => cmdBuildDebug(arg)),
    vscode.commands.registerCommand('vibeCoder.followConsole', (arg) => cmdFollowConsole(arg, context)),
    vscode.commands.registerCommand('vibeCoder.refreshTree', () => projectsProvider?.refresh()),
  );

  // Initial status-bar refresh + periodic poll (every 60 s)
  void refreshStatusBar();
  const timer = setInterval(() => void refreshStatusBar(), 60_000);
  context.subscriptions.push(new vscode.Disposable(() => clearInterval(timer)));

  // React to settings changes
  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('vibeCoder')) {
        void refreshStatusBar();
        projectsProvider?.refresh();
      }
    }),
  );
}

export function deactivate() {
  for (const d of consoleDisposables.values()) d.dispose();
  consoleDisposables.clear();
}
