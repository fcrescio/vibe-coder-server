// v0.43.0 — REST client extracted from extension.ts.
//
// HTTP-only thin wrapper over Node 's built-in http/https. No external
// runtime deps. WebSocket logic lives in ws.ts.

import * as vscode from 'vscode';
import * as http from 'http';
import * as https from 'https';

function config() {
  return vscode.workspace.getConfiguration('vibeCoder');
}

export function serverUrl(): string {
  return (config().get<string>('serverUrl') || '').replace(/\/+$/, '');
}

export function token(): string {
  return config().get<string>('token') || '';
}

export async function setToken(value: string): Promise<void> {
  await config().update('token', value, vscode.ConfigurationTarget.Global);
}

export async function setServerUrl(value: string): Promise<void> {
  await config().update('serverUrl', value.replace(/\/+$/, ''), vscode.ConfigurationTarget.Global);
}

export interface ApiOpts {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  bearer?: boolean;
}

export interface ApiResponse {
  status: number;
  body: string;
}

export function api(path: string, opts: ApiOpts = {}): Promise<ApiResponse> {
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

export async function getJson<T>(path: string): Promise<T | null> {
  const res = await api(path);
  if (res.status !== 200) return null;
  try {
    return JSON.parse(res.body) as T;
  } catch {
    return null;
  }
}

export interface ProjectDto {
  id: string;
  name: string;
  packageName: string;
  lastBuildStatus?: string | null;
  updatedAt?: string;
}

export interface BuildDto {
  id: string;
  projectId: string;
  variant: string;
  status: string;
  startedAt?: string | null;
  finishedAt?: string | null;
}
