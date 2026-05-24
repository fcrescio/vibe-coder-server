// v0.43.0 — WebSocket subscribe for live console / build log streaming.
//
// Uses the `ws` npm package (added as runtime dep in package.json).
// First-frame auth pattern matches the server: send {"type":"auth","token":"..."}
// within 5s of connection.

import * as vscode from 'vscode';
import WebSocket from 'ws';
import { serverUrl, token } from './api';

interface LogLine {
  type: string;
  level?: string;
  message?: string;
  status?: string;
  errorMessage?: string;
}

/**
 * Subscribe to /ws/projects/{id}/console/logs and append every frame to
 * a VS Code Output Channel. Returns a Disposable that closes the
 * connection when invoked.
 */
export function followConsole(projectId: string): vscode.Disposable {
  const channel = vscode.window.createOutputChannel(`Vibe Coder · ${projectId} console`);
  channel.show(true);
  const base = serverUrl().replace(/^http/, 'ws');
  const url = `${base}/ws/projects/${encodeURIComponent(projectId)}/console/logs`;
  channel.appendLine(`[connect] ${url}`);

  const t = token();
  if (!t) {
    channel.appendLine('[error] no token — run "Vibe Coder: Login" first.');
    return new vscode.Disposable(() => channel.dispose());
  }

  const ws = new WebSocket(url);

  ws.on('open', () => {
    ws.send(JSON.stringify({ type: 'auth', token: t }));
    channel.appendLine('[open] sent auth frame');
  });

  ws.on('message', (data) => {
    const text = data.toString();
    try {
      const frame = JSON.parse(text) as LogLine;
      if (frame.type === 'log') {
        const level = frame.level || '?';
        const msg = frame.message ?? '';
        channel.appendLine(`[${level}] ${msg}`);
      } else if (frame.type === 'done') {
        channel.appendLine(`[done] ${frame.status ?? ''} ${frame.errorMessage ?? ''}`);
      } else if (frame.type === 'console_assistant') {
        // Print just the message body, no metadata noise.
        channel.appendLine(((frame as any).text as string | undefined) ?? text);
      } else if (frame.type === 'console_tool_use') {
        const tool = (frame as any).name || 'tool';
        channel.appendLine(`[tool] ${tool}`);
      } else if (frame.type === 'console_tool_result') {
        channel.appendLine(`[tool-result] ${((frame as any).text as string | undefined) ?? ''}`);
      } else if (frame.type === 'console_session_started') {
        channel.appendLine(`[session] ${(frame as any).sessionId ?? ''}`);
      } else if (frame.type === 'console_done') {
        channel.appendLine(`[turn-done]`);
      } else {
        channel.appendLine(text);
      }
    } catch {
      channel.appendLine(text);
    }
  });

  ws.on('close', (code, reason) => {
    channel.appendLine(`[close] ${code} ${reason.toString()}`);
  });

  ws.on('error', (err) => {
    channel.appendLine(`[error] ${err.message}`);
  });

  return new vscode.Disposable(() => {
    try {
      ws.close();
    } catch {
      /* ignore */
    }
    channel.dispose();
  });
}
